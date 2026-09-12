package ai.rever.boss.plugin.launchpad

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * Scaffolds third-party plugin projects with standard structure, Gradle wrapper, build, and tests.
 */
@Suppress("LongMethod", "TooManyFunctions", "ComplexCondition", "LargeClass")
object PluginScaffolder {
    enum class Template(
        val type: String,
    ) {
        MCP_TOOL("mcp-tool"),
        UI_PANEL("ui-panel"),
        BACKGROUND_SERVICE("background-service"),
        FULL("full"),
        ;

        companion object {
            fun fromString(value: String): Template {
                val normalized = value.lowercase().trim()
                return entries.firstOrNull { it.type == normalized }
                    ?: throw IllegalArgumentException(
                        "Unknown template '$value'. Supported: mcp-tool, ui-panel, background-service, full",
                    )
            }
        }
    }

    data class ScaffoldResult(
        val pluginId: String,
        val targetDirectory: File,
        val filesCreated: List<File>,
    )

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun scaffold(
        name: String,
        templateName: String = "mcp-tool",
        targetDir: File,
        force: Boolean = false,
    ): ScaffoldResult {
        val template = Template.fromString(templateName)

        if (targetDir.exists() && targetDir.isDirectory) {
            val existingFiles = targetDir.listFiles()
            if (existingFiles?.isNotEmpty() == true) {
                if (!force) {
                    val path = targetDir.absolutePath
                    error("Target directory '$path' exists and is not empty. Use --force to overwrite.")
                }
                assertSafeToPurge(targetDir)
                existingFiles.forEach { it.deleteRecursively() }
            }
        }

        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("Failed to create directory: ${targetDir.absolutePath}")
        }

        val baseSuffix =
            if (name.contains('.')) {
                name.substringAfterLast('.').ifBlank { "custom-plugin" }
            } else {
                name
            }
        val rawSuffix =
            baseSuffix
                .trim()
                .lowercase()
                .replace(Regex("[^a-z0-9-]"), "-")
                .replace(Regex("-+"), "-")
                .trim('-')
                .ifBlank { "custom-plugin" }

        val pluginId = derivePluginId(name)

        val className = sanitizeClassIdentifier(name)
        val packageSuffix = sanitizePackageIdentifier(rawSuffix)
        val packageName = "com.example.$packageSuffix"
        val packageDirRel = "com/example/$packageSuffix"

        val permissions =
            when (template) {
                Template.MCP_TOOL -> listOf("mcp")
                Template.UI_PANEL -> listOf("notifications")
                Template.BACKGROUND_SERVICE -> listOf("terminal", "notifications")
                Template.FULL -> listOf("mcp", "terminal", "notifications", "network")
            }

        val mcpTools =
            when (template) {
                Template.MCP_TOOL, Template.FULL -> {
                    listOf(
                        PluginMcpToolDeclaration(
                            name = "mcp__${pluginId.replace('.', '_').replace('-', '_')}__action",
                            description = "Executes $name action tool",
                            adminOnly = false,
                        ),
                    )
                }

                else -> {
                    emptyList()
                }
            }

        val manifest =
            PluginManifest(
                pluginId = pluginId,
                displayName = name,
                version = "0.1.0",
                description = "BossConsole plugin for $name ($templateName template)",
                author = "Boss Developer",
                apiVersion = HostMeta.CURRENT_API_VERSION,
                mainClass = "$packageName.$className",
                manifestVersion = 1,
                systemPlugin = false,
                canUnload = true,
                permissions = permissions,
                mcpTools = mcpTools,
            )

        val filesCreated = mutableListOf<File>()

        // 1. Root plugin.json
        val manifestFile = File(targetDir, "plugin.json")
        val manifestContent = launchpadJson.encodeToString(manifest)
        manifestFile.writeText(manifestContent)
        filesCreated += manifestFile

        // 1b. Resource manifest: src/main/resources/META-INF/boss-plugin/plugin.json
        val metaInfDir = File(targetDir, "src/main/resources/META-INF/boss-plugin")
        metaInfDir.mkdirs()
        val resourceManifest = File(metaInfDir, "plugin.json")
        resourceManifest.writeText(manifestContent)
        filesCreated += resourceManifest

        // 2. build.gradle.kts
        val gradleFile = File(targetDir, "build.gradle.kts")
        gradleFile.writeText(generateBuildGradle(HostMeta.CURRENT_API_VERSION))
        filesCreated += gradleFile

        // 2b. libs directory for local development fallback
        val libsDir = File(targetDir, "libs")
        libsDir.mkdirs()

        // 3. settings.gradle.kts
        val settingsFile = File(targetDir, "settings.gradle.kts")
        settingsFile.writeText("rootProject.name = \"$pluginId\"\n")
        filesCreated += settingsFile

        // 4. Gradle wrapper files: gradle/wrapper/gradle-wrapper.properties and gradle-wrapper.jar
        val wrapperDir = File(targetDir, "gradle/wrapper")
        wrapperDir.mkdirs()
        val wrapperPropsFile = File(wrapperDir, "gradle-wrapper.properties")
        wrapperPropsFile.writeText(generateGradleWrapperProperties())
        filesCreated += wrapperPropsFile

        val wrapperJarFile = File(wrapperDir, "gradle-wrapper.jar")
        copyOrGenerateWrapperJar(wrapperJarFile)
        filesCreated += wrapperJarFile

        // 5. gradlew (POSIX executable script)
        val gradlewFile = File(targetDir, "gradlew")
        val gradlewContent = generateGradlew().replace("\r\n", "\n").replace("\r", "\n")
        gradlewFile.writeBytes(gradlewContent.toByteArray(Charsets.UTF_8))
        setExecutablePermissions(gradlewFile)
        filesCreated += gradlewFile

        // 6. gradlew.bat (Windows batch script)
        val gradlewBatFile = File(targetDir, "gradlew.bat")
        gradlewBatFile.writeText(generateGradlewBat())
        filesCreated += gradlewBatFile

        // 7. Entrypoint source file: src/main/kotlin/<package-path>/<PluginName>.kt
        val srcDir = File(targetDir, "src/main/kotlin/$packageDirRel")
        srcDir.mkdirs()
        val entrypointFile = File(srcDir, "$className.kt")
        entrypointFile.writeText(generateSourceFile(packageName, className, name, pluginId, template))
        filesCreated += entrypointFile

        // 8. Sample test file: src/test/kotlin/<package-path>/<PluginName>Test.kt
        val testDir = File(targetDir, "src/test/kotlin/$packageDirRel")
        testDir.mkdirs()
        val testFile = File(testDir, "${className}Test.kt")
        testFile.writeText(generateTestFile(packageName, className, name, pluginId))
        filesCreated += testFile

        // 9. .gitignore
        val gitignoreFile = File(targetDir, ".gitignore")
        gitignoreFile.writeText(generateGitignore())
        filesCreated += gitignoreFile

        // 10. README.md
        val readmeFile = File(targetDir, "README.md")
        readmeFile.writeText(generateReadme(name, pluginId, template.type, HostMeta.CURRENT_API_VERSION))
        filesCreated += readmeFile

        return ScaffoldResult(pluginId, targetDir, filesCreated)
    }

    private fun setExecutablePermissions(file: File) {
        try {
            file.setExecutable(true, false)
        } catch (_: Exception) {
        }
        try {
            val perms =
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE,
                )
            Files.setPosixFilePermissions(file.toPath(), perms)
        } catch (_: Exception) {
        }
    }

    private fun copyOrGenerateWrapperJar(targetJar: File) {
        val resourceStream =
            PluginScaffolder::class.java.getResourceAsStream("/launcher/gradle-wrapper.jar")
                ?: error("Resource /launcher/gradle-wrapper.jar not found in host resources")
        resourceStream.use { input ->
            targetJar.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun generateGradleWrapperProperties(): String =
        """
        distributionBase=GRADLE_USER_HOME
        distributionPath=wrapper/dists
        distributionUrl=https\://services.gradle.org/distributions/gradle-8.7-bin.zip
        zipStoreBase=GRADLE_USER_HOME
        zipStorePath=wrapper/dists
        """.trimIndent() + "\n"

    private fun generateGradlew(): String =
        """
        #!/bin/sh
        # Minimal gradlew launcher
        set -e
        PRG="${'$'}0"
        while [ -h "${'$'}PRG" ]; do
            link="${'$'}(readlink "${'$'}PRG" 2>/dev/null || true)"
            if [ -z "${'$'}link" ]; then
                break
            fi
            case "${'$'}link" in
                /*) PRG="${'$'}link" ;;
                *) PRG="${'$'}(dirname "${'$'}PRG")/${'$'}link" ;;
            esac
        done
        APP_HOME="${'$'}(cd "${'$'}(dirname "${'$'}PRG")" >/dev/null 2>&1 && pwd)"
        exec java -jar "${'$'}APP_HOME/gradle/wrapper/gradle-wrapper.jar" "${'$'}@"
        """.trimIndent() + "\n"

    private fun generateGradlewBat(): String =
        """
        @rem Minimal gradlew.bat launcher
        @if "%DEBUG%"=="" @echo off
        set DIRNAME=%~dp0
        if "%DIRNAME%"=="" set DIRNAME=.
        java -jar "%DIRNAME%gradle\wrapper\gradle-wrapper.jar" %*
        """.trimIndent() + "\n"

    private fun generateBuildGradle(apiVersion: String): String =
        """
        plugins {
            kotlin("jvm") version "2.0.21"
            kotlin("plugin.serialization") version "2.0.21"
            `java-library`
        }

        kotlin {
            compilerOptions {
                freeCompilerArgs.add("-Xskip-metadata-version-check")
            }
        }

        repositories {
            mavenCentral()
            mavenLocal()
            ivy {
                url = uri("https://github.com/risa-labs-inc/boss-plugin-api/releases/download")
                patternLayout {
                    artifact("v[revision]/[artifact]-[revision].[ext]")
                    artifact("[revision]/[artifact]-[revision].[ext]")
                }
                metadataSources { artifact() }
            }
            flatDir {
                dirs("libs")
            }
        }

        dependencies {
            compileOnly("ai.rever.boss:boss-plugin-api:$apiVersion")
            testImplementation("ai.rever.boss:boss-plugin-api:$apiVersion")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            testImplementation(kotlin("test"))
            testImplementation(kotlin("test-junit5"))
        }

        tasks.test {
            useJUnitPlatform()
        }
        """.trimIndent() + "\n"

    private fun escapeString(raw: String): String =
        raw
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
            .replace("\r", "")
            .replace("\n", " ")

    private fun generateSourceFile(
        packageName: String,
        className: String,
        pluginName: String,
        pluginId: String,
        template: Template,
    ): String {
        val escapedName = escapeString(pluginName)
        val sanitizedToolName = pluginId.replace('-', '_').replace('.', '_')

        return when (template) {
            Template.MCP_TOOL -> {
                """
                package $packageName

                import ai.rever.boss.plugin.api.McpToolDefinition
                import ai.rever.boss.plugin.api.McpToolHandler
                import ai.rever.boss.plugin.api.McpToolProvider
                import ai.rever.boss.plugin.api.McpToolResult
                import ai.rever.boss.plugin.api.Plugin
                import ai.rever.boss.plugin.api.PluginContext

                class $className : Plugin {
                    override val pluginId: String = "$pluginId"
                    override val displayName: String = "$escapedName"

                    private var toolProvider: McpToolProvider? = null

                    override fun register(context: PluginContext) {
                        val provider =
                            object : McpToolProvider {
                                override val providerId: String = pluginId

                                override fun tools(): List<McpToolDefinition> =
                                    listOf(
                                        McpToolDefinition(
                                            name = "mcp__${sanitizedToolName}__action",
                                            description = "Executes $escapedName action tool",
                                            handler =
                                                McpToolHandler { _ ->
                                                    McpToolResult("Action executed successfully for $escapedName")
                                                },
                                        ),
                                    )
                            }
                        toolProvider = provider
                        context.registerMcpToolProvider(provider)
                    }

                    override fun dispose() {
                        toolProvider = null
                        println("Disposed $escapedName ($pluginId)")
                    }
                }
                """.trimIndent() + "\n"
            }

            Template.UI_PANEL -> {
                """
                package $packageName

                import ai.rever.boss.plugin.api.PanelId
                import ai.rever.boss.plugin.api.PanelMenuContribution
                import ai.rever.boss.plugin.api.PanelMenuItem
                import ai.rever.boss.plugin.api.Plugin
                import ai.rever.boss.plugin.api.PluginContext

                class $className : Plugin {
                    override val pluginId: String = "$pluginId"
                    override val displayName: String = "$escapedName"

                    private var menuContribution: PanelMenuContribution? = null

                    override fun register(context: PluginContext) {
                        val contribution =
                            object : PanelMenuContribution {
                                override val contributionId: String = "$pluginId.menu"
                                override val targetPanels: Set<String> = emptySet()

                                override fun items(panelId: PanelId): List<PanelMenuItem> =
                                    listOf(
                                        PanelMenuItem(
                                            id = "$pluginId.action",
                                            label = "$escapedName Action",
                                        ),
                                    )

                                override fun onItemClick(
                                    panelId: PanelId,
                                    itemId: String,
                                    windowId: String?,
                                ) {
                                    println("Panel menu item clicked: ${'$'}itemId for $escapedName")
                                }
                            }
                        menuContribution = contribution
                        context.registerPanelMenuContribution(contribution)
                    }

                    override fun dispose() {
                        menuContribution = null
                        println("Disposed $escapedName ($pluginId)")
                    }
                }
                """.trimIndent() + "\n"
            }

            Template.BACKGROUND_SERVICE -> {
                """
                package $packageName

                import ai.rever.boss.plugin.api.Plugin
                import ai.rever.boss.plugin.api.PluginContext
                import kotlinx.coroutines.Job
                import kotlinx.coroutines.delay
                import kotlinx.coroutines.isActive
                import kotlinx.coroutines.launch

                class $className : Plugin {
                    override val pluginId: String = "$pluginId"
                    override val displayName: String = "$escapedName"

                    private var workerJob: Job? = null

                    override fun register(context: PluginContext) {
                        workerJob =
                            context.pluginScope.launch {
                                println("Background service started for $escapedName ($pluginId)")
                                while (isActive) {
                                    delay(30_000)
                                    println("Background heartbeat for $escapedName")
                                }
                            }
                    }

                    override fun dispose() {
                        workerJob?.cancel()
                        workerJob = null
                        println("Background service stopped for $escapedName ($pluginId)")
                    }
                }
                """.trimIndent() + "\n"
            }

            Template.FULL -> {
                """
                package $packageName

                import ai.rever.boss.plugin.api.McpToolDefinition
                import ai.rever.boss.plugin.api.McpToolHandler
                import ai.rever.boss.plugin.api.McpToolProvider
                import ai.rever.boss.plugin.api.McpToolResult
                import ai.rever.boss.plugin.api.PanelId
                import ai.rever.boss.plugin.api.PanelMenuContribution
                import ai.rever.boss.plugin.api.PanelMenuItem
                import ai.rever.boss.plugin.api.Plugin
                import ai.rever.boss.plugin.api.PluginContext
                import kotlinx.coroutines.Job
                import kotlinx.coroutines.delay
                import kotlinx.coroutines.isActive
                import kotlinx.coroutines.launch

                class $className : Plugin {
                    override val pluginId: String = "$pluginId"
                    override val displayName: String = "$escapedName"

                    private var toolProvider: McpToolProvider? = null
                    private var menuContribution: PanelMenuContribution? = null
                    private var workerJob: Job? = null

                    override fun register(context: PluginContext) {
                        val provider =
                            object : McpToolProvider {
                                override val providerId: String = pluginId

                                override fun tools(): List<McpToolDefinition> =
                                    listOf(
                                        McpToolDefinition(
                                            name = "mcp__${sanitizedToolName}__action",
                                            description = "Executes $escapedName action tool",
                                            handler =
                                                McpToolHandler { _ ->
                                                    McpToolResult("Action executed successfully for $escapedName")
                                                },
                                        ),
                                    )
                            }
                        toolProvider = provider
                        context.registerMcpToolProvider(provider)

                        val contribution =
                            object : PanelMenuContribution {
                                override val contributionId: String = "$pluginId.menu"
                                override val targetPanels: Set<String> = emptySet()

                                override fun items(panelId: PanelId): List<PanelMenuItem> =
                                    listOf(
                                        PanelMenuItem(
                                            id = "$pluginId.action",
                                            label = "$escapedName Action",
                                        ),
                                    )

                                override fun onItemClick(
                                    panelId: PanelId,
                                    itemId: String,
                                    windowId: String?,
                                ) {
                                    println("Panel menu item clicked: ${'$'}itemId for $escapedName")
                                }
                            }
                        menuContribution = contribution
                        context.registerPanelMenuContribution(contribution)

                        workerJob =
                            context.pluginScope.launch {
                                println("Full plugin service started for $escapedName ($pluginId)")
                                while (isActive) {
                                    delay(30_000)
                                }
                            }
                    }

                    override fun dispose() {
                        workerJob?.cancel()
                        workerJob = null
                        toolProvider = null
                        menuContribution = null
                        println("Disposed full plugin $escapedName ($pluginId)")
                    }
                }
                """.trimIndent() + "\n"
            }
        }
    }

    private fun generateTestFile(
        packageName: String,
        className: String,
        pluginName: String,
        pluginId: String,
    ): String {
        val escapedName = escapeString(pluginName)
        return """
            package $packageName

            import kotlin.test.Test
            import kotlin.test.assertEquals
            import kotlin.test.assertNotNull

            class ${className}Test {
                @Test
                fun testPluginMetadata() {
                    val plugin = $className()
                    assertNotNull(plugin)
                    assertEquals("$pluginId", plugin.pluginId)
                    assertEquals("$escapedName", plugin.displayName)
                }
            }
            """.trimIndent() + "\n"
    }

    private fun generateGitignore(): String =
        """
        .gradle/
        build/
        .idea/
        *.iml
        .DS_Store
        """.trimIndent() + "\n"

    private fun generateReadme(
        name: String,
        id: String,
        template: String,
        apiVersion: String,
    ): String =
        """
        # $name Plugin

        Scaffolded BossConsole plugin project.

        ## Specification
        - **Plugin ID**: `$id`
        - **Template**: `$template`
        - **Target Host API**: `$apiVersion`

        ## Build Instructions
        ```bash
        ./gradlew build
        ```

        ## Validate & Link
        ```bash
        boss plugin validate .
        boss plugin link .
        ```
        """.trimIndent() + "\n"

    private val RESERVED_KEYWORDS =
        setOf(
            "as",
            "break",
            "class",
            "continue",
            "do",
            "else",
            "false",
            "for",
            "fun",
            "if",
            "in",
            "interface",
            "is",
            "null",
            "object",
            "package",
            "return",
            "super",
            "this",
            "throw",
            "true",
            "try",
            "typealias",
            "typeof",
            "val",
            "var",
            "when",
            "while",
            "default",
            "switch",
            "case",
            "const",
            "goto",
        )

    fun isReservedKeyword(name: String): Boolean = RESERVED_KEYWORDS.contains(name.lowercase())

    fun derivePluginId(name: String): String {
        val trimmed = name.trim().lowercase()
        if (trimmed.contains('.')) {
            val cleaned =
                trimmed
                    .replace(Regex("[^a-z0-9._-]"), "-")
                    .replace(Regex("-+"), "-")
                    .trim('-', '.')
            if (cleaned.isNotEmpty() && cleaned.first().isLetter() && cleaned.contains('.')) {
                return cleaned
            }
        }
        val rawSuffix =
            trimmed
                .replace(Regex("[^a-z0-9-]"), "-")
                .replace(Regex("-+"), "-")
                .trim('-')
                .ifBlank { "custom-plugin" }
        return "com.example.$rawSuffix"
    }

    fun sanitizePackageIdentifier(rawId: String): String {
        val cleaned = rawId.lowercase().replace(Regex("[^a-z0-9]"), "")
        val validStart =
            if (cleaned.isNotEmpty() && cleaned.first().isDigit()) {
                "p$cleaned"
            } else {
                cleaned.ifBlank { "plugin" }
            }
        return if (isReservedKeyword(validStart)) "${validStart}_pkg" else validStart
    }

    fun sanitizeClassIdentifier(rawName: String): String {
        val pascal =
            rawName
                .trim()
                .split(Regex("[^a-zA-Z0-9]"))
                .filter { it.isNotBlank() }
                .joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
                .ifBlank { "Custom" }
        val base = if (pascal.first().isDigit()) "Plugin$pascal" else pascal
        return if (base.endsWith("Plugin")) base else "${base}Plugin"
    }

    /**
     * Prevents catastrophic directory deletion in --force mode by ensuring the directory is not a
     * system root, user home, git repository, or arbitrary non-plugin directory.
     */
    fun assertSafeToPurge(targetDir: File) {
        val canonicalTarget = targetDir.canonicalFile
        val userHome = System.getProperty("user.home")?.let { File(it).canonicalFile }
        require(userHome == null || canonicalTarget != userHome) {
            "Refusing to purge user home directory in --force mode: ${targetDir.absolutePath}"
        }
        val roots = File.listRoots()?.map { it.canonicalFile } ?: emptyList()
        require(roots.none { it == canonicalTarget }) {
            "Refusing to purge system root directory in --force mode: ${targetDir.absolutePath}"
        }
        val gitDir = File(targetDir, ".git")
        require(!gitDir.exists()) {
            "Refusing to purge directory containing a .git repository in --force mode: ${targetDir.absolutePath}"
        }
        val hasPluginJson =
            File(targetDir, "plugin.json").exists() ||
                File(targetDir, "src/main/resources/META-INF/boss-plugin/plugin.json").exists()
        val hasGradleBuild =
            File(targetDir, "build.gradle.kts").exists() || File(targetDir, "build.gradle").exists()
        require(hasPluginJson || hasGradleBuild) {
            "Refusing to purge non-plugin directory in --force mode: ${targetDir.absolutePath}. " +
                "Directory does not contain plugin.json or build.gradle.kts. Clear it manually if intended."
        }
    }
}
