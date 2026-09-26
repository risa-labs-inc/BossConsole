rootProject.name = "buildSrc"

// The root build's version catalog, so buildSrc's test dependencies are pinned to the same versions
// as every other module's rather than to whatever Gradle's embedded Kotlin happens to bring.
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
