package com.dshmobile.shell

import java.io.File

/**
 * Container smoke test: runs a real command inside the single rootfs via the
 * exact proot argv the engine uses, so a failure here means the container is
 * genuinely unusable — proot binary, its shared libs and the rootfs node are
 * all exercised. Runs outside the engine process (fresh ProcessBuilder).
 */
class ContainerProbe(
  private val prootRuntime: ProotRuntime,
  private val rootfsDir: File,
  private val projectsDir: File,
  private val pickToken: String,
) {
  /** Returns null on success, or the combined output tail on failure. */
  fun smokeTest(): String? =
    try {
      val (args, env) = prootRuntime.buildEngineArgs(rootfsDir, projectsDir, 3080, pickToken)
      val pb =
        ProcessBuilder(smokeArgsFrom(args)).also { b ->
          b.environment().putAll(env)
          b.redirectErrorStream(true)
        }
      val proc = pb.start()
      // Bounded wait: a hung container chain must not freeze the boot flow.
      if (!proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
        AppLog.log("boot", "container smoke probe hung, killing")
        proc.destroyForcibly()
      }
      val out = proc.inputStream.bufferedReader().readText()
      if (out.contains("CONTAINER_OK")) null else out.trim().take(600)
    } catch (t: Throwable) {
      (t.message ?: t.javaClass.simpleName).take(600)
    }

  companion object {
    /**
     * Splice a bounded smoke command into an engine argv: keep the proot
     * prefix (options + bindings) and replace the jailed command with
     * rootfs bash. Termux proot has no "--" separator — the command starts
     * at CONTAINER_ENTRY (the first argument not prefixed with "-").
     */
    fun smokeArgsFrom(args: Array<String>): List<String> {
      val cmdAt = args.indexOf(ProotRuntime.CONTAINER_ENTRY)
      require(cmdAt > 0) { "engine argv carries no " + ProotRuntime.CONTAINER_ENTRY + " entry" }
      return args.take(cmdAt).toMutableList().apply {
        add("/bin/bash")
        add("-c")
        add("echo CONTAINER_OK; id -u")
      }
    }
  }
}
