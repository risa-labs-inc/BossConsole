plugins {
    alias(libs.plugins.kotlinJvm)
    id("org.graalvm.buildtools.native") version "1.1.3"
}

group = "ai.rever.boss.service.auth"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

graalvmNative {
    binaries {
        named("main") {
            mainClass.set("ai.rever.boss.service.auth.AuthServiceMainKt")
            buildArgs.add("--no-fallback")
            buildArgs.add("-H:+ReportExceptionStackTraces")
            buildArgs.add("--initialize-at-build-time=kotlin")
        }
    }
    // metadataRepository automatically fetches GraalVM Reachability Metadata
    // (reflect-config.json, resource-config.json, proxy-config.json, etc.) from
    // https://github.com/oracle/graalvm-reachability-metadata for all known
    // dependencies (gRPC, Netty, Ktor, kotlinx.serialization).
    // No manual reflect-config.json files are needed for these libraries.
    metadataRepository {
        enabled.set(true)
    }
}

dependencies {
    // IPC protocol and connection management
    implementation(project(":boss-ipc"))

    // Kotlin coroutines
    implementation(libs.kotlinx.coroutines.core)

    // Logging
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)

    // Serialization (for session data)
    implementation(libs.kotlinx.serialization.json)

    // Supabase auth integration
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.ktor.client.cio)

    // Testing
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Application entry point
tasks.jar {
    manifest {
        attributes["Main-Class"] = "ai.rever.boss.service.auth.AuthServiceMainKt"
    }
}

tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "ai.rever.boss.service.auth.AuthServiceMainKt"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    with(tasks.jar.get())
}
