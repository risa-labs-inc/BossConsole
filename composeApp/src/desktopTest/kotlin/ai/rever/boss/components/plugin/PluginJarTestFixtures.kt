package ai.rever.boss.components.plugin

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Shared jar fixtures for the plugin-path resolver tests ([ReloadJarPathTest],
 * [FindRelocatedPluginJarTest]). One definition instead of two verbatim copies, and the temp
 * directory is removed by the caller via [use] instead of relying on `deleteOnExit`, which only
 * runs at JVM shutdown and leaks the directory across a Gradle test run.
 */
internal object PluginJarTestFixtures {
    /**
     * Create a unique temp directory. Wrap in [use] (or delete it in a `finally`) so the
     * directory does not outlive the test.
     */
    fun tempDir(): File =
        File.createTempFile("plugins", null).apply {
            delete()
            mkdirs()
        }

    /** Write a minimal plugin jar with the given manifest fields. */
    fun writeJar(
        dir: File,
        fileName: String,
        pluginId: String,
        version: String,
    ): File {
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
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()
        }
        return jar
    }
}

/** Scope a temp directory for the duration of [block], deleting it afterwards. */
internal inline fun <T> withTempDir(block: (File) -> T): T {
    val dir = PluginJarTestFixtures.tempDir()
    try {
        return block(dir)
    } finally {
        dir.deleteRecursively()
    }
}
