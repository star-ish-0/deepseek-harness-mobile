package com.dshmobile.shell

import android.content.Context
import java.io.File

/**
 * Proot single-runtime container: extracts the Termux proot binary + its
 * dependencies (libtalloc, libandroid-shmem) from APK assets and builds the
 * engine argv — the dsh web engine and the agent shell share ONE embedded
 * Debian glibc rootfs, so no wrapper and no env injection exist anymore.
 * Mirrors/workspace are pre-provisioned inside the rootfs artifact
 * (build-rootfs.sh); nothing is written into the container at runtime except
 * the resolv.conf bind source.
 */
class ProotRuntime(
  private val context: Context,
) {
  val prootDir: File get() = File(context.filesDir, "proot")
  val prootBin: File get() = File(prootDir, "proot")

  companion object {
    /** Container entry executable. Doubles as the argv marker where proot
     *  options end and the jailed command begins — Termux proot's parser has
     *  NO "--" separator: it aborts with `unknown option '--'` (verified in
     *  termux/proot src/cli/cli.c, device-reproduced with 5.1.107). The
     *  command simply starts at the first argument not prefixed with "-". */
    const val CONTAINER_ENTRY = "/usr/bin/env"
  }

  fun resolvConf(): File {
    val f = File(context.filesDir, "etc/resolv.conf")
    if (!f.isFile) {
      f.parentFile?.mkdirs()
      // AliDNS first: reachable in CN networks, where 8.8.8.8 would stall
      // every first lookup. Google DNS kept as a secondary.
      f.writeText("nameserver 223.5.5.5\nnameserver 8.8.8.8\n")
    }
    return f
  }

  private fun extractAsset(
    name: String,
    target: File,
    exec: Boolean,
  ): Boolean {
    // Reuse an already-extracted asset: overwriting one whose write bit was
    // stripped (W^X policy) fails with EACCES on reinstall-without-clear.
    if (target.isFile && target.length() > 0L) return true
    return try {
      target.parentFile?.mkdirs()
      context.assets.open("proot/arm64-v8a/$name").use { input ->
        target.outputStream().use { out -> input.copyTo(out) }
      }
      target.setExecutable(exec, true)
      // W^X: proot AND its shared libs must not stay writable — Huawei/EMUI
      // refuse to exec (and mmap PROT_EXEC) a writable file, so a left-writable
      // proot binary makes the whole container chain fail on those devices
      // (mirrors SnapshotExtractor's write-bit strip on the snapshot ELFs).
      target.setWritable(false, false)
      true
    } catch (t: Throwable) {
      AppLog.log("proot", "extract failed: $name", t)
      false
    }
  }

  /** Extract proot + its shared libs from assets. True when the binary works. */
  fun ensureProot(): Boolean {
    val talloc = File(prootDir, "libtalloc.so.2")
    val shmem = File(prootDir, "libandroid-shmem.so")
    // All three must be present: a partial extraction (interrupted) that left
    // proot but missed a lib would otherwise pass the short-circuit and then
    // fail at exec time with a confusing dynamic-loader error.
    if (prootBin.isFile && prootBin.length() > 0L &&
      talloc.isFile && talloc.length() > 0L &&
      shmem.isFile && shmem.length() > 0L
    ) {
      return true
    }
    val ok = extractAsset("proot", prootBin, exec = true)
    val tallocOk = extractAsset("libtalloc.so.2", talloc, exec = false)
    val shmemOk = extractAsset("libandroid-shmem.so", shmem, exec = false)
    AppLog.log("proot", "ensureProot executable=$ok libtalloc=$tallocOk shmem=$shmemOk")
    return ok && tallocOk && shmemOk
  }

  /**
   * Build the engine argv + env: proot with the single rootfs, engine node
   * booted inside the container. The container env is rebuilt by `env -i`
   * (glibc binaries, no Termux paths). LD_LIBRARY_PATH only reaches proot
   * itself (its bionic deps live in its own dir).
   */
  fun buildEngineArgs(
    rootfsDir: File,
    projectsDir: File,
    port: Int,
    pickToken: String,
  ): Pair<Array<String>, Map<String, String>> {
    resolvConf()
    val args =
      arrayOf(
        prootBin.absolutePath,
        "-0",
        "-r",
        rootfsDir.absolutePath,
        "-b",
        "/dev:/dev",
        "-b",
        "/proc:/proc",
        "-b",
        "/sys:/sys",
        "-b",
        resolvConf().absolutePath + ":/etc/resolv.conf",
        "-b",
        projectsDir.absolutePath + ":/root/projects",
        "-w",
        "/root",
        "--kill-on-exit",
        // No "--" separator: Termux proot rejects it ("unknown option '--'")
        // and ends its own option parsing at the first argument that does
        // not start with "-" — /usr/bin/env is already the command start.
        CONTAINER_ENTRY,
        "-i",
        "HOME=/root",
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TERM=xterm-256color",
        "DSH_HOME=/root/.dsh",
        "DSH_PICK_TOKEN=" + pickToken,
        "node",
        "--expose-internals",
        "/root/.dsh-arm64/node_modules/@deepseek-ai/dsh/lib/bin.js",
        "web",
        "--port",
        port.toString(),
      )
    val env = mapOf("LD_LIBRARY_PATH" to prootDir.absolutePath)
    return args to env
  }
}
