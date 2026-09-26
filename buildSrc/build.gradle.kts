plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(localGroovy())
    testImplementation(gradleTestKit())
    // DebControlTest — the .deb control rewrite is only exercised for real on a Linux
    // runner, so its string transform is unit-tested here instead.
    testImplementation(kotlin("test"))
    // kotlin("test") alone resolves JUnit 5.10 (Platform 1.10) through Gradle's embedded Kotlin, which
    // predates junit.platform.discovery.issue.severity.critical (Platform 1.13): the guard below was
    // read by nothing. The BOM lifts every JUnit artifact to the root's version, so the guard is live
    // and the two builds cannot drift apart on a later bump.
    testImplementation(platform("org.junit:junit-bom:${libs.versions.junit.jupiter.get()}"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // buildSrc is a separate build, so the root build.gradle.kts guard does not reach it: fail on a
    // @Test JUnit will not execute (a value-returning one is skipped with a warning otherwise).
    // CI runs these tests in the dedicated `./gradlew -p buildSrc test` step of build.yml.
    systemProperty("junit.platform.discovery.issue.severity.critical", "WARNING")
}
