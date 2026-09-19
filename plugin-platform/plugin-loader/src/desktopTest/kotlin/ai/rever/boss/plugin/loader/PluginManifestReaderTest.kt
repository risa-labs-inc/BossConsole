package ai.rever.boss.plugin.loader

import ai.rever.boss.plugin.api.PluginManifestConstants
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PluginManifestReaderTest {
    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("manifest-reader-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun createTestJar(
        name: String,
        manifestContent: String,
    ): File {
        val jarFile = File(tempDir, name)
        JarOutputStream(jarFile.outputStream()).use { jos ->
            val entry = JarEntry(PluginManifestConstants.MANIFEST_PATH)
            jos.putNextEntry(entry)
            jos.write(manifestContent.toByteArray(Charsets.UTF_8))
            jos.closeEntry()
        }
        return jarFile
    }

    @Test
    fun `readFromJar parses valid plugin manifest`() {
        val validJson =
            """
            {
                "pluginId": "com.example.test",
                "displayName": "Test Plugin",
                "version": "1.0.0",
                "apiVersion": "1.0.0",
                "mainClass": "com.example.test.Main"
            }
            """.trimIndent()
        val jar = createTestJar("valid.jar", validJson)

        val manifest = PluginManifestReader.readFromJar(jar.absolutePath)
        assertEquals("com.example.test", manifest.pluginId)
        assertEquals("Test Plugin", manifest.displayName)
    }

    @Test
    fun `readFromJar throws PluginManifestException when manifest exceeds max bytes`() {
        // Create an oversized manifest with 600 KB padding (> 512 KB limit)
        val padding = "a".repeat(600 * 1024)
        val oversizedJson =
            """
            {
                "pluginId": "com.example.oversized",
                "displayName": "Oversized Plugin",
                "version": "1.0.0",
                "apiVersion": "1.0.0",
                "mainClass": "com.example.oversized.Main",
                "description": "$padding"
            }
            """.trimIndent()
        val jar = createTestJar("oversized.jar", oversizedJson)

        assertFailsWith<PluginManifestException> {
            PluginManifestReader.readFromJar(jar.absolutePath)
        }
    }
}
