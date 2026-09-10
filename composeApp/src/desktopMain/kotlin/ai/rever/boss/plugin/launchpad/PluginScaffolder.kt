package ai.rever.boss.plugin.launchpad

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * Scaffolds third-party plugin projects with standard structure, Gradle wrapper, build, and tests.
 */
@Suppress("LongMethod", "TooManyFunctions", "ComplexCondition")
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

    fun scaffold(
        name: String,
        templateName: String = "mcp-tool",
        targetDir: File,
        force: Boolean = false,
    ): ScaffoldResult {
        val template = Template.fromString(templateName)

        if (targetDir.exists() && targetDir.isDirectory && (targetDir.listFiles()?.isNotEmpty() == true) && !force) {
            error("Target directory '${targetDir.absolutePath}' exists and is not empty. Use --force to overwrite.")
        }

        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw IOException("Failed to create directory: ${targetDir.absolutePath}")
        }

        val pluginId =
            name
                .trim()
                .lowercase()
                .replace(Regex("[^a-z0-9-]"), "-")
                .replace(Regex("-+"), "-")
                .trim('-')
                .ifBlank { "custom-plugin" }

        val className = sanitizeClassIdentifier(name)
        val packageSuffix = sanitizePackageIdentifier(pluginId)
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
                            name = "mcp__${pluginId.replace('-', '_')}__action",
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
                id = pluginId,
                name = name,
                version = "0.1.0",
                description = "BossConsole plugin for $name ($templateName template)",
                author = "Boss Developer",
                minApiVersion = HostMeta.CURRENT_API_VERSION,
                entrypointClass = "$packageName.$className",
                permissions = permissions,
                mcpTools = mcpTools,
            )

        val filesCreated = mutableListOf<File>()

        // 1. plugin.json
        val manifestFile = File(targetDir, "plugin.json")
        manifestFile.writeText(launchpadJson.encodeToString(manifest))
        filesCreated += manifestFile

        // 2. build.gradle.kts
        val gradleFile = File(targetDir, "build.gradle.kts")
        gradleFile.writeText(generateBuildGradle(HostMeta.CURRENT_API_VERSION))
        filesCreated += gradleFile

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

        // 7. Base contract: src/main/kotlin/ai/rever/boss/plugin/launchpad/BossPlugin.kt
        val contractDir = File(targetDir, "src/main/kotlin/ai/rever/boss/plugin/launchpad")
        contractDir.mkdirs()
        val contractFile = File(contractDir, "BossPlugin.kt")
        contractFile.writeText(generateContractFile())
        filesCreated += contractFile

        // 8. Entrypoint source file: src/main/kotlin/<package-path>/<PluginName>.kt
        val srcDir = File(targetDir, "src/main/kotlin/$packageDirRel")
        srcDir.mkdirs()
        val entrypointFile = File(srcDir, "$className.kt")
        entrypointFile.writeText(generateSourceFile(packageName, className, name, pluginId, template))
        filesCreated += entrypointFile

        // 9. Sample test file: src/test/kotlin/<package-path>/<PluginName>Test.kt
        val testDir = File(targetDir, "src/test/kotlin/$packageDirRel")
        testDir.mkdirs()
        val testFile = File(testDir, "${className}Test.kt")
        testFile.writeText(generateTestFile(packageName, className))
        filesCreated += testFile

        // 10. .gitignore
        val gitignoreFile = File(targetDir, ".gitignore")
        gitignoreFile.writeText(generateGitignore())
        filesCreated += gitignoreFile

        // 11. README.md
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
        val candidates =
            listOf(
                File("gradle/wrapper/gradle-wrapper.jar"),
                File("../gradle/wrapper/gradle-wrapper.jar"),
                File("../../gradle/wrapper/gradle-wrapper.jar"),
            )
        for (candidate in candidates) {
            if (candidate.exists() && candidate.isFile && candidate.length() > 0) {
                candidate.copyTo(targetJar, overwrite = true)
                return
            }
        }
        // Minimal fallback stub: empty or placeholder if host wrapper jar not found
        if (!targetJar.exists()) {
            targetJar.writeBytes(ByteArray(0))
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

    private fun generateGradlew(): String {
        val candidates =
            listOf(
                File("gradlew"),
                File("../gradlew"),
                File("../../gradlew"),
            )
        for (candidate in candidates) {
            if (candidate.exists() && candidate.isFile && candidate.length() > 0) {
                return candidate.readText().replace("\r\n", "\n").replace("\r", "\n")
            }
        }
        return """
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
    }

    private fun generateGradlewBat(): String {
        val hostGradlewBat = File("gradlew.bat")
        if (hostGradlewBat.exists() && hostGradlewBat.isFile) {
            return hostGradlewBat.readText()
        }
        return """
            @rem Minimal gradlew.bat launcher
            @if "%DEBUG%"=="" @echo off
            set DIRNAME=%~dp0
            if "%DIRNAME%"=="" set DIRNAME=.
            java -jar "%DIRNAME%gradle\wrapper\gradle-wrapper.jar" %*
            """.trimIndent() + "\n"
    }

    private fun generateContractFile(): String =
        """
        package ai.rever.boss.plugin.launchpad

        import kotlinx.coroutines.CoroutineScope

        /**
         * Core lifecycle interface implemented by BossConsole plugins.
         */
        interface BossPlugin {
            fun onStart(context: PluginContext)
            fun onStop() {}
        }

        data class PluginContext(
            val coroutineScope: CoroutineScope? = null,
            val hostContext: Any? = null,
        )
        """.trimIndent() + "\n"

    private fun generateBuildGradle(apiVersion: String): String =
        """
        plugins {
            kotlin("jvm") version "2.0.21"
            kotlin("plugin.serialization") version "2.0.21"
            `java-library`
        }

        repositories {
            mavenCentral()
        }

        dependencies {
            // Target Host API Contract: ai.rever.boss:boss-plugin-api:$apiVersion
            compileOnly(fileTree("libs") { include("*.jar") })
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            testImplementation(kotlin("test"))
            testImplementation(kotlin("test-junit5"))
        }

        tasks.test {
            useJUnitPlatform()
        }
        """.trimIndent() + "\n"

    private fun generateSourceFile(
        packageName: String,
        className: String,
        pluginName: String,
        pluginId: String,
        template: Template,
    ): String =
        """
        package $packageName

        import ai.rever.boss.plugin.launchpad.BossPlugin
        import ai.rever.boss.plugin.launchpad.PluginContext

        class $className : BossPlugin {
            override fun onStart(context: PluginContext) {
                println("Starting $pluginName plugin ($pluginId) [template: ${template.type}]")
            }

            override fun onStop() {
                println("Stopping $pluginName plugin ($pluginId)")
            }
        }
        """.trimIndent() + "\n"

    private fun generateTestFile(
        packageName: String,
        className: String,
    ): String =
        """
        package $packageName

        import ai.rever.boss.plugin.launchpad.PluginContext
        import kotlin.test.Test
        import kotlin.test.assertNotNull

        class ${className}Test {
            @Test
            fun testPluginInitialization() {
                val plugin = $className()
                plugin.onStart(PluginContext())
                assertNotNull(plugin)
                plugin.onStop()
            }
        }
        """.trimIndent() + "\n"

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
}
