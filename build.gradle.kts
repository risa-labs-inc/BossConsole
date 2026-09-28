import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask
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
//
// -PskipLint turns both gates off for a build that is not the gate. CI uses it
// in the per-OS build jobs, which only need to prove the code compiles and the
// tests pass; the dedicated code-quality job stays authoritative for lint.
// Excluding the tasks on the command line is not equivalent: `-x detekt` works,
// but ktlint wires a ktlint<SourceSet>Check task into `check` for every source
// set in every module, so `-x ktlintCheck` drops only the umbrella and leaves
// all of them scheduled.
// ---------------------------------------------------------------------------
val skipLint = providers.gradleProperty("skipLint").isPresent

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

    tasks.withType<Detekt>().configureEach {
        jvmTarget = "17"
    }
    tasks.withType<DetektCreateBaselineTask>().configureEach {
        jvmTarget = "17"
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

    if (skipLint) {
        tasks
            .matching {
                it.name.contains("ktlint", ignoreCase = true) ||
                    it.name.contains("detekt", ignoreCase = true)
            }.configureEach { enabled = false }
    }

    // Fail a test run on a test JUnit will not execute, rather than let it pass by not running. A
    // @Test method that returns a value is the common case: `fun x() = runBlocking { ... }` returns
    // whatever its last expression does, JUnit reports that as a WARNING-level discovery issue,
    // skips the method, and the build stays green. Nine composeApp tests sat unexecuted that way,
    // and one of them no longer described the code (#1667). Declare such a test `(): Unit =`.
    //
    // What a failure looks like: a DiscoveryIssueException aborts discovery for the whole engine,
    // so every test in the module vanishes and one initializationError is reported in their place.
    // Its message names the method. If a toolchain bump (JUnit, Compose, the vintage engine)
    // introduces an unrelated WARNING, relax it to "ERROR" for that module while it is fixed, rather
    // than deleting this line: set the same property in that module's own build.gradle.kts, whose
    // Test configuration runs after this one and wins. composeApp also carries the setting in
    // src/desktopTest/resources/junit-platform.properties, so an IDE run meets it too, and buildSrc,
    // a separate build this block never reaches, carries its own copy.
    tasks.withType<Test>().configureEach {
        systemProperty("junit.platform.discovery.issue.severity.critical", "WARNING")
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
