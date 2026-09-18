package ai.rever.boss.plugin.repository

import ai.rever.boss.plugin.api.PluginManifestConstants
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalPluginRepositoryBoundedReadTest {
    private lateinit var tempDir: File
    private lateinit var repository: LocalPluginRepository

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("local-repo-test").toFile()
        repository = LocalPluginRepository(tempDir, "test-repo")
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
    fun `listPlugins reads valid plugin manifest`() =
        runBlocking {
            val validJson =
                """
                {
                    "pluginId": "com.example.repo.test",
                    "displayName": "Test Repo Plugin",
                    "version": "1.0.0",
                    "apiVersion": "1.0.0",
                    "mainClass": "com.example.repo.test.Main"
                }
                """.trimIndent()
            createTestJar("plugin-valid.jar", validJson)

            val plugins = repository.listPlugins().getOrThrow()
            assertEquals(1, plugins.size)
            assertEquals("com.example.repo.test", plugins[0].pluginId)
        }

    @Test
    fun `listPlugins skips oversized plugin manifest`() =
        runBlocking {
            val padding = "x".repeat(600 * 1024) // 600 KB > 512 KB
            val oversizedJson =
                """
                {
                    "pluginId": "com.example.repo.oversized",
                    "displayName": "Oversized Plugin",
                    "version": "1.0.0",
                    "apiVersion": "1.0.0",
                    "mainClass": "com.example.repo.oversized.Main",
                    "description": "$padding"
                }
                """.trimIndent()
            createTestJar("plugin-oversized.jar", oversizedJson)

            val plugins = repository.listPlugins().getOrThrow()
            assertTrue(plugins.isEmpty(), "Oversized manifest must be skipped during listing")
        }
}
