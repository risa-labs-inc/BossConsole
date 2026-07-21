import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    alias(libs.plugins.kotlinSerialization)
}

group = "ai.rever.boss.plugin"

kotlin {
    // Suppress expect/actual classes beta warning (KT-61573)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvmToolchain(17)

    // Desktop JVM target
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
        val commonMain by getting {
            dependencies {
                // Coroutines
                implementation(libs.kotlinx.coroutines.core)

                // Serialization for manifest parsing
                implementation(libs.kotlinx.serialization.json)

                // Minimal plugin-api-core (PluginContext, DynamicPlugin, PluginManifest)
                api(projects.pluginPlatform.pluginApiCore)

                // Logging
                implementation(projects.pluginPlatform.pluginLogging)
            }
        }

        val desktopMain by getting {
            dependencies {
                // Desktop-specific implementations
            }
        }

        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-junit5"))
                implementation("org.junit.jupiter:junit-jupiter:6.1.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            }
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
