import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig
import java.util.Properties
import java.util.zip.ZipFile
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import org.gradle.process.ExecOperations
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction

// Detect Windows ARM64 — microkernel modules excluded (no protoc binaries)
val isWindowsArm64Build: Boolean = run {
    val a = System.getProperty("os.arch").lowercase()
    val n = System.getProperty("os.name").lowercase()
    n.contains("win") && (a == "aarch64" || a == "arm")
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    // alias(libs.plugins.androidApplication) // Disabled for desktop-focused development
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.jxbrowser)
}

// Interface for injecting ExecOperations into tasks
// Replaces deprecated project.exec() calls for Gradle 9.0 compatibility
interface InjectedExecOps {
    @get:Inject val execOps: ExecOperations
}

// Configuration-cache-compatible ValueSource for reading version properties
abstract class VersionPropertiesValueSource : ValueSource<Properties, VersionPropertiesValueSource.Parameters> {
    interface Parameters : ValueSourceParameters {
        val propertiesFile: RegularFileProperty
    }

    override fun obtain(): Properties {
        val props = Properties()
        val file = parameters.propertiesFile.get().asFile
        if (file.exists()) {
            file.inputStream().use { props.load(it) }
        }
        return props
    }
}

// Configuration-cache-compatible ValueSource for reading JxBrowser version from TOML
abstract class JxBrowserVersionValueSource : ValueSource<String, JxBrowserVersionValueSource.Parameters> {
    interface Parameters : ValueSourceParameters {
        val tomlFile: RegularFileProperty
    }

    override fun obtain(): String {
        val file = parameters.tomlFile.get().asFile
        if (!file.exists()) {
            throw GradleException("libs.versions.toml not found at ${file.absolutePath}")
        }
        val content = file.readText()
        return Regex("""jxbrowser\s*=\s*"([^"]+)"""")
            .find(content)?.groupValues?.get(1)
            ?: throw GradleException("Could not find jxbrowser version in libs.versions.toml")
    }
}

// Load version from properties file using configuration-cache-compatible providers
val versionPropsFile = layout.projectDirectory.file("../version.properties")
val versionPropsProvider = providers.of(VersionPropertiesValueSource::class.java) {
    parameters.propertiesFile.set(versionPropsFile)
}
val appVersion = versionPropsProvider.map { it.getProperty("app.version", "8.8.0") }.get()
val bundleVersion = versionPropsProvider.map { it.getProperty("app.bundle.version", "8.8.0") }.get()
// Base version (without prerelease suffix) for native package formats that don't support semver prereleases
val baseVersion = appVersion.substringBefore("-")

println("📦 Building BOSS Version: $appVersion")

// Path to libs.versions.toml for reading JxBrowser version (single source of truth)
val libsVersionsFile = layout.projectDirectory.file("../gradle/libs.versions.toml")

// Configuration-cache-compatible provider for JxBrowser version
// Can be overridden via -PjxBrowserVersion=X.Y.Z for CI builds (e.g., branded Chromium workflow)
val jxBrowserVersionProvider = providers.of(JxBrowserVersionValueSource::class.java) {
    parameters.tomlFile.set(libsVersionsFile)
}
val jxBrowserVersion = project.findProperty("jxBrowserVersion")?.toString()
    ?: jxBrowserVersionProvider.get()

// local.properties (git-ignored) as a lazy, configuration-cache-tracked input.
// Absent file → absent provider; callers must getOrElse/orNull.
val localPropertiesProvider: Provider<Properties> = providers
    .fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText
    .map { text -> Properties().apply { load(text.reader()) } }

// ---------------------------------------------------------------------------
// Embedded runtime config
//
// The repo carries no keys: the JxBrowser license and Supabase endpoint/anon
// key are injected at BUILD time from environment variables (CI: GitHub
// Actions secrets) or local.properties (dev), and baked into the app as a
// classpath resource that ConfigLoader treats as its lowest-priority source.
// Missing values are omitted — a fork without keys still builds and runs,
// with browser/auth features disabled at runtime.
// ---------------------------------------------------------------------------
val embeddedConfigKeys = listOf(
    "JXBROWSER_LICENSE_KEY",
    "SUPABASE_URL",
    "SUPABASE_ANON_KEY",
    "SUPABASE_FUNCTION_URL",
)

val generateEmbeddedConfig = tasks.register("generateEmbeddedConfig") {
    val outputDir = layout.buildDirectory.dir("generated/embeddedConfig")
    val outputFile = outputDir.map { it.file("boss-build-config.properties") }

    val keys = embeddedConfigKeys
    val envProviders = keys.associateWith { providers.environmentVariable(it) }
    val localProps = localPropertiesProvider

    // Values come from env/local.properties, not tracked files — always regenerate.
    outputs.upToDateWhen { false }
    outputs.file(outputFile)

    doLast {
        val local = localProps.orNull
        // Properties.store() so values round-trip through the Properties.load()
        // in ConfigLoader — hand-written key=value lines would corrupt values
        // containing backslashes or other characters load() treats specially.
        val resolved = Properties()
        keys.forEach { key ->
            val value = envProviders.getValue(key).orNull
                ?: local?.getProperty(key)
                // legacy local.properties spelling for the JxBrowser key
                ?: "jxbrowser.license.key".takeIf { key == "JXBROWSER_LICENSE_KEY" }
                    ?.let { local?.getProperty(it) }
            if (!value.isNullOrBlank()) resolved.setProperty(key, value)
        }
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writer().use { resolved.store(it, "Generated at build time — never commit") }
        }
        println("🔑 Embedded config: " + keys.joinToString { k ->
            "$k ${if (resolved.containsKey(k)) "✓" else "✗"}"
        })
    }
}

// Task to generate Version constants from properties
val generateVersionConstants = tasks.register("generateVersionConstants") {
    val outputDir = layout.buildDirectory.dir("generated/source/version")
    val outputFile = outputDir.map { it.file("ai/rever/boss/utils/VersionConstants.kt") }

    // Use providers for configuration cache compatibility
    val propsProvider = versionPropsProvider
    val majorProvider = propsProvider.map { it.getProperty("app.version.major", "8") }
    val minorProvider = propsProvider.map { it.getProperty("app.version.minor", "8") }
    val patchProvider = propsProvider.map { it.getProperty("app.version.patch", "0") }
    val prereleaseProvider = propsProvider.map { it.getProperty("app.prerelease.suffix", "") }
    val jxVersionProvider = jxBrowserVersionProvider

    // Track libs.versions.toml as an input for JxBrowser version
    inputs.file(libsVersionsFile)

    inputs.file(versionPropsFile)
    outputs.file(outputFile)

    // CRITICAL: Force regeneration on every build to prevent stale version constants
    // This prevents Issue #111 where builds had wrong version embedded in artifacts
    outputs.upToDateWhen { false }

    doLast {
        val major = majorProvider.get()
        val minor = minorProvider.get()
        val patch = patchProvider.get()
        val prerelease = prereleaseProvider.get().takeIf { it.isNotBlank() }
        val jxVersion = jxVersionProvider.get()

        // Generate PRERELEASE constant as nullable String
        val prereleaseConstant = if (prerelease != null) "\"$prerelease\"" else "null"

        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText("""
                |package ai.rever.boss.utils
                |
                |/**
                | * Auto-generated version constants from version.properties and libs.versions.toml
                | * Do not edit this file manually - it will be regenerated on build
                | */
                |internal object VersionConstants {
                |    const val MAJOR = $major
                |    const val MINOR = $minor
                |    const val PATCH = $patch
                |
                |    /** Prerelease suffix (e.g., "beta.1", "rc.2") or null for stable releases */
                |    val PRERELEASE: String? = $prereleaseConstant
                |
                |    /** JxBrowser version from gradle/libs.versions.toml */
                |    const val JXBROWSER_VERSION = "$jxVersion"
                |}
                |
            """.trimMargin())
        }
    }
}

// Bundled plugins configuration
// Downloads the latest boss-plugin-api from GitHub releases
// Repo: https://github.com/risa-labs-inc/boss-plugin-api

// Task to download latest bundled plugins from GitHub releases using curl
val downloadBundledPlugins = tasks.register("downloadBundledPlugins") {
    group = "build"
    description = "Downloads the latest bundled plugin JARs from GitHub releases"

    val destDir = layout.buildDirectory.dir("bundled-plugins")
    outputs.dir(destDir)

    doLast {
        val bundledPluginsDir = destDir.get().asFile
        bundledPluginsDir.mkdirs()

        // List of bundled plugins to download from GitHub releases
        // These are core plugins that ship with BossConsole
        val bundledPlugins = listOf(
            "risa-labs-inc/boss-plugin-api" to "boss-plugin-api",
            "risa-labs-inc/boss-plugin-terminal-tab" to "boss-plugin-terminal-tab",
            "risa-labs-inc/boss-plugin-terminal" to "boss-plugin-terminal",
            "risa-labs-inc/boss-plugin-fluck-browser" to "boss-plugin-fluck-browser",
            "risa-labs-inc/boss-plugin-editor-tab" to "boss-plugin-editor-tab",
            "risa-labs-inc/boss-plugin-plugin-manager" to "boss-plugin-plugin-manager",
            "risa-labs-inc/boss-plugin-bookmarks" to "boss-plugin-bookmarks"
        )

        for ((repo, artifactPrefix) in bundledPlugins) {
            try {
                logger.lifecycle("📦 Fetching latest release for $repo...")

                // Get latest release info from GitHub API using curl
                val apiUrl = "https://api.github.com/repos/$repo/releases/latest"
                val curlProcess = ProcessBuilder("curl", "-s", "-H", "Accept: application/vnd.github.v3+json", apiUrl)
                    .redirectErrorStream(true)
                    .start()
                val responseText = curlProcess.inputStream.bufferedReader().readText()
                curlProcess.waitFor()

                // Parse JSON to find the JAR asset
                val tagNameMatch = Regex(""""tag_name"\s*:\s*"([^"]+)"""").find(responseText)
                val tagName = tagNameMatch?.groupValues?.get(1) ?: "unknown"

                // Find the JAR download URL (look for browser_download_url ending in .jar)
                val jarUrlMatch = Regex(""""browser_download_url"\s*:\s*"([^"]+$artifactPrefix[^"]*\.jar)"""").find(responseText)

                if (jarUrlMatch == null) {
                    logger.warn("⚠️  No JAR asset found in release for $repo")
                    continue
                }

                val jarUrl = jarUrlMatch.groupValues[1]
                val jarFileName = jarUrl.substringAfterLast("/")
                val destFile = File(bundledPluginsDir, jarFileName)

                // Check if we already have this version
                if (destFile.exists()) {
                    logger.lifecycle("✅ $jarFileName already exists (version: $tagName)")
                    continue
                }

                // Clean up old versions of this plugin
                bundledPluginsDir.listFiles()?.filter {
                    it.name.startsWith(artifactPrefix) && it.name.endsWith(".jar")
                }?.forEach { oldFile ->
                    logger.lifecycle("🗑️  Removing old version: ${oldFile.name}")
                    oldFile.delete()
                }

                // Download the JAR using curl
                logger.lifecycle("⬇️  Downloading $jarFileName...")
                val downloadProcess = ProcessBuilder("curl", "-sL", "-o", destFile.absolutePath, jarUrl)
                    .redirectErrorStream(true)
                    .start()
                downloadProcess.waitFor()

                logger.lifecycle("✅ Downloaded $jarFileName (version: $tagName, size: ${destFile.length()} bytes)")

            } catch (e: Exception) {
                logger.warn("⚠️  Failed to download bundled plugin from $repo: ${e.message}")
            }
        }
    }
}

// Task to copy bundled plugins from local build (for development)
val copyBundledPluginsLocal = tasks.register("copyBundledPluginsLocal") {
    group = "build"
    description = "Copies bundled plugin JARs from local build (for development), keeping only the latest version"

    val bossPluginApiDir = layout.projectDirectory.dir("../../boss-plugins/boss-plugin-api/build/libs")
    val pluginManagerDir = layout.projectDirectory.dir("../../boss-plugins/plugin-manager/build/libs")
    val bookmarksDir = layout.projectDirectory.dir("../../boss-plugins/bookmarks/build/libs")
    val terminalTabDir = layout.projectDirectory.dir("../../boss-plugins/terminal-tab/build/libs")
    val terminalDir = layout.projectDirectory.dir("../../boss-plugins/terminal/build/libs")
    val analyticsDir = layout.projectDirectory.dir("../../boss-plugins/analytics/build/libs")
    val destDir = layout.buildDirectory.dir("bundled-plugins")

    doLast {
        val bundledPluginsDir = destDir.get().asFile
        bundledPluginsDir.mkdirs()

        // Helper to extract version from JAR filename (e.g., "boss-plugin-api-1.0.21.jar" -> "1.0.21")
        fun extractVersion(fileName: String): String? {
            val match = Regex(""".*-(\d+\.\d+\.\d+)\.jar$""").find(fileName)
            return match?.groupValues?.get(1)
        }

        // Helper to compare versions (returns true if v1 > v2)
        fun isNewerVersion(v1: String, v2: String): Boolean {
            val v1Parts = v1.split(".").mapNotNull { it.toIntOrNull() }
            val v2Parts = v2.split(".").mapNotNull { it.toIntOrNull() }
            for (i in 0 until maxOf(v1Parts.size, v2Parts.size)) {
                val p1 = v1Parts.getOrElse(i) { 0 }
                val p2 = v2Parts.getOrElse(i) { 0 }
                if (p1 > p2) return true
                if (p1 < p2) return false
            }
            return false
        }

        // Helper to copy latest JAR, removing old versions
        fun copyLatestJar(sourceDir: File, artifactPrefix: String) {
            if (!sourceDir.exists()) {
                logger.warn("⚠️  Source directory not found: ${sourceDir.absolutePath}")
                return
            }

            // Find the latest JAR in source directory
            val sourceJar = sourceDir.listFiles()?.filter {
                it.name.startsWith(artifactPrefix) && it.name.endsWith(".jar") &&
                !it.name.contains("-sources") && !it.name.contains("-javadoc")
            }?.maxByOrNull { extractVersion(it.name) ?: "0.0.0" }

            if (sourceJar == null) {
                logger.warn("⚠️  No $artifactPrefix JAR found in: ${sourceDir.absolutePath}")
                return
            }

            val sourceVersion = extractVersion(sourceJar.name) ?: "0.0.0"

            // Check existing JARs in bundled-plugins directory
            val existingJars = bundledPluginsDir.listFiles()?.filter {
                it.name.startsWith(artifactPrefix) && it.name.endsWith(".jar")
            } ?: emptyList()

            // Find the latest existing version
            val latestExisting = existingJars.maxByOrNull { extractVersion(it.name) ?: "0.0.0" }
            val existingVersion = latestExisting?.let { extractVersion(it.name) } ?: "0.0.0"

            // Only copy if source is newer or same version
            if (isNewerVersion(existingVersion, sourceVersion)) {
                logger.lifecycle("⏭️  Keeping existing $artifactPrefix v$existingVersion (source has v$sourceVersion)")
                return
            }

            // Remove all old versions
            existingJars.forEach { oldJar ->
                logger.lifecycle("🗑️  Removing old version: ${oldJar.name}")
                oldJar.delete()
            }

            // Copy the new JAR
            val destFile = File(bundledPluginsDir, sourceJar.name)
            sourceJar.copyTo(destFile, overwrite = true)
            logger.lifecycle("✅ Copied ${sourceJar.name} (v$sourceVersion)")
        }

        // Copy boss-plugin-api
        copyLatestJar(bossPluginApiDir.asFile, "boss-plugin-api")

        // Copy plugin-manager
        copyLatestJar(pluginManagerDir.asFile, "boss-plugin-plugin-manager")

        // Copy bookmarks
        copyLatestJar(bookmarksDir.asFile, "boss-plugin-bookmarks")

        // Copy terminal-tab
        copyLatestJar(terminalTabDir.asFile, "boss-plugin-terminal-tab")

        // Copy terminal (sidebar)
        copyLatestJar(terminalDir.asFile, "boss-plugin-terminal")

        // Copy analytics (system plugin)
        copyLatestJar(analyticsDir.asFile, "boss-plugin-analytics")
    }
}

// Task to copy local plugin-manager to ~/.boss/plugins for development testing
val copyPluginManagerToDev = tasks.register("copyPluginManagerToDev") {
    group = "build"
    description = "Copies local plugin-manager build to ~/.boss/plugins for development testing"

    val pluginManagerDir = layout.projectDirectory.dir("../../boss-plugins/plugin-manager/build/libs")
    val userHome = System.getProperty("user.home")
    val destDir = File("$userHome/.boss/plugins")

    doLast {
        destDir.mkdirs()

        val sourceDir = pluginManagerDir.asFile
        val jarFile = sourceDir.listFiles()?.filter {
            it.name.startsWith("boss-plugin-plugin-manager") && it.name.endsWith(".jar") &&
            !it.name.contains("-sources") && !it.name.contains("-javadoc")
        }?.maxByOrNull { it.lastModified() }

        if (jarFile != null) {
            // Remove old versions
            destDir.listFiles()?.filter {
                it.name.startsWith("boss-plugin-plugin-manager") && it.name.endsWith(".jar")
            }?.forEach { oldFile ->
                logger.lifecycle("🗑️  Removing old version: ${oldFile.name}")
                oldFile.delete()
            }

            // Copy new version
            val destFile = File(destDir, jarFile.name)
            jarFile.copyTo(destFile, overwrite = true)
            logger.lifecycle("✅ Copied ${jarFile.name} to ${destDir.absolutePath}")

            // Update installed.json
            val installedJson = File(destDir, "installed.json")
            if (installedJson.exists()) {
                val content = installedJson.readText()
                val updatedContent = content.replace(
                    Regex("boss-plugin-plugin-manager-[0-9]+\\.[0-9]+\\.[0-9]+\\.jar"),
                    jarFile.name
                )
                installedJson.writeText(updatedContent)
                logger.lifecycle("✅ Updated installed.json to reference ${jarFile.name}")
            }
        } else {
            logger.warn("⚠️  No plugin-manager JAR found in: ${sourceDir.absolutePath}")
            logger.warn("   Build it first: cd ~/Development/Boss/boss-plugins/plugin-manager && ./gradlew build")
        }
    }
}

// Task to prepare bundled plugins for app resources (used by native distributions)
val prepareBundledPluginsResources = tasks.register<Copy>("prepareBundledPluginsResources") {
    group = "build"
    description = "Prepares bundled plugins in app resources structure for native distribution"

    // Use local builds for development, GitHub releases for CI
    val useLocalPlugins = System.getenv("CI") != "true"
    if (useLocalPlugins) {
        dependsOn(copyBundledPluginsLocal)
    } else {
        dependsOn(downloadBundledPlugins)
    }

    from(layout.buildDirectory.dir("bundled-plugins"))
    into(layout.buildDirectory.dir("bundled-plugins-resources/common/bundled-plugins"))
}

// Task to generate versioned CLI scripts from templates
val generateVersionedCLIScripts = tasks.register("generateVersionedCLIScripts") {
    val sourceDir = layout.projectDirectory.dir("../scripts")
    val outputDir = layout.buildDirectory.dir("generated/resources/cli")

    // Use providers for configuration cache compatibility
    val versionProvider = versionPropsProvider.map { it.getProperty("app.version", "8.8.0") }

    inputs.dir(sourceDir)
    inputs.file(versionPropsFile)
    outputs.dir(outputDir)

    // Force regeneration on every build to keep CLI version synchronized
    outputs.upToDateWhen { false }

    doLast {
        val version = versionProvider.get()
        val buildDate = LocalDateTime.now().format(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        )

        outputDir.get().asFile.mkdirs()

        sourceDir.asFile.listFiles()?.forEach { scriptFile ->
            if (scriptFile.isFile && (scriptFile.name == "boss" || scriptFile.name.startsWith("boss."))) {
                val content = scriptFile.readText()
                val versioned = content
                    .replace("{{VERSION}}", version)
                    .replace("{{BUILD_DATE}}", buildDate)

                val outputFile = outputDir.get().asFile.resolve(scriptFile.name)
                outputFile.writeText(versioned)

                // Preserve executable permission for bash script
                if (scriptFile.name == "boss") {
                    outputFile.setExecutable(true, false)
                }

                println("Generated versioned CLI script: ${scriptFile.name} (v$version)")
            }
        }
    }
}

repositories {
    google()
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    // JetBrains IntelliJ Platform repositories for PSI code navigation
    maven("https://www.jetbrains.com/intellij-repository/releases")
    maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
    // NOTE: the boss-plugin-api contract is deliberately NOT resolved from any
    // Maven registry — its distribution is store/GitHub-releases only. See
    // plugin-platform/plugin-api-core/build.gradle.kts (fetchApiPluginJar).
}

jxbrowser {
    // JxBrowser version from gradle/libs.versions.toml (single source of truth)
    version = jxBrowserVersion
}

kotlin {
    // Suppress expect/actual classes beta warning (KT-61573)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // Android target disabled for desktop-focused development
    /*
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    */

    // iOS targets disabled for desktop-focused development
    // Uncomment below if iOS support is needed in the future
    /*
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true

            // Export Decompose and Essenty for iOS
            export(libs.decompose)
            export(libs.essenty.lifecycle)
            export(libs.essenty.state.keeper)
        }
    }
    */

    jvm("desktop")

    // Enable experimental APIs
    sourceSets.all {
        languageSettings.optIn("kotlin.time.ExperimentalTime")
        languageSettings.optIn("kotlin.ExperimentalMultiplatform")
    }

    // WASM targets disabled for desktop-focused development
    /*
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        moduleName = "composeApp"
        browser {
            val rootDirPath = project.rootDir.path
            val projectDirPath = project.projectDir.path
            commonWebpackConfig {
                outputFileName = "composeApp.js"
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
                    static = (static ?: mutableListOf()).apply {
                        // Serve sources to debug inside browser
                        add(rootDirPath)
                        add(projectDirPath)
                    }
                }
            }
        }
        binaries.executable()
    }
    */
    
    sourceSets {
        val desktopMain = getByName("desktopMain") {
            if (isWindowsArm64Build) {
                // Exclude kernel/OOP source files that depend on boss-ipc (unavailable on Windows ARM64)
                kotlin.srcDirs.forEach { srcDir ->
                    kotlin.exclude(
                        "**/kernel/**",
                        "**/plugin/remote/**",
                        "**/plugin/OutOfProcessPluginSpawnerImpl.kt",
                        "**/plugin/PluginStateBridge.kt",
                    )
                }
            }
        }
        val desktopTest = getByName("desktopTest")

        // Add generated source directory to commonMain
        commonMain {
            kotlin.srcDir(generateVersionConstants.map { it.outputs.files.singleFile.parent })
        }

        // Add generated CLI scripts to desktopMain resources
        // Note: srcDir needs parent directory to maintain /cli/ path structure
        desktopMain.resources.srcDir(generateVersionedCLIScripts.map { it.outputs.files.singleFile.parentFile })

        // Build-time embedded config (JxBrowser license, Supabase endpoint/key)
        desktopMain.resources.srcDir(generateEmbeddedConfig.map { it.outputs.files.singleFile.parentFile })

        // androidMain.dependencies disabled for desktop-focused development
        /*
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
        }
        */
        commonMain.dependencies {
            implementation(libs.compose.mp.runtime)
            implementation(libs.compose.mp.foundation)
            implementation(libs.compose.mp.material)
            implementation(compose.materialIconsExtended)
            implementation(libs.compose.mp.ui)
            implementation(libs.compose.mp.components.resources)
            implementation(libs.compose.mp.components.ui.tooling.preview)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(projects.shared)
            // NOTE: BossEditor is not a host dependency. The editor-tab plugin
            // bundles bosseditor-compose-desktop (and its kotlin-compiler-embeddable
            // PSI stack) privately inside its own JAR; the plugin's classloader
            // resolves bosseditor classes from its own URLs while sharing the
            // host's Compose runtime via parent classloader delegation.
            // Same arrangement as BossTerm/terminal-tab (see the note below).
            // Minimal plugin-api-core (PluginContext, DynamicPlugin, PluginManifest)
            // Everything else comes from boss-plugin-api bundled plugin
            implementation(projects.pluginPlatform.pluginApiCore)
            implementation(projects.pluginPlatform.pluginUiCore)
            implementation(projects.pluginPlatform.pluginLogging)
            implementation(projects.pluginPlatform.pluginScrollbar)
            implementation(projects.pluginPlatform.pluginEvents)
            implementation(projects.pluginPlatform.pluginSearch)
            implementation(projects.pluginPlatform.pluginWindow)
            implementation(projects.pluginPlatform.pluginGitTypes)
            implementation(projects.pluginPlatform.pluginRunTypes)
            implementation(projects.pluginPlatform.pluginWorkspaceTypes)
            implementation(projects.pluginPlatform.pluginBookmarkTypes)
            implementation(projects.pluginPlatform.pluginIcons)
            implementation(projects.pluginPlatform.pluginPathUtils)
            implementation(projects.pluginPlatform.pluginSandbox)

            // Plugin management infrastructure
            implementation(projects.pluginPlatform.pluginLoader)
            implementation(projects.pluginPlatform.pluginRepository)
            implementation(projects.pluginPlatform.pluginUpdater)
            // Plugin panel manager is now dynamic (loaded from boss_plugin as plugin-manager)


            // Tab type plugins are now loaded dynamically from boss_plugin:
            // - editor-tab (was plugin-tab-code-editor)
            // - terminal-tab (was plugin-tab-terminal)
            // - fluck-browser (was plugin-tab-chatgpt-fluck)

            implementation(libs.precompose)
//            implementation(libs.precompose.molecule)
            implementation(libs.precompose.viewmodel)

            // Decompose dependencies
            implementation(libs.decompose)
            implementation(libs.decompose.extensions.compose)
            implementation(libs.decompose.extensions.compose.experimental)
            implementation(libs.essenty.lifecycle)
            implementation(libs.essenty.state.keeper)
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            
            // Compose Icons dependencies
            implementation(libs.compose.icons.feather)
            implementation(libs.compose.icons.fontawesome)
            implementation(libs.compose.icons.simpleicons)
            
            // Supabase dependencies
            implementation(libs.supabase.postgrest)
            implementation(libs.supabase.auth)
            implementation(libs.supabase.realtime)
            implementation(libs.supabase.storage)
            implementation(libs.supabase.functions)
        }
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)

            // Microkernel infrastructure (optional KERNEL mode)
            // Excluded on Windows ARM64 where protoc is unavailable
            if (!isWindowsArm64Build) {
                implementation(project(":boss-ipc"))
                implementation(project(":boss-process-manager"))
                implementation(project(":boss-ui-sdk"))
            }
            implementation(libs.compose.mp.components.resources)

            // BossTerm is not a host dependency. The terminal-tab plugin
            // bundles bossterm-compose privately inside its own JAR; the
            // plugin's classloader resolves bossterm classes from its own
            // URLs while sharing the host's Compose runtime via parent
            // classloader delegation.

            // Logging
            implementation(libs.slf4j.api)
            implementation(libs.slf4j.simple)
            
            // QR Code generation
            implementation(libs.zxing.core)
            implementation(libs.zxing.javase)
            
            // JxBrowser - core API + Compose/Swing integration
            // Platform binary (currentPlatform) excluded: BOSS downloads branded Chromium
            // on first launch via ChromiumAutoDownloader (~86MB saved)
            implementation("com.teamdev.jxbrowser:jxbrowser:$jxBrowserVersion")
            implementation(jxbrowser.compose)
            implementation(jxbrowser.swing)

            // JNA for native platform API access (macOS screen capture permissions)
            implementation(libs.jna)
            implementation(libs.jna.platform)

            // JavaCV for video recording - removed due to notarization issues
            // implementation("org.bytedeco:javacv-platform:1.5.11")
            
            // Ktor client for HTTP requests
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)

            // CLI argument parsing
            implementation(libs.clikt)
        }

        desktopTest.dependencies {
            implementation(kotlin("test-junit5"))
            implementation(libs.junit.jupiter)
            // Test-only: lets the suite assert the IPC proxy's skip-list stays
            // equal to the in-process scanner's (no production coupling).
            // Resolved by path with a presence guard, NOT the type-safe
            // accessor: the module is excluded from the build on Windows ARM64
            // (settings.gradle.kts — boss-ipc's protoc ships no win-arm64
            // binaries), where the generated accessor wouldn't even compile.
            // findProject only guards presence; the dependency itself must use
            // project(path) string notation — passing the Project OBJECT to
            // implementation() is an error in Gradle 10.
            if (findProject(":plugin-platform:plugin-api-ipc") != null) {
                implementation(project(":plugin-platform:plugin-api-ipc"))
            }
        }
        // Without the IPC module (Windows ARM64) the drift test can't compile;
        // drop it from the source set — every other platform still enforces it.
        if (findProject(":plugin-platform:plugin-api-ipc") == null) {
            desktopTest.kotlin.exclude("**/SkipListDriftTest.kt")
        }
    }
}


// ---------------------------------------------------------------------------
// macOS code signing resolution
//
// Release builds sign with a "Developer ID Application" certificate. CI imports
// that cert into a keychain; local dev machines usually don't have it. Rather
// than hard-fail createDistributable with "Could not find certificate...", we
// auto-skip signing when the resolved identity isn't in the keychain.
//
// Signing is disabled when DISABLE_MACOS_SIGNING=true is set, OR the resolved
// Developer ID identity is not available in the keychain. CI (cert present)
// still signs; local packageDistributionForCurrentOS just works unsigned.
// ---------------------------------------------------------------------------
val isMacOSHost: Boolean = System.getProperty("os.name").lowercase().contains("mac")

// No identity is hardcoded: resolution is env vars, then local.properties
// (MACOS_DEVELOPER_ID=Developer ID Application: ...), else blank → unsigned.
val macOSDeveloperId: String = System.getenv("MACOS_DEVELOPER_ID")
    ?: System.getenv("DEVELOPER_ID")
    ?: localPropertiesProvider.map { it.getProperty("MACOS_DEVELOPER_ID") }.orNull
    ?: ""

// Is the signing identity present in the keychain? providers.exec keeps this
// configuration-cache compatible (a cert import invalidates the entry), and the
// provider chain keeps it LAZY: the keychain is only scanned when something
// actually queries the signing decision (packaging/notarization tasks), never
// on ./gradlew run|test|help. "-" is ad-hoc signing. Lambdas capture locals,
// not script state, so they stay config-cache serializable.
val signingIdentityAvailableProvider: Provider<Boolean> = run {
    val devId = macOSDeveloperId
    when {
        !isMacOSHost -> providers.provider { false }
        // Blank identity (no env var / local.properties entry) → build unsigned;
        // without this guard contains("") below would match any keychain output.
        devId.isBlank() -> providers.provider { false }
        devId == "-" -> providers.provider { true }
        else -> providers.exec {
            commandLine("security", "find-identity", "-v", "-p", "codesigning")
            isIgnoreExitValue = true
        }.standardOutput.asText.map { it.contains(devId) }
    }
}

val macOSSigningDisabledProvider: Provider<Boolean> = run {
    val onMac = isMacOSHost
    val devId = macOSDeveloperId
    val envDisabled = System.getenv("DISABLE_MACOS_SIGNING") == "true"
    signingIdentityAvailableProvider.map { available ->
        val disabled = envDisabled || (onMac && !available)
        if (onMac && disabled && !envDisabled) {
            println("⚠️  macOS signing identity not found in keychain ('$devId') — building UNSIGNED. Import the cert or set MACOS_DEVELOPER_ID to sign.")
        }
        disabled
    }
}


compose.desktop {
    application {
        mainClass = "ai.rever.boss.MainKt"
        
        // Enable building uber/fat JAR
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
        
        // Specify JDK for native distributions - use JAVA_HOME from environment
        // Falls back to current JVM's home directory
        javaHome = System.getenv("JAVA_HOME") ?: System.getProperty("java.home")
        
        // JVM arguments optimized for platform-specific runtime
        // Build platform-specific args to avoid warnings about non-existent packages
        val currentOs = System.getProperty("os.name").lowercase()
        val currentArch = System.getProperty("os.arch").lowercase()
        val isMacosArm64 = currentOs.contains("mac") && (currentArch == "aarch64" || currentArch == "arm64")
        val platformJvmArgs = buildList {
            // Common JCEF argument
            add("--add-opens=java.desktop/sun.awt=ALL-UNNAMED")

            // macOS-specific: lwawt packages only exist on macOS
            if (currentOs.contains("mac")) {
                add("--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED")
                add("--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED")
                add("--add-opens=java.desktop/com.apple.eawt.event=ALL-UNNAMED")
                // Required for macOS native fullscreen via com.apple.eawt.Application reflection
                // Used by FullscreenBrowserWindow.kt. Tested on Java 17+.
                // Fallback to MAXIMIZED_BOTH if API unavailable.
                add("--add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED")
                add("-Dapple.awt.application.appearance=system")
            }

            // Linux-specific: X11 WM_CLASS access for desktop integration
            if (currentOs.contains("linux")) {
                add("--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED")
            }

            // Apple Silicon JIT compatibility flags (harmless on other platforms)
            add("-XX:+IgnoreUnrecognizedVMOptions")

            // macOS ARM64: Delay C2 JIT to avoid crash during JxBrowser native library loading
            // The C2 compiler conflicts with Rust allocator in libipc.dylib at startup (issue #476).
            // Use minimal delay - just enough to let JxBrowser initialize before C2 kicks in.
            // Defaults are ~1500/1500/40000 - we use slightly higher to avoid startup crash.
            if (isMacosArm64) {
                add("-XX:Tier4CompileThreshold=2000")       // Slight delay (default ~1500)
                add("-XX:Tier4InvocationThreshold=2000")    // Slight delay (default ~1500)
                add("-XX:Tier4BackEdgeThreshold=10000")     // Reduced from 50000 (default ~40000)
            }
        }
        jvmArgs(*platformJvmArgs.toTypedArray())

        // Bake Supabase config into the packaged app launcher so the shared Supabase
        // client AND the self-updater use the CI-provided values at runtime (via
        // ConfigLoader's system-property lookup, which outranks the hardcoded
        // SupabaseClientConfig fallback — see issue #33). CI supplies these via env from
        // repo secrets (release.yml top-level env); local `./gradlew run` leaves them
        // unset and uses the run-task systemProperty block / config defaults. The anon
        // key is the public, RLS-gated key.
        System.getenv("SUPABASE_ANON_KEY")?.takeIf { it.isNotBlank() }?.let { jvmArgs("-DSUPABASE_ANON_KEY=$it") }
        System.getenv("SUPABASE_URL")?.takeIf { it.isNotBlank() }?.let { jvmArgs("-DSUPABASE_URL=$it") }
        System.getenv("SUPABASE_FUNCTION_URL")?.takeIf { it.isNotBlank() }?.let { jvmArgs("-DSUPABASE_FUNCTION_URL=$it") }

        nativeDistributions {
            targetFormats(
                TargetFormat.Dmg,           // macOS
                TargetFormat.Msi,           // Windows
                TargetFormat.Deb,           // Linux - Ubuntu/Debian
                TargetFormat.Rpm            // Linux - RHEL/Fedora
                // Removed AppImage - not working reliably
                // JAR distribution handled separately via createExecutableJar task
            )
            packageName = "BOSS"

            // Include bundled plugins in the distribution
            // These are system plugins that ship with BossConsole
            appResourcesRootDir.set(layout.buildDirectory.dir("bundled-plugins-resources"))

            // Note: Bundled plugins will be copied to bundled-plugins/ directory
            // inside the app resources during the build process
            // Use base version (without prerelease suffix) for native packages
            // DMG and MSI don't support semver prerelease suffixes like "-beta.1"
            packageVersion = baseVersion
            description = "Business Operating System Service - Intelligent service automation platform"
            copyright = "© 2024 Risa Labs Inc. All rights reserved."
            vendor = "Risa Labs Inc."
            
            // Bundle a complete, self-contained JVM
            includeAllModules = true
            
            // Note: When includeAllModules is true, the modules() call is redundant
            // as all modules will be included. If you want to optimize size later,
            // set includeAllModules = false and specify only required modules:
            // modules("java.base", "java.desktop", "java.logging", "java.net.http", 
            //         "java.sql", "java.prefs", "java.scripting", "jdk.unsupported",
            //         "java.naming", "java.xml", "java.management", "jdk.crypto.ec")
            
            windows {
                menuGroup = "BOSS"
                upgradeUuid = "8a5a7659-2e0f-41bd-bbbb-3140b1e7dd7d"
                
                // Use Windows ICO format for proper MSI icon display
                iconFile.set(project.file("src/desktopMain/resources/boss_icon.ico"))
                
                // Windows code signing is handled by external tools (signtool.exe)
                // Configuration for MSI signing is done in the CI/CD pipeline
                
                // Configure Windows to accept command line arguments for deep links
                // The protocol registration is handled at runtime by WindowsProtocolHandler
                console = false  // Don't show console window
                dirChooser = true  // Allow user to choose install directory
                perUserInstall = true  // Install per-user to avoid admin requirements
                shortcut = true  // Create desktop shortcut
                menu = true  // Add to Start Menu
            }
            
            linux {
                packageName = "boss"
                debMaintainer = "support@risalabs.ai"
                menuGroup = "Development"
                appCategory = "Utility"
                shortcut = true
                iconFile.set(project.file("src/desktopMain/resources/boss_icon.png"))
                // RPM-specific options
                rpmLicenseType = "LGPL-3.0"
                appRelease = "1"
            }
            
            macOS {
                bundleID = "ai.rever.boss"
                iconFile.set(project.file("src/desktopMain/resources/boss_icon.icns"))
                packageName = "BOSS"
                // Use base version (without prerelease suffix) for DMG - doesn't support semver prereleases
                dmgPackageVersion = baseVersion
                dmgPackageBuildVersion = "1"
                
                
                // Note: bundleJRE is not a valid property in current Compose Desktop
                // The JVM is automatically bundled when creating native distributions
                // Use includeAllModules = true above to ensure all JVM modules are included
                
                // Code signing configuration
                signing {
                    // Sign unless explicitly disabled or no identity is available in the
                    // keychain. Provider-typed so the keychain scan only runs when a
                    // packaging task actually reads `sign`.
                    sign.set(macOSSigningDisabledProvider.map { !it })
                    identity.set(macOSDeveloperId)

                    // Debug logging (keychain lookup + final decision resolve lazily
                    // when packaging queries them; the UNSIGNED warning prints then)
                    println("🔐 macOS Code Signing Configuration:")
                    println("   DISABLE_MACOS_SIGNING: ${System.getenv("DISABLE_MACOS_SIGNING")}")
                    println("   Identity: $macOSDeveloperId")
                }
                
                // Entitlements
                entitlementsFile.set(project.file("src/desktopMain/resources/BOSS.entitlements"))
                
                // DMG customization
                infoPlist {
                    extraKeysRawXml = """
                        <key>LSMinimumSystemVersion</key>
                        <string>10.15</string>
                        <key>CFBundleShortVersionString</key>
                        <string>$appVersion</string>
                        <key>CFBundleVersion</key>
                        <string>$bundleVersion</string>
                        <key>NSHighResolutionCapable</key>
                        <true/>
                        <key>NSSupportsAutomaticGraphicsSwitching</key>
                        <true/>
                        <key>NSCameraUsageDescription</key>
                        <string>BOSS needs access to your camera for video conferencing and screen sharing.</string>
                        <key>NSMicrophoneUsageDescription</key>
                        <string>BOSS needs access to your microphone for video conferencing and voice communication.</string>
                        <key>NSBluetoothAlwaysUsageDescription</key>
                        <string>BOSS uses Bluetooth to let you sign in with a passkey stored on your phone or tablet.</string>
                        <key>NSBluetoothPeripheralUsageDescription</key>
                        <string>BOSS uses Bluetooth to let you sign in with a passkey stored on your phone or tablet.</string>
                        <key>CFBundleURLTypes</key>
                        <array>
                            <dict>
                                <key>CFBundleURLName</key>
                                <string>ai.rever.boss</string>
                                <key>CFBundleURLSchemes</key>
                                <array>
                                    <string>boss</string>
                                    <string>http</string>
                                    <string>https</string>
                                </array>
                            </dict>
                        </array>
                        <key>CFBundleDocumentTypes</key>
                        <array>
                            <dict>
                                <key>CFBundleTypeName</key>
                                <string>HTML Document</string>
                                <key>CFBundleTypeRole</key>
                                <string>Viewer</string>
                                <key>LSItemContentTypes</key>
                                <array>
                                    <string>public.html</string>
                                    <string>public.url</string>
                                </array>
                            </dict>
                        </array>
                    """.trimIndent()
                }
            }
        }
    }
}

// ============================================================================
// Application Run Tasks with Environment Configuration
// ============================================================================

// Simple wrapper task to run with local Supabase configuration
tasks.register("runLocal") {
    group = "application"
    description = "Run desktop application with local Supabase configuration (localhost:54321)"
    dependsOn("run")
}

// Simple wrapper task to run with production Supabase configuration
tasks.register("runProduction") {
    group = "application"
    description = "Run desktop application with production Supabase configuration (api.risaboss.com)"
    dependsOn("run")
}

// Configure all JavaExec tasks with boss.log.level from gradle.properties
// Also enable dev mode so ./gradlew run uses ~/.boss_debug (not ~/.boss)
tasks.withType<JavaExec>().configureEach {
    val bossLogLevel = project.findProperty("boss.log.level") as? String
    if (bossLogLevel != null) {
        systemProperty("boss.log.level", bossLogLevel)
    }
    systemProperty("boss.dev.mode", "true")
    // Dev runs are unbundled, so macOS shows the bare process/version (e.g. in the
    // Screen Recording list). Name the AWT app "BOSS" as a best-effort; the packaged
    // BOSS.app already reports "BOSS" via its bundle CFBundleName.
    systemProperty("apple.awt.application.name", "BOSS")
}

// Configure run task based on which wrapper task will execute
// This runs after task graph is built but before execution
gradle.taskGraph.whenReady {
    if (gradle.taskGraph.hasTask(":composeApp:runLocal")) {
        println("🏠 Running BOSS with LOCAL Supabase configuration")
        println()
        println("Configuration:")
        println("  SUPABASE_URL: http://localhost:54321")
        println("  SUPABASE_ANON_KEY: sb_publishable_ACJWlzQHlZjBrEguHvfOxg_3BJgxAaH")
        println("  SUPABASE_FUNCTION_URL: http://localhost:54321/functions/v1")
        println("  (System properties will override local.properties)")
        println()

        tasks.named<JavaExec>("run") {
            systemProperty("SUPABASE_URL", "http://localhost:54321")
            systemProperty("SUPABASE_ANON_KEY", "sb_publishable_ACJWlzQHlZjBrEguHvfOxg_3BJgxAaH")
            systemProperty("SUPABASE_FUNCTION_URL", "http://localhost:54321/functions/v1")
        }
    } else if (gradle.taskGraph.hasTask(":composeApp:runProduction")) {
        println("☁️  Running BOSS with PRODUCTION Supabase configuration")
        println()
        println("Configuration:")
        println("  SUPABASE_URL: https://api.risaboss.com")
        println("  SUPABASE_FUNCTION_URL: https://api.risaboss.com/functions/v1")
        println("  (System properties will override local.properties)")
        println()

        tasks.named<JavaExec>("run") {
            systemProperty("SUPABASE_URL", "https://api.risaboss.com")
            // The anon key is deliberately NOT hardcoded (public repo). It
            // resolves at runtime: SUPABASE_ANON_KEY env var, local.properties,
            // or the build-time embedded config resource.
            systemProperty("SUPABASE_FUNCTION_URL", "https://api.risaboss.com/functions/v1")
        }
    }
}

// Extract JCEF natives
tasks.register("extractJcefNatives") {
    // Declare provider at configuration time for configuration cache compatibility
    val jcefNativeDirProvider = layout.buildDirectory.dir("jcef-natives")

    // Declare outputs for up-to-date checking
    outputs.dir(jcefNativeDirProvider)
    outputs.cacheIf { true }

    doLast {
        // Access provider value at execution time
        val jcefNativeDir = jcefNativeDirProvider.get().asFile
        jcefNativeDir.mkdirs()

        // The jcefmaven library will download natives automatically
        // We just need to ensure the directory exists
        println("JCEF natives directory: ${jcefNativeDir.absolutePath}")
    }
}

// Extract CLI script to app bundle Resources for Homebrew installation
tasks.register("extractCLIToAppResources") {
    description = "Extracts CLI script to BOSS.app/Contents/Resources for Homebrew binary stanza"
    group = "build"

    // Configuration cache: the onlyIf/doLast lambdas must not reach through
    // the enclosing script object (this$0 is null after the task graph is
    // deserialized) nor call Task.project at execution time — capture
    // everything they need as task-local values at configuration time.
    // Providers stay lazy: .get() still happens inside doLast.
    val onMacHost = isMacOSHost
    val signingDisabledProvider = macOSSigningDisabledProvider
    val developerId = macOSDeveloperId
    val appDirProvider = layout.buildDirectory.dir("compose/binaries/main/app")
    val generatedCliDirProvider = layout.buildDirectory.dir("generated/resources/cli")
    val entitlementsFile = project.file("src/desktopMain/resources/BOSS.entitlements")

    // Only run on macOS
    onlyIf {
        onMacHost
    }

    // Inject ExecOperations for exec calls (replaces deprecated project.exec)
    val injected = project.objects.newInstance<InjectedExecOps>()

    doLast {
        println("📦 Extracting CLI script to app bundle Resources...")

        // Signing is skipped when disabled explicitly or no keychain identity is available.
        // Resolved here inside doLast, i.e. at execution time only.
        val signingDisabled = signingDisabledProvider.get()

        // Find the built app in the standard Compose Desktop location
        val appDir = appDirProvider.get().asFile
        val appFile = appDir.listFiles()?.find { it.name.endsWith(".app") }

        if (appFile?.exists() == true) {
            println("Found app: ${appFile.name}")

            // Target location: BOSS.app/Contents/Resources/
            val resourcesDir = File(appFile, "Contents/Resources")
            resourcesDir.mkdirs()

            // Source: generated CLI script from build/generated/resources/cli/boss
            val generatedCLIDir = generatedCliDirProvider.get().asFile
            val cliScript = File(generatedCLIDir, "boss")

            if (cliScript.exists()) {
                val targetScript = File(resourcesDir, "boss")

                // Copy the CLI script
                cliScript.copyTo(targetScript, overwrite = true)

                // Make it executable
                targetScript.setExecutable(true, false)

                println("✅ CLI script installed to: ${targetScript.absolutePath}")
                println("   Homebrew will symlink this to /opt/homebrew/bin/boss")

                // CRITICAL: Re-sign the entire app bundle after adding the CLI script
                // This is necessary because adding files to a signed bundle invalidates the signature
                if (!signingDisabled) {
                    println("🔒 Re-signing app bundle after CLI script installation...")
                    try {
                        injected.execOps.exec {
                            commandLine(
                                "codesign",
                                "--force",
                                "--deep",
                                "--options", "runtime",
                                "--sign", developerId,
                                "--timestamp",
                                "--entitlements", entitlementsFile.absolutePath,
                                appFile.absolutePath
                            )
                        }

                        // Verify the re-signed app
                        injected.execOps.exec {
                            commandLine("codesign", "-vvv", "--deep", "--strict", appFile.absolutePath)
                        }

                        println("✅ App bundle re-signed successfully after CLI script installation")
                    } catch (e: Exception) {
                        println("❌ Failed to re-sign app bundle: ${e.message}")
                        throw e
                    }
                } else {
                    println("⚠️  Signing disabled - skipping app bundle re-signing")
                }
            } else {
                println("⚠️  Warning: Generated CLI script not found at ${cliScript.absolutePath}")
                println("   Make sure generateVersionedCLIScripts task ran successfully")
            }
        } else {
            println("⚠️  Warning: Built app not found at ${appDir.absolutePath}")
            println("   This task should run after createDistributable")
        }
    }
}

// Sign PTY4J native binaries with hardened runtime for macOS notarization
// This is required because pty4j-unix-spawn-helper is not signed by default
tasks.register("signPty4jBinaries") {
    description = "Signs PTY4J native binaries with hardened runtime for Apple notarization"
    group = "build"

    // Configuration cache: the onlyIf/doLast lambdas must not reach through
    // the enclosing script object (this$0 is null after the task graph is
    // deserialized) nor call Task.project at execution time — capture
    // everything they need as task-local values at configuration time.
    // The signing-disabled provider stays lazy: .get() happens at execution.
    val onMacHost = isMacOSHost
    val signingDisabledProvider = macOSSigningDisabledProvider
    val developerId = macOSDeveloperId
    val appDirProvider = layout.buildDirectory.dir("compose/binaries/main/app")
    val entitlementsFile = project.file("src/desktopMain/resources/BOSS.entitlements")

    // Only run on macOS and when signing is enabled (resolved at execution time)
    onlyIf {
        onMacHost && !signingDisabledProvider.get()
    }

    // Inject ExecOperations for exec calls (replaces deprecated project.exec)
    val injected = project.objects.newInstance<InjectedExecOps>()

    doLast {
        println("🔧 Signing PTY4J native binaries with hardened runtime for notarization...")

        if (developerId == "-") {
            println("⚠️ No signing identity found, skipping PTY4J signing")
            return@doLast
        }

        // Find the built app in the standard Compose Desktop location
        val appDir = appDirProvider.get().asFile
        val appFile = appDir.listFiles()?.find { it.name.endsWith(".app") }

        if (appFile?.exists() == true) {
            println("Found app: ${appFile.name}")

            // Find PTY4J jar inside the app
            val appContents = File(appFile, "Contents/app")
            val pty4jJar = appContents.listFiles()?.find {
                it.name.startsWith("pty4j-") && it.name.endsWith(".jar")
            }

            if (pty4jJar?.exists() == true) {
                println("Processing PTY4J jar: ${pty4jJar.name}")

                // Create temporary directory for jar manipulation
                val tempDir = File(System.getProperty("java.io.tmpdir"), "pty4j-sign-${System.currentTimeMillis()}")
                tempDir.mkdirs()

                try {
                    // Extract the entire jar
                    injected.execOps.exec {
                        workingDir = tempDir
                        commandLine("jar", "xf", pty4jJar.absolutePath)
                    }

                    // Sign PTY4J native libraries with hardened runtime
                    val nativeFiles = tempDir.walkTopDown().filter {
                        it.isFile && (it.name.endsWith(".dylib") || it.name.contains("spawn-helper"))
                    }.toList()

                    if (nativeFiles.isNotEmpty()) {
                        println("Found ${nativeFiles.size} PTY4J native binary(ies) to sign:")
                        var signingFailures = 0

                        for (nativeFile in nativeFiles) {
                            println("  Signing: ${nativeFile.relativeTo(tempDir)}")

                            // Make executable
                            nativeFile.setExecutable(true)

                            // Sign with hardened runtime
                            try {
                                injected.execOps.exec {
                                    commandLine(
                                        "codesign",
                                        "--force",
                                        "--options", "runtime",
                                        "--sign", developerId,
                                        "--timestamp",
                                        nativeFile.absolutePath
                                    )
                                }

                                // Verify signature
                                injected.execOps.exec {
                                    commandLine("codesign", "-vv", nativeFile.absolutePath)
                                }

                                println("    ✅ Successfully signed ${nativeFile.name}")
                            } catch (e: Exception) {
                                signingFailures++
                                println("    ❌ Failed to sign ${nativeFile.name}: ${e.message}")
                            }
                        }

                        // Fail the build if any native binary failed to sign
                        if (signingFailures > 0) {
                            throw GradleException(
                                "❌ PTY4J signing failed: $signingFailures of ${nativeFiles.size} native binary(ies) could not be signed. " +
                                "This will cause notarization to fail."
                            )
                        }

                        // Recreate the jar with signed native libraries
                        val signedJar = File(pty4jJar.parentFile, "${pty4jJar.nameWithoutExtension}-signed.jar")
                        injected.execOps.exec {
                            workingDir = tempDir
                            commandLine("jar", "cf", signedJar.absolutePath, ".")
                        }

                        // Replace original jar with signed version
                        pty4jJar.delete()
                        signedJar.renameTo(pty4jJar)

                        println("✅ PTY4J jar updated with signed native libraries")

                    } else {
                        println("⚠️ Warning: No PTY4J native binaries found in jar")
                    }

                } finally {
                    // Clean up temp directory
                    tempDir.deleteRecursively()
                }

                // CRITICAL: Re-sign the entire app bundle after modifying the JAR
                println("🔒 Re-signing app bundle after PTY4J modifications...")
                try {
                    injected.execOps.exec {
                        commandLine(
                            "codesign",
                            "--force",
                            "--deep",
                            "--options", "runtime",
                            "--sign", developerId,
                            "--timestamp",
                            "--entitlements", entitlementsFile.absolutePath,
                            appFile.absolutePath
                        )
                    }

                    // Verify the re-signed app
                    injected.execOps.exec {
                        commandLine("codesign", "-vvv", "--deep", "--strict", appFile.absolutePath)
                    }

                    println("✅ App bundle re-signed successfully after PTY4J signing")
                } catch (e: Exception) {
                    println("❌ Failed to re-sign app bundle: ${e.message}")
                    throw e
                }
            } else {
                println("⚠️ Warning: PTY4J jar not found in ${appContents.absolutePath}")
            }
        } else {
            println("⚠️ Warning: Built app not found at ${appDir.absolutePath}")
            println("   This task should run after createDistributable")
        }
    }
}

// Fix Linux .desktop file to add StartupWMClass for proper desktop integration
// This task post-processes the .deb file after jpackage creates it
// Note: RPM packages require rpmbuild for repacking, which is not handled here
abstract class FixLinuxDesktopFileTask : DefaultTask() {
    @get:Inject
    abstract val execOps: ExecOperations

    @get:InputDirectory
    @get:Optional
    abstract val debDir: DirectoryProperty

    @TaskAction
    fun fixDesktopFile() {
        println("🔧 Fixing Linux .desktop file for proper dock integration...")

        val debDirectory = debDir.orNull?.asFile
        if (debDirectory == null || !debDirectory.exists()) {
            println("⚠️ No .deb directory found")
            return
        }

        val debFile = debDirectory.listFiles()?.find { it.name.endsWith(".deb") }
        if (debFile == null) {
            println("⚠️ No .deb file found in $debDirectory")
            return
        }

        println("📦 Fixing .desktop file in ${debFile.name}...")
        val workDir = File(debDirectory, "fix-temp-${System.currentTimeMillis()}")
        workDir.mkdirs()

        try {
            // Extract deb contents
            execOps.exec {
                commandLine("dpkg-deb", "-R", debFile.absolutePath, workDir.absolutePath)
            }

            // Find and modify .desktop file in the applications directory
            var modified = false
            workDir.walkTopDown()
                .filter { file ->
                    file.isFile &&
                    file.name.endsWith(".desktop") &&
                    // Verify it's in a valid location (lib/ or applications/)
                    (file.path.contains("/lib/") || file.path.contains("/applications/"))
                }
                .forEach { desktopFile ->
                    var content = desktopFile.readText()
                    var fileModified = false

                    // Add StartupWMClass if missing
                    if (!content.contains("StartupWMClass")) {
                        content = content.trimEnd() + "\nStartupWMClass=BOSS\n"
                        println("✅ Added StartupWMClass=BOSS to ${desktopFile.name}")
                        fileModified = true
                    }

                    // Change Icon path to use standard icon name for better desktop integration
                    if (content.contains("Icon=/opt/boss/lib/BOSS.png")) {
                        content = content.replace("Icon=/opt/boss/lib/BOSS.png", "Icon=boss")
                        println("✅ Changed Icon to use standard name in ${desktopFile.name}")
                        fileModified = true
                    }

                    if (fileModified) {
                        desktopFile.writeText(content)
                        modified = true
                    } else {
                        println("ℹ️ No changes needed for ${desktopFile.name}")
                    }
                }

            // Copy icon to hicolor theme directory for proper desktop integration
            val iconSource = workDir.walkTopDown().find { it.name == "BOSS.png" && it.path.contains("/lib/") }
            if (iconSource != null) {
                val hicolorDir = File(workDir, "usr/share/icons/hicolor/256x256/apps")
                hicolorDir.mkdirs()
                val iconDest = File(hicolorDir, "boss.png")
                iconSource.copyTo(iconDest, overwrite = true)
                println("✅ Copied icon to hicolor theme: ${iconDest.path}")
                modified = true
            }

            // Make desktop integration best-effort in the maintainer scripts.
            // jpackage's generated postinst/prerm run xdg-* tools (xdg-desktop-menu,
            // xdg-icon-resource, ...) under `set -e`; on headless systems (servers,
            // containers, CI) those exit non-zero (e.g. "No writable system menu
            // directory found"), which aborts dpkg configure/remove and leaves the
            // package half-installed. Tolerates leading whitespace and covers the
            // whole xdg-* class in case a future jpackage reorders or indents them.
            // Assumes single-line commands (true of jpackage output); a trailing
            // backslash continuation would put the appended `||` on the wrong line.
            listOf("postinst", "prerm").forEach { scriptName ->
                val script = File(workDir, "DEBIAN/$scriptName")
                if (script.isFile) {
                    val content = script.readText()
                    // The marker guard keeps this idempotent: without it the regex
                    // would re-match an already-patched line and append a second `||`.
                    if (!content.contains("skipping desktop integration")) {
                        val patched = content.replace(
                            Regex("(?m)^([ \\t]*)(xdg-\\S+ .*)$"),
                            "\$1\$2 || echo \"boss: no desktop environment detected, skipping desktop integration\" >&2"
                        )
                        if (patched != content) {
                            script.writeText(patched)
                            println("✅ Made xdg-* desktop integration best-effort in $scriptName (headless installs)")
                            modified = true
                        }
                    }
                }
            }

            if (modified) {
                // Repack deb using dpkg-deb --build
                execOps.exec {
                    commandLine("dpkg-deb", "--build", "--root-owner-group", workDir.absolutePath, debFile.absolutePath)
                }
                println("✅ Repacked ${debFile.name} with desktop-integration fixes")
            }
        } catch (e: Exception) {
            println("❌ Failed to fix .desktop file: ${e.message}")
            throw e
        } finally {
            workDir.deleteRecursively()
        }
    }
}

tasks.register<FixLinuxDesktopFileTask>("fixLinuxDesktopFile") {
    description = "Fixes the .deb for desktop integration: StartupWMClass, hicolor icon, and headless-safe maintainer scripts"
    group = "build"

    val isLinux = System.getProperty("os.name").lowercase().contains("linux")
    onlyIf { isLinux }

    debDir.set(layout.buildDirectory.dir("compose/binaries/main/deb"))
}

// Configure task dependencies for DMG packaging
afterEvaluate {
    // Make run tasks depend on the extraction tasks
    tasks.findByName("run")?.apply {
        dependsOn("extractJcefNatives")
    }

    // Ensure prepareAppResources depends on prepareBundledPluginsResources
    tasks.findByName("prepareAppResources")?.apply {
        dependsOn("prepareBundledPluginsResources")
    }

    val isMacOS = isMacOSHost

    // Configure createDistributable
    tasks.findByName("createDistributable")?.apply {
        // Ensure CLI scripts are generated before distribution tasks run
        dependsOn("generateVersionedCLIScripts")
        // Ensure bundled plugins are prepared
        dependsOn("prepareBundledPluginsResources")

        // Task chain: createDistributable → signPty4jBinaries → extractCLIToAppResources.
        // Both finalizers are wired unconditionally so the signing decision is NOT
        // needed at configuration time (keeps the keychain scan lazy): when signing
        // is disabled, signPty4jBinaries skips itself via its own onlyIf and the
        // CLI extraction still runs.
        if (isMacOS) {
            finalizedBy("signPty4jBinaries", "extractCLIToAppResources")
            println("📝 createDistributable will be finalized by signPty4jBinaries (skips itself when signing is disabled) and extractCLIToAppResources")
        }
    }

    // signPty4jBinaries runs after createDistributable, then triggers extractCLIToAppResources
    tasks.findByName("signPty4jBinaries")?.apply {
        mustRunAfter("createDistributable")
        if (isMacOS) {
            finalizedBy("extractCLIToAppResources")
            println("📝 signPty4jBinaries will be finalized by extractCLIToAppResources")
        }
    }

    // Ensure extractCLIToAppResources depends on CLI script generation and runs after
    // PTY4J signing. mustRunAfter is pure ordering — harmless when signPty4jBinaries
    // is skipped — so it needs no config-time signing check.
    tasks.findByName("extractCLIToAppResources")?.apply {
        dependsOn("generateVersionedCLIScripts")
        mustRunAfter("signPty4jBinaries")
        println("📝 extractCLIToAppResources will depend on generateVersionedCLIScripts")
    }

    // Ensure packageDmg runs after all signing/CLI tasks (ordering only, see above)
    tasks.findByName("packageDmg")?.apply {
        if (isMacOS) {
            mustRunAfter("signPty4jBinaries", "extractCLIToAppResources")
            println("📝 packageDmg will run after PTY4J signing and CLI extraction")
        }
    }

    // Linux: Fix .desktop file after DEB packaging to add StartupWMClass
    // Note: RPM repacking requires rpmbuild toolchain which is complex;
    // RPM packages will need manual StartupWMClass addition or a separate script
    val isLinux = System.getProperty("os.name").lowercase().contains("linux")
    if (isLinux) {
        tasks.findByName("fixLinuxDesktopFile")?.apply {
            mustRunAfter("packageDeb")
        }
        tasks.findByName("packageDeb")?.apply {
            finalizedBy("fixLinuxDesktopFile")
            println("📝 packageDeb will be finalized by fixLinuxDesktopFile")
        }
    }
}

// Task to create an executable JAR
tasks.register<Jar>("createExecutableJar") {
    dependsOn("desktopJar")
    group = "build"
    description = "Creates an executable JAR with all dependencies"

    // Enable zip64 for JARs with more than 65535 entries
    isZip64 = true

    archiveClassifier.set("all")
    archiveBaseName.set("BOSS")
    archiveVersion.set(appVersion as String)
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    
    // Get the desktop jar output
    val desktopJar = tasks.named<Jar>("desktopJar").get()
    
    // Include the main jar contents
    from(zipTree(desktopJar.archiveFile.get().asFile))
    
    // Include all runtime dependencies
    from(configurations.named("desktopRuntimeClasspath").get().map { 
        if (it.isDirectory) it else zipTree(it) 
    }) {
        exclude("META-INF/*.SF")
        exclude("META-INF/*.DSA")
        exclude("META-INF/*.RSA")
        exclude("META-INF/MANIFEST.MF")
        exclude("module-info.class")
    }
    
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    
    manifest {
        attributes(mapOf(
            "Main-Class" to "ai.rever.boss.MainKt",
            "Implementation-Title" to "BOSS",
            "Implementation-Version" to appVersion,
            "Implementation-Vendor" to "Risa Labs Inc.",
            "Multi-Release" to "true"
        ))
    }
}

// Task to package JAR with native libraries
tasks.register<Zip>("packageJarWithNatives") {
    dependsOn("createExecutableJar", "extractJcefNatives")
    group = "build"
    description = "Creates a distributable package with JAR and native libraries"

    archiveBaseName.set("BOSS-package")
    archiveVersion.set(appVersion as String)
    archiveExtension.set("zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))

    // Include the executable JAR
    from(layout.buildDirectory.dir("libs")) {
        include("BOSS-$appVersion-all.jar")
        into("")
    }

    // Include native libraries
    from(layout.buildDirectory.dir("jcef-natives")) {
        into("jcef-natives")
    }
    
    // Include launch scripts
    from(projectDir) {
        include("launch.sh", "launch.bat")
        into("")
    }
}

// Ensure version constants are generated before Kotlin compilation
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    dependsOn(generateVersionConstants)
}

// Configure Test tasks to not fail when no tests are discovered (Gradle 9+ compatibility)
tasks.withType<Test> {
    // Use JUnit Platform for test discovery
    useJUnitPlatform()
    // Disable failure when test sources exist but no tests are discovered
    // This handles misconfigured test sources or test classes without test methods
    failOnNoDiscoveredTests = false
}

// Wrapper tasks that auto-increment build number before packaging
tasks.register("packageDmgWithIncrement") {
    group = "distribution"
    description = "Builds DMG package with auto-incremented build number"

    doFirst {
        // Execute auto-increment before packaging
        rootProject.tasks.findByName("autoIncrementBuildNumber")?.actions?.forEach {
            it.execute(rootProject.tasks.getByName("autoIncrementBuildNumber"))
        }
    }

    finalizedBy("packageDmg")
}

tasks.register("packageMsiWithIncrement") {
    group = "distribution"
    description = "Builds MSI package with auto-incremented build number"

    doFirst {
        rootProject.tasks.findByName("autoIncrementBuildNumber")?.actions?.forEach {
            it.execute(rootProject.tasks.getByName("autoIncrementBuildNumber"))
        }
    }

    finalizedBy("packageMsi")
}

tasks.register("createExecutableJarWithIncrement") {
    group = "build"
    description = "Creates executable JAR with auto-incremented build number"

    doFirst {
        rootProject.tasks.findByName("autoIncrementBuildNumber")?.actions?.forEach {
            it.execute(rootProject.tasks.getByName("autoIncrementBuildNumber"))
        }
    }

    finalizedBy("createExecutableJar")
}

// Task to download Chromium binaries for branding
// Uses desktop runtime classpath since this is a Kotlin Multiplatform project
// Detached configuration for the JxBrowser platform binary needed by downloadChromium.
// This is NOT included in the app runtime (branded Chromium is used instead).
val jxBrowserPlatformBinary: Configuration = configurations.create("jxBrowserPlatformBinary")

dependencies {
    jxBrowserPlatformBinary(jxbrowser.currentPlatform)
}

tasks.register<JavaExec>("downloadChromium") {
    group = "jxbrowser"
    description = "Downloads JxBrowser Chromium binaries to a specified directory"

    dependsOn("desktopJar")

    // Use the desktop JAR and its dependencies, plus the platform binary for extraction
    classpath = files(
        tasks.named("desktopJar").map { it.outputs.files },
        configurations.named("desktopRuntimeClasspath"),
        jxBrowserPlatformBinary
    )
    mainClass.set("ai.rever.boss.ChromiumDownloaderKt")

    // Output directory can be configured via -PchromiumDir=<path>
    val chromiumDir = project.findProperty("chromiumDir")?.toString()
        ?: "${System.getProperty("user.home")}/chromium-binaries"

    args = listOf(chromiumDir)

    doFirst {
        println("Downloading Chromium to: $chromiumDir")
    }
}
