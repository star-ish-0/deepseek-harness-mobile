package com.dshmobile.shell

import java.io.File

/**
 * Container smoke test: runs a real command inside the single rootfs via the
 * exact proot argv the engine uses, so a failure here means the container is
 * genuinely unusable — proot binary, its shared libs, the rootfs node AND the
 * hardlink emulation are all exercised. Runs outside the engine process
 * (fresh ProcessBuilder).
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
      // Both markers required: CONTAINER_OK proves bash runs inside the jail;
      // LINK2SYMLINK_OK proves link(2) is emulated (AOSP sepolicy denies real
      // hardlinks for every app — dsh's atomic session commits depend on it).
      if (out.contains("CONTAINER_OK") && out.contains("LINK2SYMLINK_OK")) null else out.trim().take(600)
    } catch (t: Throwable) {
      (t.message ?: t.javaClass.simpleName).take(600)
    }

  companion object {
    /**
     * Splice a bounded smoke command into an engine argv: keep the proot
     * prefix (options + bindings, including --link2symlink) and replace the
     * jailed command with rootfs bash. Termux proot has no "--" separator —
     * the command starts at CONTAINER_ENTRY (the first argument not
     * prefixed with "-").
     *
     * Every external command must be spelled with its ABSOLUTE rootfs path:
     * unlike the engine argv (which rebuilds the environment via
     * `env -i PATH=...`), the probe's bash inherits the HOST environment,
     * whose PATH lists Android dirs (/system/bin...) that do not exist
     * inside the jail — device-reproduced on build-15: `id`, `touch`, `ln`
     * and `rm` all resolved to "command not found" while `echo` (a bash
     * builtin) worked, so the LINK2SYMLINK_OK gate never fired and container
     * init failed although the jail itself was healthy.
     *
     * The hardlink part reproduces dsh's actual session-commit pattern:
     * write tmp file → link() tmp onto the final name → unlink() the tmp →
     * read the final name back. Without --link2symlink the ln step dies with
     * EACCES (AOSP sepolicy grants apps no file:link on app_data_file),
     * which is exactly what broke every chat turn on device.
     */
    fun smokeArgsFrom(args: Array<String>): List<String> {
      val cmdAt = args.indexOf(ProotRuntime.CONTAINER_ENTRY)
      require(cmdAt > 0) { "engine argv carries no " + ProotRuntime.CONTAINER_ENTRY + " entry" }
      return args.take(cmdAt).toMutableList().apply {
        add("/bin/bash")
        add("-c")
        add(
          "echo CONTAINER_OK; /usr/bin/id -u; cd /root && printf dsh > l2s-probe.tmp && " +
            "/usr/bin/ln l2s-probe.tmp l2s-probe.fin && /usr/bin/rm l2s-probe.tmp && " +
            "/usr/bin/grep -q dsh l2s-probe.fin && echo LINK2SYMLINK_OK; " +
            "/usr/bin/rm -f l2s-probe.tmp l2s-probe.fin",
        )
      }
    }
  }
}
