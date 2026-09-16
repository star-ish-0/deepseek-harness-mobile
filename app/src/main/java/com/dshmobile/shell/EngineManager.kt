package com.dshmobile.shell

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * Owns the single embedded Debian glibc rootfs: first-launch extraction into
 * filesDir/rootfs and the dsh engine process lifecycle. The engine (node +
 * dsh) runs INSIDE the rootfs via proot — the agent shell and the engine
 * share one container, no wrappers, no exec hooks.
 */
class EngineManager(
  private val context: Context,
) {
  val rootfsDir = File(context.filesDir, DshPaths.ROOTFS_DIR)
  val homeDir = File(context.filesDir, "home")

  /**
   * Shared persistent directory: /storage/emulated/0/Documents/dshdata.
   * User data (settings, plugin configs, session history, attachments) lands
   * here by default so it is visible to file managers, can be backed up, and
   * survives app uninstall/reinstall.
   */
  val dshDataDir: File
    get() {
      val publicDocs =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
          ?: File(context.filesDir, "dshdata-fallback")
      return File(publicDocs, "dshdata")
    }
  private val prootRuntime by lazy { ProotRuntime(context) }

  /** Engine entry binary inside the rootfs. */
  private val dshEntry = File(rootfsDir, DshPaths.DSH_ENTRY)

  /** True once the rootfs is extracted and holds the dsh entry. */
  val engineReady: Boolean get() = dshEntry.isFile

  /**
   * Extract the bundled rootfs archive into filesDir/rootfs. Runs on any
   * thread; callers own the progress UI.
   * @param onProgress bytesDone, bytesTotal.
   * @returns true on success.
   */
  fun extractRootfs(onProgress: (Long, Long) -> Unit): Boolean =
    try {
      // The archive IS the rootfs: its entries are ./etc, ./usr, ./root/...
      // (no rootfs/ prefix — exactly the layout UpdateManager stages and
      // swaps in as filesDir/rootfs on the online-update path). Extracting it
      // straight into filesDir scattered the tree across filesDir while
      // rootfsDir stayed empty: engineReady never turned true (boot
      // re-extracted on every launch) and proot's -r pointed at an empty
      // jail. Device-reproduced on the rc.6 snapshot first boot.
      rootfsDir.mkdirs()
      context.assets.openFd(DshPaths.ROOTFS_ASSET).use { fd ->
        AppLog.log("extract", "archive size=" + fd.length + " bytes, dest=" + rootfsDir.absolutePath)
        SnapshotExtractor.extract(
          context.assets.open(DshPaths.ROOTFS_ASSET),
          fd.length,
          rootfsDir,
          onProgress,
        )
      }
      homeDir.mkdirs()
      AppLog.log("extract", "done, engineReady=" + engineReady)
      true
    } catch (t: Throwable) {
      Log.e(TAG, "rootfs extract failed", t)
      AppLog.log("extract", "FAILED", t)
      false
    }

  /**
   * Ensure the shared persistent directory is wired up (idempotent; call from
   * a background thread).
   *
   * Design (issue apk#8): DSH_HOME itself MUST stay in the private domain —
   * dsh maintains a flat-module fallback under `$DSH_HOME/profiles/node_modules`
   * on every start (one symlink per dependency pointing at the engine install),
   * and the public storage (/storage/emulated/0) FUSE layer forbids symlink
   * creation (observed Permission denied), so a wholesale migration would
   * break the engine.
   *
   * Instead we do item-level migration: move user data to Documents/dshdata
   * and place a symlink at the original private location (symlinks work in the
   * app-private domain, verified on device), so dsh reads/writes land on the
   * public directory through the symlink:
   *  - settings.yaml: copied to public (the settings-file config.path in
   *    cordis.patch.yml points straight at the public file, avoiding the
   *    atomic-rewrite-replaces-symlink problem)
   *  - sessions/, storages/, attachments/: moved wholesale + private symlink
   *    (writing files inside a directory does not replace the directory symlink)
   *  - profiles/{web,headless}/cordis.yml + cordis.patch.yml: copied to public
   *    + private replaced with a symlink (dsh only reads these two files)
   *  - .credentials.yaml (API key): NOT migrated — the public FUSE forces mode
   *    660, which the credentials-local permission check rejects, and the key
   *    would be exposed to other apps; it stays as the private entity, pointed
   *    to by the credentials path in cordis.patch.yml.
   * After migration the private locations hold only symlinks/kept entities;
   * the public copies are never deleted.
   */
  fun ensureDshDataHome(): File {
    val dshData = dshDataDir
    val privateDsh = File(rootfsDir, DshPaths.CONTAINER_DSH_HOME)
    // The /root/projects bind source must exist BEFORE buildEngineArgs runs:
    // proot canonicalizes bindings at startup and a missing source emits
    // "can't sanitize binding ... No such file or directory" — the workspace
    // then lands inside the rootfs instead of the host-backed dshdata
    // (device-reproduced when the container probe ran before any start).
    // Every caller of this method derives the projects dir from its result.
    File(privateDsh, DshPaths.PROJECTS_DIR).mkdirs()
    // Android < 11 has no All Files Access model and the public Documents
    // directory is unwritable (scoped storage); migration is impossible, so
    // keep DSH_HOME fully private. Observed on Android 10 (Huawei): the
    // migration used to fail with FileNotFoundException on every start.
    if (android.os.Build.VERSION.SDK_INT < 30) {
      AppLog.log("migrate", "skipped: Android < 11 (no All Files Access), public dshdata unwritable")
      return privateDsh
    }
    val marker = File(dshData, ".migrated-from")
    if (privateDsh.isDirectory) {
      if (marker.exists()) {
        // Re-link (I-10): uninstall wipes the private symlinks but the public
        // data and marker persist. Idempotently rebuild the private links so
        // the data becomes visible again; missing public targets are skipped
        // and already-correct links cost nothing.
        relink(File(privateDsh, "sessions"), File(dshData, "sessions"))
        relink(File(privateDsh, "storages"), File(dshData, "storages"))
        relink(File(privateDsh, "attachments"), File(dshData, "attachments"))
        for (profile in listOf("web", "headless")) {
          for (name in listOf("cordis.yml", "cordis.patch.yml")) {
            relinkFile(File(privateDsh, "profiles/$profile/$name"), File(dshData, "profiles/$profile/$name"))
          }
        }
      } else {
        try {
          dshData.mkdirs()
          // 1) settings.yaml: public entity + plugin config.path points at it (see patch)
          copyFileIfExists(File(privateDsh, "settings.yaml"), File(dshData, "settings.yaml"))
          // 2) directory-level data: move wholesale + private symlink
          relocateDir(File(privateDsh, "sessions"), File(dshData, "sessions"))
          relocateDir(File(privateDsh, "storages"), File(dshData, "storages"))
          relocateDir(File(privateDsh, "attachments"), File(dshData, "attachments"))
          // 3) plugin configs: copy to public + replace private with a symlink (dsh only reads)
          for (profile in listOf("web", "headless")) {
            for (name in listOf("cordis.yml", "cordis.patch.yml")) {
              val sf = File(privateDsh, "profiles/$profile/$name")
              if (sf.exists() && sf.isFile) {
                val pf = File(dshData, "profiles/$profile/$name")
                pf.parentFile?.mkdirs()
                sf.copyTo(pf, overwrite = true)
                sf.delete()
                try {
                  java.nio.file.Files
                    .createSymbolicLink(sf.toPath(), pf.toPath())
                } catch (t: Throwable) {
                  // Symlink failed (edge case): keep the private entity, discard the public copy.
                  pf.delete()
                  Log.w(TAG, "symlink failed for " + sf.absolutePath + "; keeping private copy")
                }
              }
            }
          }
          marker.writeText(privateDsh.absolutePath)
          Log.i(TAG, "dshdata migration done -> " + dshData.absolutePath)
        } catch (t: Throwable) {
          // A failed migration must not block startup: DSH_HOME stays private,
          // the engine still works, and the migration retries next time.
          Log.e(TAG, "dshdata migration failed", t)
          AppLog.log("migrate", "dshdata migration FAILED", t)
        }
      }
    }
    return privateDsh
  }

  /**
   * Re-link (I-10): when the public target exists, ensure the private item is
   * a symlink pointing at it. Already-correct symlink → no-op; private real
   * empty directory (fresh shell created by dsh after reinstall) → replaced
   * with a symlink; private non-empty directory (may hold new data) →
   * conservatively skipped.
   */
  private fun relink(
    src: File,
    dst: File,
  ) {
    if (!dst.exists()) return
    val srcPath = src.toPath()
    if (java.nio.file.Files
        .isSymbolicLink(srcPath)
    ) {
      if (src.canonicalPath == dst.canonicalPath) return
      src.delete()
    } else if (src.exists()) {
      val children = src.listFiles()
      if (children != null && children.isEmpty()) {
        src.delete()
      } else {
        Log.w(TAG, "relink skipped (non-empty): " + src.absolutePath)
        return
      }
    }
    src.parentFile?.mkdirs()
    try {
      java.nio.file.Files
        .createSymbolicLink(srcPath, dst.toPath())
    } catch (t: Throwable) {
      Log.w(TAG, "relink failed for " + src.absolutePath, t)
    }
  }

  /** Re-link (I-10), file variant: when the public target exists, replace the
   *  private file with a symlink pointing at it. */
  private fun relinkFile(
    src: File,
    dst: File,
  ) {
    if (!dst.isFile) return
    val srcPath = src.toPath()
    if (java.nio.file.Files
        .isSymbolicLink(srcPath)
    ) {
      if (src.canonicalPath == dst.canonicalPath) return
      src.delete()
    } else if (src.exists()) {
      src.delete()
    }
    src.parentFile?.mkdirs()
    try {
      java.nio.file.Files
        .createSymbolicLink(srcPath, dst.toPath())
    } catch (t: Throwable) {
      Log.w(TAG, "relinkFile failed for " + src.absolutePath, t)
    }
  }

  /** Copy a single file when it exists. */
  private fun copyFileIfExists(
    src: File,
    dst: File,
  ) {
    if (src.isFile) {
      dst.parentFile?.mkdirs()
      src.copyTo(dst, overwrite = true)
    }
  }

  /** Move a directory wholesale to public (copy+delete-source when a cross-
   *  mount rename fails), then leave a symlink at the original location. */
  private fun relocateDir(
    src: File,
    dst: File,
  ) {
    if (!src.isDirectory || dst.exists()) return
    dst.parentFile?.mkdirs()
    if (!src.renameTo(dst)) {
      copyTree(src, dst)
      src.deleteRecursively()
    }
    try {
      java.nio.file.Files
        .createSymbolicLink(src.toPath(), dst.toPath())
    } catch (t: Throwable) {
      Log.w(TAG, "symlink failed for dir " + src.absolutePath)
    }
  }

  /** Recursively copy a directory tree (real file contents). */
  private fun copyTree(
    src: File,
    dst: File,
  ) {
    src.listFiles()?.forEach { f ->
      val target = File(dst, f.name)
      if (f.isDirectory) {
        target.mkdirs()
        copyTree(f, target)
      } else {
        f.copyTo(target, overwrite = true)
      }
    }
  }

  /** Start the dsh web engine inside the single runtime rootfs. */
  fun startEngine(port: Int = 3080): Boolean {
    val now = System.currentTimeMillis()
    // Process-level CAS: only one concurrent caller actually starts the engine
    // (device-observed EADDRINUSE on double start). The losing caller returns
    // immediately — even the proot check is skipped for it (single-flight).
    if (!STARTING.compareAndSet(false, true)) return true
    if (!prootRuntime.ensureProot()) {
      AppLog.log("engine", "start refused: proot runtime unavailable")
      STARTING.set(false)
      return false
    }
    // I-11: when the engine process is dead (or was never started) there is no
    // double-start race — clear the cooldown immediately, otherwise the 5s
    // watchdog polls would keep hitting the 90s cooldown window and crash
    // recovery would take up to 90s.
    if (engineProcess?.isAlive != true) EngineManager.lastStartAttemptAt = 0
    // Cooldown window: no new start within this window of the last attempt
    // (cold node boot takes 20-45s).
    if (now - EngineManager.lastStartAttemptAt < START_COOLDOWN_MS) {
      STARTING.set(false)
      return true
    }
    // Cooldown expired but the process is still alive: it is hung (a healthy
    // boot always answers within the 90s window) and holds the port — kill it
    // or every subsequent start dies with EADDRINUSE and the watchdog loops
    // forever restarting a corpse.
    EngineManager.engineProcess?.let { p ->
      if (p.isAlive) {
        AppLog.log("engine", "previous engine process alive past cooldown, killing hung process")
        p.destroyForcibly()
        try {
          p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
      }
    }
    EngineManager.engineProcess = null
    return try {
      val projectsDir = File(ensureDshDataHome(), DshPaths.PROJECTS_DIR).apply { mkdirs() }
      val (args, env) = prootRuntime.buildEngineArgs(rootfsDir, projectsDir, port, pickToken)
      engineProcess = startWithArgs(args, env)
      // The cooldown is only set after a real start; a failed path does not
      // consume the window so a retry can happen immediately.
      EngineManager.lastStartAttemptAt = now
      AppLog.log(
        "engine",
        "started port=" + port +
          " proot=" + prootRuntime.prootBin.absolutePath +
          " rootfs=" + rootfsDir.absolutePath + " arch=" +
          android.os.Build.SUPPORTED_ABIS
            .joinToString(","),
      )
      true
    } catch (t: Throwable) {
      Log.e(TAG, "engine start failed", t)
      AppLog.log("engine", "start FAILED", t)
      AppLog.includeFile(File(context.filesDir, DshPaths.ENGINE_LOG), DshPaths.ENGINE_LOG)
      false
    } finally {
      STARTING.set(false)
    }
  }

  /**
   * Spawn the engine, falling back to the system linker when the direct exec
   * is denied. proot is NDK/bionic-linked, so /system/bin/linker64 can load
   * it; the rootfs's glibc binaries are never exec'd from app data (they run
   * under proot inside the container).
   */
  private fun startWithArgs(
    args: Array<String>,
    env: Map<String, String>,
  ): Process {
    val log = File(context.filesDir, "engine.log")

    fun build(argv: List<String>): ProcessBuilder =
      ProcessBuilder(argv).also { b ->
        b.environment().putAll(env)
        b.redirectErrorStream(true)
        b.redirectOutput(log)
      }
    return try {
      build(args.toList()).start()
    } catch (e: java.io.IOException) {
      if (e.message?.contains("Permission denied") != true) throw e
      Log.w(TAG, "direct exec denied, falling back to linker64: " + e.message)
      AppLog.log("engine", "direct exec denied (" + e.message + "), falling back to linker64")
      build(listOf("/system/bin/linker64") + args.toList()).start()
    }
  }

  /** Stop the engine process (best-effort). Guarded by the start CAS so a
   *  concurrent start cannot race a destroy-then-null. */
  fun stopEngine() {
    if (!STARTING.compareAndSet(false, true)) {
      AppLog.log("engine", "stop skipped: engine start in progress")
      return
    }
    try {
      EngineManager.engineProcess?.let { p ->
        p.destroy()
        try {
          p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
      }
      EngineManager.engineProcess = null
      // Reset the cooldown after a manual stop: the user returning to the
      // foreground should be allowed to restart immediately.
      EngineManager.lastStartAttemptAt = 0
    } finally {
      STARTING.set(false)
    }
  }

  companion object {
    private const val TAG = "dsh-engine"

    /**
     * Process-wide auth token for the directory-pick bridge. Single source of
     * truth: MainActivity's WebView bridge and every EngineManager instance
     * (including the EngineService watchdog's) read the same value, so an
     * engine restart never leaves the bridge token mismatched. Random per
     * process start, exactly as before — just shared.
     */
    val pickToken: String =
      java.util.UUID
        .randomUUID()
        .toString()

    /** Watchdog/retry backoff: no new start within this window of the last
     *  attempt. Cold node boot on the phone takes 20-45s (plugin tree + first
     *  bind); a 5s watchdog poll would otherwise race a healthy boot and
     *  double-start the engine (device-observed EADDRINUSE). 90s covers the
     *  slowest observed boot with margin. */
    const val START_COOLDOWN_MS = 90_000L

    /** Process-level start CAS: visible across EngineManager instances
     *  (double-start race protection). */
    val STARTING =
      java.util.concurrent.atomic
        .AtomicBoolean(false)

    /** Epoch ms of the last real start; baseline for the watchdog cooldown. */
    @Volatile
    var lastStartAttemptAt: Long = 0

    /** Engine process, shared at the process level (I-11): MainActivity and
     *  EngineService hold separate EngineManager instances whose instance
     *  fields are invisible to each other — like STARTING, this lives on the
     *  companion so both instances can see and manage the same process. */
    @Volatile
    var engineProcess: Process? = null
  }
}
