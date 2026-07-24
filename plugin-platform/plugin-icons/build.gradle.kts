import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
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
        commonMain {
            dependencies {
                // Path utilities (shared with bosseditor)
                implementation(projects.pluginPlatform.pluginPathUtils)

                // UI core for colors (BossDarkTextSecondary)
                implementation(projects.pluginPlatform.pluginUiCore)

                // Compose dependencies
                implementation(libs.compose.mp.runtime)
                implementation(libs.compose.mp.ui)
                implementation(libs.compose.mp.foundation)
                implementation(libs.compose.mp.material)
                implementation(compose.materialIconsExtended)

                // Simple Icons for brand icons (Kotlin, Python, etc.)
                implementation(libs.compose.icons.simpleicons)
            }
        }

        named("desktopMain") {
            dependencies {
                implementation(compose.desktop.currentOs)
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

// Configure Test tasks
tasks.withType<Test> {
    useJUnitPlatform()
}
