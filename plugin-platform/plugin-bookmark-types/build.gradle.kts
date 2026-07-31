
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    alias(libs.plugins.kotlinSerialization)
    // Compose compiler with no Compose code, on purpose — same reason as plugin-logging.
    //
    // boss-plugin-api ships this same package and IS a Compose project, so its copies of these
    // types carry the synthetic `$stable` field. This module's copies shadow them parent-first in
    // plugin classloaders, so a plugin compiled against the api and holding one of these as a
    // property emits `getstatic <Type>.$stable` — which links at build time and is missing at
    // runtime. BinaryCompatibilityValidator then rejects the ENTIRE plugin as binary incompatible.
    //
    // That is exactly what took down secret-manager 1.2.6 and 1.2.7 via ComponentLogger. These
    // types are worse exposed: plugins hold Bookmark/TabConfig/PanelConfig-shaped data routinely.
    // Found by diffing every api package the host also bundles — 15 classes across three modules.
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.mavenPublish)
}

group = "com.risaboss"
version = "1.0.5"

kotlin {
    jvmToolchain(17)

    jvm("desktop") {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
            }
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlinx.serialization.json)
                implementation(projects.pluginPlatform.pluginWorkspaceTypes)
                // Compose runtime for @Immutable — and now also required by the Compose compiler
                // plugin applied above, which the compiler refuses to run without.
                implementation(libs.compose.mp.runtime)
            }
        }

        named("desktopTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    pom {
        name.set("BOSS Plugin Bookmark Types")
        description.set("Bookmark data types for BOSS Plugin API")
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
