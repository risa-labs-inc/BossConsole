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

                // Serialization
                implementation(libs.kotlinx.serialization.json)

                // Minimal plugin-api-core
                api(projects.pluginPlatform.pluginApiCore)

                // Logging
                implementation(projects.pluginPlatform.pluginLogging)

                // Path utilities (BossDirectories)
                implementation(projects.pluginPlatform.pluginPathUtils)

                // Ktor client for remote repositories
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)

                // Supabase Realtime for live updates
                implementation(libs.supabase.realtime)
            }
        }

        val desktopMain by getting {
            dependencies {
                // Ktor engine
                implementation(libs.ktor.client.cio)
                // Trust anchor + signature verifier (load-time verification lives in plugin-loader)
                implementation(projects.pluginPlatform.pluginLoader)
            }
        }

        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-junit5"))
                implementation(libs.junit.jupiter)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
