plugins {
    alias(libs.plugins.kotlinJvm)
}

group = "ai.rever.boss.process"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    // IPC protocol definitions and connection management
    api(project(":boss-ipc"))

    // Kotlin coroutines
    api(libs.kotlinx.coroutines.core)

    // Logging
    implementation(libs.slf4j.api)

    // Testing
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
