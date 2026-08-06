
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    alias(libs.plugins.mavenPublish)
}

group = "com.risaboss"
version = "1.0.8"

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
                implementation(libs.compose.mp.runtime)
                implementation(libs.compose.mp.ui)
                implementation(libs.compose.mp.foundation)
                implementation(libs.compose.mp.material)
                implementation(compose.materialIconsExtended)
            }
        }

        named("desktopMain") {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }

        named("desktopTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-junit5"))
                implementation(libs.junit.jupiter)
                // Compose's UI test API is JUnit 4 only, and this module runs on the JUnit
                // Platform, so the vintage engine is what actually executes it - same pairing
                // composeApp uses.
                implementation(compose.desktop.uiTestJUnit4)
                runtimeOnly(libs.junit.vintage.engine)
            }
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    pom {
        name.set("BOSS Plugin UI Core")
        description.set("Core UI components for BOSS desktop application plugins")
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
                email.set("enterprise@risalabs.ai")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/risa-labs-inc/BossConsole.git")
            developerConnection.set("scm:git:ssh://github.com/risa-labs-inc/BossConsole.git")
            url.set("https://github.com/risa-labs-inc/BossConsole")
        }
    }
}
