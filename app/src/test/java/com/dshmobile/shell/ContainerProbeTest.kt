package com.dshmobile.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Termux-proot argv compatibility: the container command must start at the
 * first argument not prefixed with "-", because proot's own parser has NO
 * "--" separator — it aborts the whole start with `unknown option '--'`
 * (termux/proot src/cli/cli.c, device-reproduced with 5.1.107 on
 * SM-A075M/Android 15: "proot error: unknown option '--'. fatal error").
 */
class ContainerProbeTest {
  @Test
  fun `smoke argv keeps the proot prefix and swaps the command for bash`() {
    val engineArgs =
      arrayOf(
        "/path/to/proot",
        "-0",
        "-r",
        "/path/to/rootfs",
        "-b",
        "/dev:/dev",
        "-w",
        "/root",
        "--kill-on-exit",
        "/usr/bin/env",
        "-i",
        "HOME=/root",
        "node",
        "bin.js",
        "web",
        "--port",
        "3080",
      )
    val smoke = ContainerProbe.smokeArgsFrom(engineArgs)
    // Proot prefix intact up to (and excluding) the container entry.
    assertEquals(engineArgs.take(9), smoke.take(9))
    // Bounded smoke command spliced in: bash + container/hardlink markers.
    // External commands must use absolute rootfs paths — the probe's bash
    // inherits the HOST environment (Android PATH), so bare names resolve
    // to "command not found" inside the jail (device-reproduced build-15).
    assertEquals("/bin/bash", smoke[9])
    assertEquals("-c", smoke[10])
    assertEquals(
      "echo CONTAINER_OK; /usr/bin/id -u; cd /root && printf dsh > l2s-probe.tmp && " +
        "/usr/bin/ln l2s-probe.tmp l2s-probe.fin && /usr/bin/rm l2s-probe.tmp && " +
        "/usr/bin/grep -q dsh l2s-probe.fin && echo LINK2SYMLINK_OK; " +
        "/usr/bin/rm -f l2s-probe.tmp l2s-probe.fin",
      smoke[11],
    )
    assertEquals(12, smoke.size)
  }

  @Test
  fun `smoke argv never carries a double-dash separator`() {
    val engineArgs =
      arrayOf("proot", "-r", "/rootfs", "--kill-on-exit", "--link2symlink", "/usr/bin/env", "-i", "HOME=/root")
    val smoke = ContainerProbe.smokeArgsFrom(engineArgs)
    assertFalse(smoke.contains("--"))
    assertFalse(smoke.contains("/usr/bin/env"))
    assertTrue(
      smoke.first() == "proot" &&
        smoke.last().endsWith("/usr/bin/rm -f l2s-probe.tmp l2s-probe.fin"),
    )
  }

  @Test(expected = IllegalArgumentException::class)
  fun `argv without a container entry is rejected loudly`() {
    ContainerProbe.smokeArgsFrom(arrayOf("proot", "-r", "/rootfs"))
  }
}
