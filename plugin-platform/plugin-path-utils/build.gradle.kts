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
        val commonMain by getting

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val desktopMain by getting

        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test-junit5"))
                implementation(libs.junit.jupiter)
            }
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
