import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("jacoco")
}

android {
  namespace = "com.dshmobile.shell"
  compileSdk = 36
  ndkVersion = "27.2.12479018"

  defaultConfig {
    applicationId = "com.dshmobile.shell"
    minSdk = 26
    // targetSdk 28 = the ONLY domain that can exec app-data ELFs.
    //
    // AOSP sepolicy (private/seapp_contexts + untrusted_app_27.te): the app's
    // targetSdkVersion picks its SELinux domain —
    //   targetSdk >= 29 → untrusted_app (29/30/32 variants): NO execute on
    //     app_data_file → every exec() under filesDir dies with
    //     "error=13, Permission denied" on Android 10+ (reproduced on a
    //     Samsung SM-A075M / Android 15: container init FAILED at proot exec).
    //   targetSdk <= 28 → untrusted_app_27:
    //     allow untrusted_app_27 app_data_file:file execute_no_trans;
    //     → proot, its ptrace children AND every rootfs binary can exec.
    // This is the same reason Termux still ships targetSdk 28: it is the only
    // no-Shizuku way to run a proot container from app data on Android 10+.
    // (The /system/bin/linker64 fallback in EngineManager.startWithArgs only
    // saves proot's OWN exec — the rootfs children would still be denied —
    // so it stays as a safety net, not the fix.)
    // Android 15 still installs targetSdk >= 24 and keeps the untrusted_app_27
    // domain alive for it; if a future release ever raises the install floor
    // above 28, the runtime must move to jniLibs/Shizuku.
    // Huawei/EMUI W^X (executables must not be writable) is handled by the
    // write-bit strip in SnapshotExtractor/ProotRuntime.
    targetSdk = 28
    // Version comes from the release tag in CI (-PversionName/-PversionCode,
    // e.g. v0.1.0 -> 0.1.0 / 100); local builds keep the defaults.
    versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
    versionName = (project.findProperty("versionName") as String?) ?: "0.1.0"
    // Single runtime: arm64-v8a only. CI passes -PabiFilter=arm64-v8a;
    // local builds are locked to arm64-v8a as well (x86_64 was dropped with
    // the Termux snapshot runtime).
    ndk { abiFilters += "arm64-v8a" }
  }

  androidResources {
    // rootfs.tar.xz is already xz-compressed; double-compressing it breaks openFd.
    noCompress += "xz"
  }

  // CI signing: the release workflow drops a release.keystore at the project
  // root and signs the release variant with it; local builds without the
  // keystore keep producing the unsigned APK as before.
  if (rootProject.file("release.keystore").exists()) {
    signingConfigs.create("ci") {
      storeFile = rootProject.file("release.keystore")
      storePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: "android"
      keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "release"
      keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: "android"
      // AGP 9 disables v1 signing by default for minSdk >= 24; some OEM
      // installers (Huawei/EMUI) reject v2-only APKs with "no certificate",
      // so force v1 on for sideload compatibility.
      enableV1Signing = true
      enableV2Signing = true
      enableV3Signing = true
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      if (rootProject.file("release.keystore").exists()) {
        signingConfig = signingConfigs.getByName("ci")
      }
    }
  }

  lint {
    // The CI quality gate runs `lintDebug` and fails on errors (abortOnError).
    // checkReleaseBuilds stays off: release builds are not blocked by lint —
    // the gate is explicit, not implicit on the release path.
    checkReleaseBuilds = false
    abortOnError = true
  }

  testOptions {
    unitTests {
      // Robolectric needs real Android resources/asset access.
      isIncludeAndroidResources = true
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

// Kotlin 2.4 removes the kotlinOptions DSL; use the compilerOptions API instead.
kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
  }
}

// Unit-test coverage report (CI uploads it; not a hard gate).
tasks.register<JacocoReport>("jacocoTestReport") {
  dependsOn("testDebugUnitTest")
  reports {
    xml.required.set(true)
    html.required.set(true)
  }
  sourceDirectories.setFrom(files("src/main/java"))
  classDirectories.setFrom(fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")))
  executionData.setFrom(
    fileTree(layout.buildDirectory.dir("outputs/unit_test_code_coverage")) { include("**/*.exec") },
  )
}

// The single-runtime rootfs ships from dsh-io/dsh-arm64 releases (the large
// file is not in the repo); fail the build with fetch instructions when it
// is missing.
tasks.whenTaskAdded {
  if (name == "mergeDebugAssets" || name == "mergeReleaseAssets") {
    doFirst {
      val rootfs = file("src/main/assets/rootfs.tar.xz")
      if (!rootfs.exists()) {
        throw GradleException(
          "缺少单运行时 rootfs assets/rootfs.tar.xz / Missing single-runtime rootfs " +
            "assets/rootfs.tar.xz — 从 dsh-io/dsh-arm64 Releases 下载 " +
            "dsh-arm64-rootfs-<version>.tar.xz 放到 app/src/main/assets/rootfs.tar.xz " +
            "（见 README.md）。/ Download dsh-arm64-rootfs-<version>.tar.xz from " +
            "dsh-io/dsh-arm64 releases into app/src/main/assets/rootfs.tar.xz (see README.md).",
        )
      }
    }
  }
}

dependencies {
  implementation("androidx.activity:activity-ktx:1.13.0")
  implementation("org.apache.commons:commons-compress:1.28.0")
  implementation("org.tukaani:xz:1.12")
  implementation("dev.rikka.shizuku:api:13.1.5")
  implementation("dev.rikka.shizuku:provider:13.1.5")

  testImplementation("junit:junit:4.13.2")
  testImplementation("org.robolectric:robolectric:4.14.1")
  testImplementation("io.mockk:mockk:1.13.13")
}
