package com.dshmobile.shell

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Single-runtime container provisioning: proot extraction (idempotent, all
 * five files required) and the engine argv/env — the dsh engine boots node
 * inside the Debian rootfs via proot. The proot binary is stubbed
 * (ensureProot short-circuits on existing files); the wrapper/mirror layer
 * is gone with the two-runtime design.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProotRuntimeTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private lateinit var context: Context
  private lateinit var runtime: ProotRuntime

  private fun fakeProotBin() {
    File(context.filesDir, "proot/proot").apply {
      parentFile!!.mkdirs()
      writeText("stub")
    }
    File(context.filesDir, "proot/libtalloc.so.2").apply {
      parentFile!!.mkdirs()
      writeText("stub")
    }
    File(context.filesDir, "proot/libandroid-shmem.so").apply {
      parentFile!!.mkdirs()
      writeText("stub")
    }
    File(context.filesDir, "proot/loader").apply {
      parentFile!!.mkdirs()
      writeText("stub")
    }
    File(context.filesDir, "proot/loader32").apply {
      parentFile!!.mkdirs()
      writeText("stub")
    }
  }

  @Before
  fun setUp() {
    context = RuntimeEnvironment.getApplication()
    fakeProotBin()
    runtime = ProotRuntime(context)
  }

  @Test
  fun `ensureProot requires the shared libs too`() {
    // proot binary present but libtalloc missing: cannot short-circuit, and
    // with empty assets the extraction cannot succeed either.
    File(context.filesDir, "proot/libtalloc.so.2").delete()
    assertFalse(runtime.ensureProot())
  }

  @Test
  fun `ensureProot requires the loaders too`() {
    // Termux proot is built with PROOT_UNBUNDLE_LOADER: it execs the loader
    // for every jailed program start. A missing loader is a dead container
    // (device: "execve(\"/usr/bin/bash\"): Permission denied" because the
    // compiled-in fallback path lives inside com.termux's data dir).
    File(context.filesDir, "proot/loader").delete()
    assertFalse(runtime.ensureProot())
  }

  @Test
  fun `ensureProot is idempotent`() {
    assertTrue(runtime.ensureProot())
    assertTrue(runtime.ensureProot())
  }

  @Test
  fun `engineArgs boot node inside the rootfs`() {
    val rootfs = File(context.filesDir, "rootfs")
    val projects = File(context.filesDir, "projects")
    val (args, env) = runtime.buildEngineArgs(rootfs, projects, 3080, "tok")
    val joined = args.joinToString(" ")
    // proot prefix with the single rootfs.
    assertTrue(joined.contains("--kill-on-exit"))
    assertTrue(joined.contains("-r " + rootfs.absolutePath))
    assertTrue(joined.contains("--expose-internals"))
    // Engine entry: node + dsh INSIDE the container.
    assertEquals("/root/.dsh-arm64/node_modules/@deepseek-ai/dsh/lib/bin.js", args.last { it.endsWith("bin.js") })
    assertTrue(joined.contains("web"))
    // Workspace bind: host projects -> /root/projects.
    assertTrue(joined.contains(projects.absolutePath + ":/root/projects"))
    // Container env is rebuilt by env -i; only proot itself gets the host
    // LD_LIBRARY_PATH (bionic deps in its own dir).
    assertTrue(joined.contains("/usr/bin/env"))
    assertTrue(joined.contains("-i"))
    assertTrue(env.getValue("LD_LIBRARY_PATH").isNotEmpty())
    // Termux proot redirection: loaders + temp dir must point at OUR app data,
    // never at the compiled-in /data/data/com.termux/… paths (unreachable for
    // another uid — the device-reproduced EACCES on execve of the loader).
    assertEquals(File(context.filesDir, "proot/loader").absolutePath, env["PROOT_LOADER"])
    assertEquals(File(context.filesDir, "proot/loader32").absolutePath, env["PROOT_LOADER_32"])
    assertEquals(File(context.filesDir, "proot-tmp").absolutePath, env["PROOT_TMP_DIR"])
    // get_temp_directory() realpaths() the value at first use: the dir has to
    // exist by the time the proot process starts (buildEngineArgs mkdirs it).
    assertTrue(File(context.filesDir, "proot-tmp").isDirectory)
    assertTrue(
      "no proot env value may leak a com.termux path",
      env.values.none { it.contains("com.termux") },
    )
    // Termux proot compatibility (device-reproduced "unknown option '--'"
    // with proot 5.1.107): NO double-dash separator anywhere; the jailed
    // command starts directly at the entry executable, right after the last
    // proot option.
    assertFalse(args.contains("--"))
    val cmdAt = args.indexOf(ProotRuntime.CONTAINER_ENTRY)
    assertTrue("argv must carry the container entry", cmdAt > 0)
    assertEquals(
      "container command must directly follow the last proot option",
      "--link2symlink",
      args[cmdAt - 1],
    )
    // Hardlink emulation must be ON: AOSP sepolicy grants apps no file:link
    // on app_data_file, so without it every fs.link inside the jail dies
    // with EACCES (device-reproduced at dsh's session commit).
    assertTrue(args.contains("--link2symlink"))
  }

  @Test
  fun `resolvConf is created once and reused`() {
    val f = runtime.resolvConf()
    assertTrue(f.isFile)
    assertTrue(f.readText().contains("nameserver"))
    val before = f.readText()
    runtime.resolvConf()
    assertEquals(before, f.readText())
  }
}
