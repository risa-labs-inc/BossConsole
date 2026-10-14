plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.graalvmNative)
}

group = "ai.rever.boss.files"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation(libs.jna)
    testImplementation(libs.kotlin.test.junit)
}

// Exercise the same directory contract after native-image compilation; no test runner reflection is needed.
graalvmNative {
    toolchainDetection.set(false)
    metadataRepository {
        enabled.set(true)
    }
    binaries {
        named("main") {
            imageName.set("native-files-smoke")
            mainClass.set("ai.rever.boss.files.NativeFilesSmokeKt")
            classpath.from(sourceSets.test.get().runtimeClasspath)
            buildArgs.add("--no-fallback")
            buildArgs.add("-H:+ReportExceptionStackTraces")
        }
    }
}

tasks.named("nativeCompile") {
    dependsOn(tasks.testClasses)
}

tasks.register<JavaExec>("nativeSmokeJvm") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("ai.rever.boss.files.NativeFilesSmokeKt")
}
