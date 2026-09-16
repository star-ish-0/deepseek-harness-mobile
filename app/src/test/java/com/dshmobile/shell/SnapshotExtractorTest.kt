package com.dshmobile.shell

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Extraction safety net: traversal guard, symlink/hard-link handling,
 * W^X write-bit stripping, exec-bit enforcement and idempotent overwrite of
 * previously stripped files (interrupted-extraction recovery).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SnapshotExtractorTest {
  @get:Rule
  val tmp = TemporaryFolder()

  /** Build an in-memory tar with the given entries. */
  private fun buildTar(entries: List<Triple<String, String, ByteArray>>): ByteArray {
    val out = ByteArrayOutputStream()
    TarArchiveOutputStream(out).use { tar ->
      tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
      for ((name, link, content) in entries) {
        when (link) {
          "DIR" -> {
            val entry = TarArchiveEntry(name, TarArchiveEntry.LF_DIR)
            entry.setMode(0x1ED) // 0o755 — Kotlin has no octal literals
            tar.putArchiveEntry(entry)
            tar.closeArchiveEntry()
          }

          "SYM" -> {
            val entry = TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK)
            entry.setLinkName(String(content, Charsets.UTF_8))
            tar.putArchiveEntry(entry)
            tar.closeArchiveEntry()
          }

          "HARD" -> {
            val entry = TarArchiveEntry(name, TarArchiveEntry.LF_LINK)
            entry.setLinkName(String(content, Charsets.UTF_8))
            tar.putArchiveEntry(entry)
            tar.closeArchiveEntry()
          }

          else -> {
            val entry = TarArchiveEntry(name)
            entry.setSize(content.size.toLong())
            entry.setMode(0x1ED) // 0o755 — Kotlin has no octal literals
            tar.putArchiveEntry(entry)
            tar.write(content)
            tar.closeArchiveEntry()
          }
        }
      }
    }
    return out.toByteArray()
  }

  private fun extract(
    bytes: ByteArray,
    dest: File,
  ) {
    val tar = TarArchiveInputStream(ByteArrayInputStream(bytes))
    SnapshotExtractor.extractTar(tar, dest)
    tar.close()
  }

  @Test
  fun `plain files and directories extract with exec bits`() {
    val dest = tmp.newFolder("a")
    extract(
      buildTar(
        listOf(
          Triple("usr", "DIR", ByteArray(0)),
          Triple("usr/bin", "DIR", ByteArray(0)),
          Triple("usr/bin/tool", "", "#!/bin/sh\n".toByteArray()),
        ),
      ),
      dest,
    )
    val tool = File(dest, "usr/bin/tool")
    assertTrue(tool.isFile)
    assertTrue(tool.canExecute())
    // W^X policy: executables must not stay writable.
    assertFalse(tool.canWrite())
  }

  @Test
  fun `traversal via dotdot is rejected`() {
    val dest = tmp.newFolder("b")
    val e =
      assertThrows(java.io.IOException::class.java) {
        extract(buildTar(listOf(Triple("../evil", "", "x".toByteArray()))), dest)
      }
    assertTrue(e.message!!.contains("escapes"))
    assertFalse(File(dest, "evil").exists())
  }

  @Test
  fun `symlink entries are preserved`() {
    val dest = tmp.newFolder("d")
    extract(
      buildTar(
        listOf(
          Triple("usr", "DIR", ByteArray(0)),
          Triple("usr/link", "SYM", "target".toByteArray()),
        ),
      ),
      dest,
    )
    val link = File(dest, "usr/link")
    assertTrue(
      java.nio.file.Files
        .isSymbolicLink(link.toPath()),
    )
    val target =
      java.nio.file.Files
        .readSymbolicLink(link.toPath())
    assertTrue(target.toString().endsWith("target"))
  }

  @Test
  fun `dangling symlink does not deadlock a retry`() {
    val dest = tmp.newFolder("e")
    // First run creates the dangling link (target entry never arrives).
    extract(buildTar(listOf(Triple("usr/link", "SYM", "missing".toByteArray()))), dest)
    assertTrue(
      java.nio.file.Files
        .isSymbolicLink(File(dest, "usr/link").toPath()),
    )
    // Second run over the same tree must succeed (isSymbolicLink-based delete).
    extract(
      buildTar(
        listOf(
          Triple("usr", "DIR", ByteArray(0)),
          Triple("usr/link", "SYM", "missing".toByteArray()),
        ),
      ),
      dest,
    )
    assertTrue(
      java.nio.file.Files
        .isSymbolicLink(File(dest, "usr/link").toPath()),
    )
  }

  @Test
  fun `re-extraction survives host-resolvable absolute symlinks`() {
    val dest = tmp.newFolder("j")
    // Device-reproduced failure: Debian's ./etc/alternatives/pager -> /bin/more
    // — the absolute target HAPPENS TO EXIST on the host (Android ships
    // /bin/more), so canonicalizing the entry path on re-extraction resolved
    // through the stale link to the host path and every retry died with
    // "tar entry escapes extraction root". The guard must stay lexical, so a
    // re-extraction over the same tree must succeed and re-create the link.
    val more = tmp.newFile("host-more")
    more.writeText("host more")
    val tarBytes =
      buildTar(
        listOf(
          Triple("etc", "DIR", ByteArray(0)),
          Triple("etc/alternatives", "DIR", ByteArray(0)),
          Triple("etc/alternatives/pager", "SYM", more.absolutePath.toByteArray()),
          Triple("etc/alternatives/README", "", "alt".toByteArray()),
        ),
      )
    extract(tarBytes, dest)
    val pager = File(dest, "etc/alternatives/pager")
    assertTrue(
      java.nio.file.Files
        .isSymbolicLink(pager.toPath()),
    )
    // Regression: second run over the same tree must NOT throw.
    extract(tarBytes, dest)
    assertTrue(
      java.nio.file.Files
        .isSymbolicLink(pager.toPath()),
    )
    assertEquals(
      more.absolutePath,
      java.nio.file.Files
        .readSymbolicLink(pager.toPath()).toString(),
    )
    assertEquals("alt", File(dest, "etc/alternatives/README").readText())
  }

  @Test
  fun `hard link entries are materialized as copies`() {
    val dest = tmp.newFolder("f")
    extract(
      buildTar(
        listOf(
          Triple("usr", "DIR", ByteArray(0)),
          Triple("usr/real", "", "payload-content".toByteArray()),
          Triple("usr/hard", "HARD", "usr/real".toByteArray()),
        ),
      ),
      dest,
    )
    val hard = File(dest, "usr/hard")
    assertTrue(hard.isFile)
    assertEquals("payload-content", hard.readText())
    assertFalse(
      java.nio.file.Files
        .isSymbolicLink(hard.toPath()),
    )
  }

  @Test
  fun `overwrite of a W-X-stripped file is idempotent`() {
    val dest = tmp.newFolder("g")
    val bytes = buildTar(listOf(Triple("usr/bin/tool", "", "v1".toByteArray())))
    extract(bytes, dest)
    val tool = File(dest, "usr/bin/tool")
    assertFalse(tool.canWrite()) // stripped by the first extraction
    // Re-extract over the same file: must not fail with EACCES.
    extract(bytes, dest)
    assertEquals("v1", tool.readText())
    assertTrue(tool.canExecute())
  }

  @Test
  fun `progress callback reports total bytes`() {
    val dest = tmp.newFolder("h")
    val bytes =
      buildTar(
        listOf(
          Triple("a", "", "aaa".toByteArray()),
          Triple("b", "", "bbbbb".toByteArray()),
        ),
      )
    var reported = 0L
    val tar = TarArchiveInputStream(ByteArrayInputStream(bytes))
    SnapshotExtractor.extractTar(tar, dest) { done -> reported = done }
    tar.close()
    assertTrue("progress must cover all entry bytes, got $reported", reported >= 8)
  }

  @Test
  fun `stream wrapper closes input on error`() {
    val dest = tmp.newFolder("i")
    // A VALID xz stream carrying a traversal entry: the error surfaces inside
    // extractTar (after XZ construction), so the finally must close the input.
    val tarBytes = buildTar(listOf(Triple("../evil", "", "x".toByteArray())))
    val xzBytes =
      java.io.ByteArrayOutputStream().use { out ->
        org.apache.commons.compress.compressors.xz.XZCompressorOutputStream(out).use { xz ->
          xz.write(tarBytes)
        }
        out.toByteArray()
      }
    val closed =
      java.util.concurrent.atomic
        .AtomicBoolean(false)
    val wrapped =
      object : java.io.InputStream() {
        override fun read(): Int = xzBytes.let { if (pos < it.size) it[pos++].toInt() else -1 }

        override fun read(
          b: ByteArray,
          off: Int,
          len: Int,
        ): Int =
          xzBytes.let {
            if (pos >= it.size) {
              -1
            } else {
              val n = minOf(len, it.size - pos)
              System.arraycopy(it, pos, b, off, n)
              pos += n
              n
            }
          }

        override fun close() {
          closed.set(true)
          super.close()
        }

        private var pos = 0
      }
    assertThrows(java.io.IOException::class.java) {
      SnapshotExtractor.extract(wrapped, xzBytes.size.toLong(), dest) { _, _ -> }
    }
    assertTrue("input stream must be closed even on error", closed.get())
  }
}
