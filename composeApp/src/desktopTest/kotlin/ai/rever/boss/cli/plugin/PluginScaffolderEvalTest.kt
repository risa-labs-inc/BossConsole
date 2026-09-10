package ai.rever.boss.cli.plugin

import ai.rever.boss.plugin.launchpad.HostMeta
import ai.rever.boss.plugin.launchpad.PluginManifest
import ai.rever.boss.plugin.launchpad.PluginScaffolder
import ai.rever.boss.plugin.launchpad.launchpadJson
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginScaffolderEvalTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    @Suppress("LongMethod")
    fun `scaffolds across all 4 templates into isolated temporary folders`() {
        val templates = listOf("mcp-tool", "ui-panel", "background-service", "full")

        for (tmpl in templates) {
            val targetFolder = File(tempDir.toFile(), "plugin-$tmpl")
            val result =
                PluginScaffolder.scaffold(
                    name = "test-$tmpl",
                    templateName = tmpl,
                    targetDir = targetFolder,
                    force = false,
                )

            assertEquals("test-$tmpl", result.pluginId)
            assertTrue(targetFolder.exists(), "Target folder should exist for $tmpl")

            // Verify canonical files exist
            val manifestFile = File(targetFolder, "plugin.json")
            val gradleFile = File(targetFolder, "build.gradle.kts")
            val settingsFile = File(targetFolder, "settings.gradle.kts")
            val gitignoreFile = File(targetFolder, ".gitignore")
            val readmeFile = File(targetFolder, "README.md")
            val wrapperPropertiesFile =
                File(
                    targetFolder,
                    "gradle" + File.separator + "wrapper" + File.separator + "gradle-wrapper.properties",
                )
            val wrapperJarFile =
                File(
                    targetFolder,
                    "gradle" + File.separator + "wrapper" + File.separator + "gradle-wrapper.jar",
                )
            val gradlewFile = File(targetFolder, "gradlew")
            val gradlewBatFile = File(targetFolder, "gradlew.bat")

            assertTrue(manifestFile.exists(), "plugin.json missing for $tmpl")
            assertTrue(gradleFile.exists(), "build.gradle.kts missing for $tmpl")
            assertTrue(settingsFile.exists(), "settings.gradle.kts missing for $tmpl")
            assertTrue(gitignoreFile.exists(), ".gitignore missing for $tmpl")
            assertTrue(readmeFile.exists(), "README.md missing for $tmpl")
            assertTrue(wrapperPropertiesFile.exists(), "gradle-wrapper.properties missing for $tmpl")
            assertTrue(wrapperJarFile.exists(), "gradle-wrapper.jar missing for $tmpl")
            assertTrue(gradlewFile.exists(), "gradlew missing for $tmpl")
            assertTrue(gradlewBatFile.exists(), "gradlew.bat missing for $tmpl")
            val isCrPresent = gradlewFile.readBytes().contains(13.toByte())
            assertFalse(isCrPresent, "gradlew must have strictly LF line endings without CR")
            assertTrue(gradlewFile.canExecute(), "gradlew must have executable bit set")

            // Verify plugin.json content
            val manifest = launchpadJson.decodeFromString<PluginManifest>(manifestFile.readText())
            assertEquals("test-$tmpl", manifest.id)
            assertEquals("0.1.0", manifest.version)
            assertEquals(HostMeta.CURRENT_API_VERSION, manifest.minApiVersion)

            when (tmpl) {
                "mcp-tool" -> {
                    assertTrue(manifest.permissions.contains("mcp"))
                    assertEquals(1, manifest.mcpTools.size)
                    assertTrue(
                        manifest.mcpTools
                            .first()
                            .name
                            .startsWith("mcp__test_mcp_tool__"),
                    )
                }

                "ui-panel" -> {
                    assertTrue(manifest.permissions.contains("notifications"))
                    assertTrue(manifest.mcpTools.isEmpty())
                }

                "background-service" -> {
                    assertTrue(manifest.permissions.contains("terminal"))
                    assertTrue(manifest.permissions.contains("notifications"))
                    assertTrue(manifest.mcpTools.isEmpty())
                }

                "full" -> {
                    assertTrue(manifest.permissions.contains("mcp"))
                    assertTrue(manifest.permissions.contains("terminal"))
                    assertTrue(manifest.permissions.contains("notifications"))
                    assertTrue(manifest.permissions.contains("network"))
                    assertEquals(1, manifest.mcpTools.size)
                }
            }

            // Verify Gradle structure
            val gradleContent = gradleFile.readText()
            assertTrue(gradleContent.contains("kotlin(\"jvm\")"))
            assertTrue(gradleContent.contains("ai.rever.boss:boss-plugin-api:${HostMeta.CURRENT_API_VERSION}"))

            // Verify standard source sets (src/main/kotlin/ and src/test/kotlin/)
            val entrypointClassPath = manifest.entrypointClass.replace('.', File.separatorChar) + ".kt"
            val srcFile =
                File(
                    targetFolder,
                    "src" + File.separator + "main" + File.separator + "kotlin" + File.separator + entrypointClassPath,
                )
            val testClassName = entrypointClassPath.removeSuffix(".kt") + "Test.kt"
            val testFile =
                File(
                    targetFolder,
                    "src" + File.separator + "test" + File.separator + "kotlin" + File.separator + testClassName,
                )
            val contractPath =
                listOf("src", "main", "kotlin", "ai", "rever", "boss", "plugin", "launchpad", "BossPlugin.kt")
                    .joinToString(File.separator)
            val contractFile = File(targetFolder, contractPath)

            assertTrue(srcFile.exists(), "Source file missing: ${srcFile.absolutePath}")
            assertTrue(testFile.exists(), "Test file missing: ${testFile.absolutePath}")
            assertTrue(contractFile.exists(), "Contract file missing: ${contractFile.absolutePath}")
        }
    }

    @Test
    fun `asserts non-empty collisions fail fast without force`() {
        val targetFolder = File(tempDir.toFile(), "collision-test")
        targetFolder.mkdirs()
        val dummy = File(targetFolder, "existing.txt")
        dummy.writeText("blocking")

        // Without force -> fails fast
        val ex =
            assertFailsWith<IllegalStateException> {
                PluginScaffolder.scaffold(
                    name = "collision-plugin",
                    templateName = "mcp-tool",
                    targetDir = targetFolder,
                    force = false,
                )
            }
        assertTrue(ex.message!!.contains("exists and is not empty"))

        // With force -> succeeds and overwrites
        val result =
            PluginScaffolder.scaffold(
                name = "collision-plugin",
                templateName = "mcp-tool",
                targetDir = targetFolder,
                force = true,
            )
        assertEquals("collision-plugin", result.pluginId)
        assertTrue(File(targetFolder, "plugin.json").exists())
    }

    @Test
    fun `sanitizes numeric prefixes and reserved keywords in package and class names`() {
        val testCases =
            listOf(
                Triple("3d-viewport", "p3dviewport", "Plugin3dViewportPlugin"),
                Triple("123-audit", "p123audit", "Plugin123AuditPlugin"),
                Triple("default", "default_pkg", "DefaultPlugin"),
                Triple("while-loop", "whileloop", "WhileLoopPlugin"),
                Triple("fun", "fun_pkg", "FunPlugin"),
            )

        for ((input, expectedPkgSuffix, expectedClass) in testCases) {
            val pkg = PluginScaffolder.sanitizePackageIdentifier(input)
            val cls = PluginScaffolder.sanitizeClassIdentifier(input)
            assertEquals(expectedPkgSuffix, pkg, "Package suffix for $input")
            assertEquals(expectedClass, cls, "Class name for $input")

            val targetFolder = File(tempDir.toFile(), "plugin-sanitize-$input")
            val result =
                PluginScaffolder.scaffold(
                    name = input,
                    templateName = "mcp-tool",
                    targetDir = targetFolder,
                    force = true,
                )

            val manifestFile = File(targetFolder, "plugin.json")
            assertTrue(manifestFile.exists(), "Manifest should exist for $input")
            val manifest = launchpadJson.decodeFromString<PluginManifest>(manifestFile.readText())
            assertEquals("com.example.$expectedPkgSuffix.$expectedClass", manifest.entrypointClass)
        }
    }
}
