plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.graalvmNative)
}

group = "ai.rever.boss.app.terminal"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

graalvmNative {
    binaries {
        named("main") {
            mainClass.set("ai.rever.boss.app.terminal.TerminalServiceMainKt")
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
    implementation(project(":boss-ipc"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(libs.kotlin.test.junit)
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "ai.rever.boss.app.terminal.TerminalServiceMainKt"
    }
}

tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "ai.rever.boss.app.terminal.TerminalServiceMainKt"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    with(tasks.jar.get())
}
