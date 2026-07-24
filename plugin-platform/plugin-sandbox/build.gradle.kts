import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
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
                // Compose dependencies
                implementation(libs.compose.mp.runtime)
                implementation(libs.compose.mp.ui)
                implementation(libs.compose.mp.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)

                // Decompose for ComponentContext
                implementation(libs.decompose)
                implementation(libs.essenty.lifecycle)

                // Coroutines
                implementation(libs.kotlinx.coroutines.core)

                // Minimal plugin-api-core
                api(projects.pluginPlatform.pluginApiCore)

                // Logging
                implementation(projects.pluginPlatform.pluginLogging)
            }
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
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
