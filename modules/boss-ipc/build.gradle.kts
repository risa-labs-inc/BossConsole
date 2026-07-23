import com.google.protobuf.gradle.*

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.protobuf)
}

group = "ai.rever.boss.ipc"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

// Detect if protoc + grpc codegen are available for this platform.
// protoc-gen-grpc-java does NOT publish Windows ARM64 binaries.
// protoc itself also lacks Windows ARM64.
// On those platforms, use pre-generated sources from src/main/generated/.
val osName: String = System.getProperty("os.name").lowercase()
val osArch: String = System.getProperty("os.arch").lowercase()
val isWindowsArm = osName.contains("win") && (osArch == "aarch64" || osArch == "arm")
val protocAvailable = !isWindowsArm

dependencies {
    // gRPC + Protobuf
    api(libs.grpc.protobuf)
    api(libs.grpc.stub)
    api(libs.grpc.kotlin.stub)
    api(libs.protobuf.kotlin)
    api(libs.protobuf.java)

    // gRPC Netty transport (supports Unix domain sockets)
    implementation(libs.grpc.netty)
    implementation(libs.netty.transport.native.unix.common)

    // Platform-specific UDS transports (all included — unused platforms are harmless)
    implementation(libs.netty.transport.native.kqueue) {
        artifact { classifier = "osx-x86_64" }
    }
    implementation(libs.netty.transport.native.kqueue) {
        artifact { classifier = "osx-aarch_64" }
    }
    implementation(libs.netty.transport.native.epoll) {
        artifact { classifier = "linux-x86_64" }
    }
    implementation(libs.netty.transport.native.epoll) {
        artifact { classifier = "linux-aarch_64" }
    }

    // Kotlin coroutines
    api(libs.kotlinx.coroutines.core)

    // Logging
    implementation(libs.slf4j.api)

    // Testing
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.grpc.services)
}

if (protocAvailable) {
    // Normal build: generate gRPC stubs from .proto files
    protobuf {
        protoc {
            artifact = "com.google.protobuf:protoc:4.35.1"
        }
        plugins {
            create("grpc") {
                artifact = "io.grpc:protoc-gen-grpc-java:1.82.2"
            }
            create("grpckt") {
                // Keep in lockstep with the grpc-kotlin-stub runtime version
                // (libs.versions.toml `grpc-kotlin`) — codegen/runtime skew can
                // generate stubs against APIs the runtime doesn't ship.
                artifact = "io.grpc:protoc-gen-grpc-kotlin:1.5.0:jdk8@jar"
            }
        }
        generateProtoTasks {
            all().forEach { task ->
                task.plugins {
                    create("grpc")
                    create("grpckt")
                }
                task.builtins {
                    create("kotlin")
                }
            }
        }
    }
} else {
    // Unsupported platform (Windows ARM64): use pre-generated sources.
    // These are committed at src/main/generated/ and kept in sync
    // by running `./gradlew :boss-ipc:generateProto` on a supported platform.
    logger.warn("protoc not available for $osName/$osArch — using pre-generated gRPC sources")
    sourceSets {
        main {
            java.srcDir("src/main/generated")
        }
    }

    // Disable proto generation tasks to avoid resolution errors
    protobuf {
        protoc {
            // Use a placeholder — protoc tasks are disabled below
            artifact = "com.google.protobuf:protoc:4.35.1"
        }
    }
    tasks.matching { it.name.startsWith("generateProto") || it.name.startsWith("extract") && it.name.contains("Proto") }.configureEach {
        enabled = false
    }
}
