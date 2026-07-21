plugins {
    alias(libs.plugins.kotlinJvm)
}

group = "ai.rever.boss.ui"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation(project(":boss-ipc"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test.junit)
}
