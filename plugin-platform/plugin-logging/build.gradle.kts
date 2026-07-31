
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    // Compose compiler with no Compose code, on purpose.
    //
    // boss-plugin-api ships this same `ai.rever.boss.plugin.logging` package and IS a Compose
    // project, so its ComponentLogger carries the synthetic `$stable` field. This module's copy
    // shadows it parent-first in plugin classloaders, so any plugin compiled against the api and
    // holding a ComponentLogger property emitted `getstatic ComponentLogger.$stable` — which
    // linked at build time and was missing at runtime. BinaryCompatibilityValidator then rejected
    // the plugin outright and the host disabled it as binary incompatible; that took down
    // secret-manager 1.2.6 and 1.2.7 entirely.
    //
    // `$stable` was the ONLY difference between the two classes (verified member-by-member with
    // javap). Emitting it here makes the two copies interchangeable and fixes every ALREADY-BUILT
    // plugin, with no api release and no plugin rebuild. LoggingStableFieldTest pins it.
    alias(libs.plugins.composeCompiler)
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
        commonMain {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        named("desktopMain") {
            dependencies {
                implementation(libs.slf4j.api)
                implementation(libs.kotlinx.coroutines.core)
                // compileOnly, NOT implementation: the Compose compiler refuses to run without the
                // runtime on the compile classpath, but the only thing it generates here is the
                // `$stable` int field — the api's copy has an empty static initialiser and
                // references no Compose class. So nothing is needed at runtime and the published
                // POM stays free of a Compose dependency for a logging library.
                //
                // Declared here rather than in commonMain because every class the Compose compiler
                // processes lives in desktopMain; commonMain has no Kotlin sources at all.
                compileOnly(libs.compose.mp.runtime)
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
        name.set("BOSS Plugin Logging")
        description.set("Logging utilities for BOSS desktop application plugins")
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
