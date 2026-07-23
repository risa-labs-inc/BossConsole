import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

group = "ai.rever.boss.plugin.ipc"
version = "1.0.0"

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
                // Plugin API (interfaces we're implementing proxies for)
                api(projects.pluginPlatform.pluginApiCore)

                // Coroutines
                api(libs.kotlinx.coroutines.core)
            }
        }

        named("desktopMain") {
            dependencies {
                // IPC protocol and connection management
                implementation(project(":boss-ipc"))

                implementation(compose.desktop.currentOs)
            }
        }

        named("desktopTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
