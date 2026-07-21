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
}

// Apply version management script (Kotlin DSL with Provider API)
apply(from = "gradle/version.gradle.kts")

// Phase 1 of microkernel-runtime extraction: aggregator that produces the
// upstream IPC JARs (boss-ipc, boss-ui-sdk, plugin-api-ipc, plugin-api-core)
// for the standalone runtime repo to consume. Skipped on Windows ARM64
// because boss-ipc is itself excluded from settings.gradle.kts there.
val rootSettingsOsArch: String = System.getProperty("os.arch").lowercase()
val rootSettingsOsName: String = System.getProperty("os.name").lowercase()
val isRootWindowsArm64 = rootSettingsOsName.contains("win") &&
    (rootSettingsOsArch == "aarch64" || rootSettingsOsArch == "arm")
if (!isRootWindowsArm64) {
    apply(from = "gradle/upstream-artifacts.gradle.kts")
}