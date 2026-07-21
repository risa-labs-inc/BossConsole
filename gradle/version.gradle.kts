// Version Management for BOSS Application
// Kotlin DSL with proper Provider API for Gradle 9+ configuration cache compatibility

import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.*

// ============================================================================
// Extension Properties - Available to all build files
// ============================================================================

// Load version properties
val versionPropsFileObject = file("version.properties")
val versionProps = Properties()

if (versionPropsFileObject.exists()) {
    versionProps.load(FileInputStream(versionPropsFileObject))
} else {
    throw GradleException("version.properties file not found! Please create it with version information.")
}

// Extract version components
val versionMajor = versionProps["app.version.major"].toString().toInt()
val versionMinor = versionProps["app.version.minor"].toString().toInt()
val versionPatch = versionProps["app.version.patch"].toString().toInt()
val prereleaseSuffix = versionProps["app.prerelease.suffix"]?.toString()?.takeIf { it.isNotBlank() }

// Construct version strings
val appVersion = if (prereleaseSuffix != null) {
    "$versionMajor.$versionMinor.$versionPatch-$prereleaseSuffix"
} else {
    "$versionMajor.$versionMinor.$versionPatch"
}
val bundleVersion = versionProps["app.bundle.version"].toString()
val buildNumber = versionProps["app.build.number"].toString()

// Build artifact names
val jarName = "BOSS-$appVersion-all.jar"
val dmgName = "BOSS-$appVersion.dmg"
val dmgUniversalName = "BOSS-$appVersion-Universal.dmg"
val msiName = "BOSS-$appVersion.msi"
val packageZipName = "BOSS-package-$appVersion.zip"

// Set as extra properties for access from other build files
project.extra.apply {
    set("versionMajor", versionMajor)
    set("versionMinor", versionMinor)
    set("versionPatch", versionPatch)
    set("prereleaseSuffix", prereleaseSuffix)
    set("appVersion", appVersion)
    set("bundleVersion", bundleVersion)
    set("buildNumber", buildNumber)
    set("jarName", jarName)
    set("dmgName", dmgName)
    set("dmgUniversalName", dmgUniversalName)
    set("msiName", msiName)
    set("packageZipName", packageZipName)
}

// Version info for logging
println("📦 BOSS Version: $appVersion")
println("🔢 Build Number: $buildNumber")
println("📅 Build Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}")

// ============================================================================
// Task Registration - Using classes from buildSrc
// ============================================================================

val versionPropsFile = layout.projectDirectory.file("version.properties")

tasks.register<ShowVersionTask>("showVersion") {
    group = "versioning"
    description = "Display current version information"
    versionFile.set(versionPropsFile)
}

tasks.register<IncrementVersionTask>("incrementVersion") {
    group = "versioning"
    description = "Increment patch version and reset build number"
    inputFile.set(versionPropsFile)
    outputFile.set(versionPropsFile)
    skipGitCheck.set(providers.gradleProperty("skipGitCheck").map { it.toBoolean() }.orElse(false))
}

tasks.register<IncrementMinorTask>("incrementMinor") {
    group = "versioning"
    description = "Increment minor version and reset patch and build number"
    inputFile.set(versionPropsFile)
    outputFile.set(versionPropsFile)
    skipGitCheck.set(providers.gradleProperty("skipGitCheck").map { it.toBoolean() }.orElse(false))
}

tasks.register<IncrementMajorTask>("incrementMajor") {
    group = "versioning"
    description = "Increment major version and reset minor/patch and build number"
    inputFile.set(versionPropsFile)
    outputFile.set(versionPropsFile)
    skipGitCheck.set(providers.gradleProperty("skipGitCheck").map { it.toBoolean() }.orElse(false))
}

tasks.register<IncrementBuildNumberTask>("incrementBuildNumber") {
    group = "versioning"
    description = "Increment build number only"
    inputFile.set(versionPropsFile)
    outputFile.set(versionPropsFile)
    skipGitCheck.set(providers.gradleProperty("skipGitCheck").map { it.toBoolean() }.orElse(false))
}

tasks.register<AutoIncrementBuildNumberTask>("autoIncrementBuildNumber") {
    group = "versioning"
    description = "Auto-increment build number for package builds"
    inputFile.set(versionPropsFile)
    outputFile.set(versionPropsFile)
    skipGitCheck.set(providers.gradleProperty("skipGitCheck").map { it.toBoolean() }.orElse(false))
}

tasks.register<SetPrereleaseSuffixTask>("setPrereleaseSuffix") {
    group = "versioning"
    description = "Set prerelease suffix (e.g., -Psuffix=beta.1)"
    inputFile.set(versionPropsFile)
    outputFile.set(versionPropsFile)
    suffix.set(providers.gradleProperty("suffix"))
    skipGitCheck.set(providers.gradleProperty("skipGitCheck").map { it.toBoolean() }.orElse(false))
}

tasks.register<ClearPrereleaseSuffixTask>("clearPrereleaseSuffix") {
    group = "versioning"
    description = "Clear prerelease suffix (promote to stable)"
    inputFile.set(versionPropsFile)
    outputFile.set(versionPropsFile)
    skipGitCheck.set(providers.gradleProperty("skipGitCheck").map { it.toBoolean() }.orElse(false))
}

tasks.register<IncrementPrereleaseTask>("incrementPrerelease") {
    group = "versioning"
    description = "Increment prerelease number (e.g., beta.1 → beta.2)"
    inputFile.set(versionPropsFile)
    outputFile.set(versionPropsFile)
    skipGitCheck.set(providers.gradleProperty("skipGitCheck").map { it.toBoolean() }.orElse(false))
}
