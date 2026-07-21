import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
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
            // SemanticVersion is pure Kotlin — no external dependencies. (The
            // coroutines/serialization/plugin-api-core/logging deps went with
            // the removed PluginDependencyResolver.)
            dependencies {
            }
        }

        val desktopMain by getting {
            dependencies {
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
