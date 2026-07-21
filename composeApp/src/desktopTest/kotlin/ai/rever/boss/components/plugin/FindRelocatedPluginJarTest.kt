package ai.rever.boss.components.plugin

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [findRelocatedPluginJar] — the fallback used by
 * [DynamicPluginManager.loadPersistedPlugins] when a persisted jar path went
 * stale because the background system-plugin updater replaced the file under
 * a new versioned name mid-startup.
 */
class FindRelocatedPluginJarTest {

    private fun tempDir(): File = File.createTempFile("plugins", null).apply {
        delete()
        mkdirs()
        deleteOnExit()
    }

    /** Write a minimal plugin jar with the given manifest fields. */
    private fun writeJar(dir: File, fileName: String, pluginId: String, version: String): File {
        val jar = File(dir, fileName)
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/boss-plugin/plugin.json"))
            zip.write(
                """
                {
                  "manifestVersion": 1,
                  "pluginId": "$pluginId",
                  "displayName": "Test Plugin",
                  "version": "$version",
                  "apiVersion": "1.0.0",
                  "mainClass": "com.example.TestPlugin"
                }
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()
        }
        return jar
    }

    @Test
    fun `finds the jar with the matching pluginId`() {
        val dir = tempDir()
        writeJar(dir, "other-plugin-9.9.9.jar", "com.example.other", "9.9.9")
        val target = writeJar(dir, "my-plugin-1.8.7.jar", "com.example.mine", "1.8.7")

        assertEquals(target, findRelocatedPluginJar(dir, "com.example.mine"))
    }

    @Test
    fun `prefers the highest manifest version when several match`() {
        val dir = tempDir()
        writeJar(dir, "my-plugin-1.8.6.jar", "com.example.mine", "1.8.6")
        val newest = writeJar(dir, "my-plugin-1.8.10.jar", "com.example.mine", "1.8.10")
        writeJar(dir, "my-plugin-1.8.9.jar", "com.example.mine", "1.8.9")

        // 1.8.10 > 1.8.9 numerically though not lexicographically.
        assertEquals(newest, findRelocatedPluginJar(dir, "com.example.mine"))
    }

    @Test
    fun `ignores jars without a readable manifest`() {
        val dir = tempDir()
        File(dir, "not-a-plugin.jar").writeText("garbage")
        val target = writeJar(dir, "my-plugin-1.0.0.jar", "com.example.mine", "1.0.0")

        assertEquals(target, findRelocatedPluginJar(dir, "com.example.mine"))
    }

    @Test
    fun `returns null when nothing matches`() {
        val dir = tempDir()
        writeJar(dir, "other-plugin-1.0.0.jar", "com.example.other", "1.0.0")

        assertNull(findRelocatedPluginJar(dir, "com.example.mine"))
        assertNull(findRelocatedPluginJar(null, "com.example.mine"))
        assertNull(findRelocatedPluginJar(File(dir, "missing-subdir"), "com.example.mine"))
    }
}
