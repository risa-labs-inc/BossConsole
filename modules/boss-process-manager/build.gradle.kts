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
    implementation(project(":boss-native-files"))

    // Kotlin coroutines
    api(libs.kotlinx.coroutines.core)

    // Logging
    implementation(libs.slf4j.api)

    // Testing
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    // IPC address resolution caches its data directory; isolate it before the test JVM starts.
    val testHome =
        layout.buildDirectory
            .dir("test-home/$name")
            .get()
            .asFile
    systemProperty("user.home", testHome.absolutePath)
    systemProperty("boss.data.dir", testHome.resolve(".boss").absolutePath)
    doFirst {
        testHome.deleteRecursively()
        testHome.mkdirs()
    }
}
