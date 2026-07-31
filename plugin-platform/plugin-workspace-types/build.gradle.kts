
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
                // Compose runtime for @Immutable, and the Compose compiler plugin applied above
                // also refuses to run without a runtime on the compile classpath.
                //
                // Kept as `implementation` rather than narrowed to `compileOnly` (which would
                // satisfy both, since @Immutable is BINARY-retention and the generated $stable is a
                // plain int) because this artifact is already published: dropping a transitive
                // runtime dependency is consumer-visible. plugin-logging uses compileOnly because it
                // had no Compose dependency to preserve.
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

// Publishing must not bypass the $stable guard.
//
// Co-locating the test in this module does NOT put it on the publish path:
// publishAndReleaseToMavenCentral has no dependency on check/allTests/desktopTest, and
// publish-maven-central.yml invokes the publish task directly with no test step ahead of it. So
// without this a release could ship without the field and nothing on that path would object —
// which is the hole that let the divergence reach users in the first place.
//
// (PR CI does cover it, but via `./gradlew build` -> check -> allTests. A bare `./gradlew test`
// does not: a KMP jvm target registers desktopTest, not test.)
tasks.withType<AbstractPublishToMaven>().configureEach {
    dependsOn(tasks.named("desktopTest"))
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    pom {
        name.set("BOSS Plugin Workspace Types")
        description.set("Workspace data types for BOSS Plugin API")
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
