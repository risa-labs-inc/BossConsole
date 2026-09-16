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
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
