import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
}

group = "ai.rever.boss.plugin"

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvmToolchain(17)

    jvm("desktop") {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                }
            }
        }
    }

    sourceSets {
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        named("desktopTest") {
            dependencies {
                implementation(kotlin("test-junit5"))
                implementation(libs.junit.jupiter)
            }
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()

    // Point the test JVM's home at a build directory, so BossDirectories.rootDir resolves to
    // <build>/test-home/<task>/.boss instead of the developer's real ~/.boss.
    //
    // This module's own suite is the load-bearing case: rootDir is a `by lazy` process-global
    // that CREATES the directory on first access, and BossDirectoriesTest touches it on every
    // run. Without the redirect a test run on a machine that has never launched the app leaves
    // a real ~/.boss behind, and the suite's assertions bind to the developer's own home - the
    // failure issue #818 tracked for the desktop suites. AGENTS.md keeps this guarantee
    // deliberately module-local, so the module that owns BossDirectories carries the same
    // redirect composeApp already documents.
    val testHome =
        layout.buildDirectory
            .dir("test-home/$name")
            .get()
            .asFile
    systemProperty("user.home", testHome.absolutePath)
    // Deleted, not just created: a stale home from an earlier run would carry that run's .boss
    // into this one, so the home is fresh per run rather than merely private.
    // BossDirectoriesHomeIsolationTest pins both halves of the contract.
    doFirst {
        testHome.deleteRecursively()
        testHome.mkdirs()
    }
}
