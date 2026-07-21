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
                compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":plugin-platform:plugin-logging"))
                implementation(libs.compose.mp.runtime)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
}
