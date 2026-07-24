import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.mavenPublish) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
}

// ---------------------------------------------------------------------------
// Quality gates
//
// detekt: static analysis on every module, wired into `check` (and therefore
// `build` and CI). Existing debt is frozen in per-module detekt-baseline.xml
// files — only NEW violations fail the build. Regenerate a module's baseline
// with `./gradlew :<module>:detektBaseline` (root: `./gradlew detektBaseline`).
//
// ktlint: formatting gate (`ktlintCheck`, wired into `check`; `ktlintFormat`
// to fix). The format-the-world PR normalized the whole tree, so any failure
// is a new violation — run `./gradlew ktlintFormat` and recommit.
// ---------------------------------------------------------------------------
allprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
        baseline = file("detekt-baseline.xml")
        // The default task only scans src/main + src/test; this build mixes KMP
        // (src/commonMain, src/desktopMain, …) and plain JVM modules, so point
        // detekt at the whole src tree — it only picks up .kt/.kts files.
        source.setFrom(files("src"))
        parallel = true
    }

    extensions.configure<KtlintExtension> {
        // Pin the ktlint engine to the exact version the format-the-world
        // reformat was produced with (ktlint-gradle 14.2.0 bundles 1.5.0 by
        // default; rule behavior differs across engine versions).
        version.set("1.8.0")
        // The format-the-world PR normalized every module; ktlintCheck is part
        // of `check` and NEW formatting violations fail the build.
        ignoreFailures.set(false)
    }
}

// Apply version management script (Kotlin DSL with Provider API)
apply(from = "gradle/version.gradle.kts")

// Phase 1 of microkernel-runtime extraction: aggregator that produces the
// upstream IPC JARs (boss-ipc, boss-ui-sdk, plugin-api-ipc, plugin-api-core)
// for the standalone runtime repo to consume. Skipped on Windows ARM64
// because boss-ipc is itself excluded from settings.gradle.kts there.
val rootSettingsOsArch: String = System.getProperty("os.arch").lowercase()
val rootSettingsOsName: String = System.getProperty("os.name").lowercase()
val isRootWindowsArm64 =
    rootSettingsOsName.contains("win") &&
        (rootSettingsOsArch == "aarch64" || rootSettingsOsArch == "arm")
if (!isRootWindowsArm64) {
    apply(from = "gradle/upstream-artifacts.gradle.kts")
}
