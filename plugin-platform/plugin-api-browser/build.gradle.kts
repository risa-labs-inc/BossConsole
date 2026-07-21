
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    alias(libs.plugins.mavenPublish)
}

group = "com.risaboss"
version = "1.0.5"

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
        val commonMain by getting {
            dependencies {
                // Standalone module - no dependency on plugin-api
                implementation(libs.compose.mp.runtime)
                implementation(libs.compose.mp.ui)
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

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    pom {
        name.set("BOSS Plugin Browser API")
        description.set("Browser service API for BOSS plugins needing embedded browser capabilities")
        url.set("https://github.com/risa-labs-inc/BossConsole")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("risa-labs")
                name.set("Risa Labs")
                email.set("dev@risaboss.com")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/risa-labs-inc/BossConsole.git")
            developerConnection.set("scm:git:ssh://github.com/risa-labs-inc/BossConsole.git")
            url.set("https://github.com/risa-labs-inc/BossConsole")
        }
    }
}
