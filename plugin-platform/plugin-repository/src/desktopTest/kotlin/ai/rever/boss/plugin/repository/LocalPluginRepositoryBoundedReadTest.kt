package ai.rever.boss.plugin.repository

import ai.rever.boss.plugin.api.PluginManifestConstants
import ai.rever.boss.plugin.loader.PluginManifestReader
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the bounded manifest read on the repository side: LocalPluginRepository
 * reads the same `plugin.json` JAR entry as the loader (to list plugins and to
 * resolve downloads), so both of its readers must go through
 * [PluginManifestReader.readManifestContent] and reject an entry that inflates
 * past [PluginManifestReader.MAX_MANIFEST_BYTES] instead of loading it into
 * memory.
 *
 * The local plugin directory is user content, so without the bound a tiny
 * hostile JAR could otherwise exhaust the heap during a plain listing or
 * before downloadPlugin copies the JAR out.
 */
class LocalPluginRepositoryBoundedReadTest {
    @TempDir
    lateinit var temporary: File

    /** The plugin directory to scan, fresh for every test. */
    private fun pluginDirectory(): File = File(temporary, "plugins").apply { mkdirs() }

    /** Writes a plugin JAR into [directory] whose manifest entry holds [manifestText]. */
    private fun manifestJar(
        directory: File,
        manifestText: String,
    ): File {
        val jar = File(directory, "bounded-read.jar")
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry(PluginManifestConstants.MANIFEST_PATH))
            out.write(manifestText.toByteArray(Charsets.UTF_8))
            out.closeEntry()
        }
        return jar
    }

    /**
     * A structurally valid manifest whose exact UTF-8 size is [totalBytes].
     * The padding lives inside the `description` string value and is pure
     * ASCII, so bytes == chars and the JSON stays well-formed at any size.
     */
    private fun manifestOfTotalBytes(totalBytes: Int): String {
        val skeleton =
            listOf(
                "{\"pluginId\":\"com.example.local.bounded\"",
                "\"displayName\":\"Bounded Local\"",
                "\"version\":\"1.0.0\"",
                "\"apiVersion\":\"1.0.0\"",
                "\"mainClass\":\"com.example.Missing\"",
                "\"description\":\"\"}",
            ).joinToString(",")
        require(totalBytes >= skeleton.length) { "target below skeleton size" }
        val padding = "x".repeat(totalBytes - skeleton.length)
        return skeleton.replace("\"description\":\"\"", "\"description\":\"" + padding + "\"")
    }

    @Test
    fun `a jar whose manifest exceeds the cap is skipped during listing`() =
        runTest {
            val pluginDir = pluginDirectory()
            val oversize = manifestOfTotalBytes(PluginManifestReader.MAX_MANIFEST_BYTES + 1024)
            manifestJar(pluginDir, oversize)

            val plugins = LocalPluginRepository(pluginDir).listPlugins().getOrThrow()

            assertTrue(plugins.isEmpty(), "an oversize manifest must be skipped, not listed")
        }

    @Test
    fun `a jar with a small manifest is listed`() =
        runTest {
            val pluginDir = pluginDirectory()
            manifestJar(pluginDir, manifestOfTotalBytes(512))

            val plugins = LocalPluginRepository(pluginDir).listPlugins().getOrThrow()

            assertEquals(1, plugins.size)
            assertEquals("com.example.local.bounded", plugins.single().pluginId)
            assertEquals("Bounded Local", plugins.single().displayName)
        }

    @Test
    fun `a manifest just under the cap still lists`() =
        runTest {
            val pluginDir = pluginDirectory()
            manifestJar(pluginDir, manifestOfTotalBytes(PluginManifestReader.MAX_MANIFEST_BYTES - 1))

            val plugins = LocalPluginRepository(pluginDir).listPlugins().getOrThrow()

            assertEquals(1, plugins.size)
        }

    @Test
    fun `a manifest exactly at the cap still lists`() =
        runTest {
            val pluginDir = pluginDirectory()
            manifestJar(pluginDir, manifestOfTotalBytes(PluginManifestReader.MAX_MANIFEST_BYTES))

            val plugins = LocalPluginRepository(pluginDir).listPlugins().getOrThrow()

            assertEquals(1, plugins.size)
        }

    @Test
    fun `getJarPath ignores a jar whose manifest exceeds the cap`() {
        val pluginDir = pluginDirectory()
        val oversize = manifestOfTotalBytes(PluginManifestReader.MAX_MANIFEST_BYTES + 1024)
        manifestJar(pluginDir, oversize)

        val path = LocalPluginRepository(pluginDir).getJarPath("com.example.local.bounded")

        assertNull(path, "an oversize manifest must not resolve to a JAR path")
    }

    @Test
    fun `getJarPath finds a jar with a small manifest`() {
        val pluginDir = pluginDirectory()
        val jar = manifestJar(pluginDir, manifestOfTotalBytes(512))

        val path = LocalPluginRepository(pluginDir).getJarPath("com.example.local.bounded")

        assertEquals(jar.absolutePath, path)
    }

    @Test
    fun `downloadPlugin refuses a jar whose manifest exceeds the cap`() =
        runTest {
            val pluginDir = pluginDirectory()
            val oversize = manifestOfTotalBytes(PluginManifestReader.MAX_MANIFEST_BYTES + 1024)
            manifestJar(pluginDir, oversize)
            val downloads = File(temporary, "downloads").apply { mkdirs() }
            val target = File(downloads, "copied.jar")

            val result =
                LocalPluginRepository(pluginDir).downloadPlugin(
                    pluginId = "com.example.local.bounded",
                    version = null,
                    targetPath = target.absolutePath,
                )

            assertTrue(result.isFailure, "an oversize manifest must not be copied out as a download")
            assertTrue(!target.exists(), "nothing may be copied out of the hostile JAR")
        }

    /**
     * A real zip-bomb regression fixture for the repository scan: the JAR's
     * manifest entry claims [ZIP_BOMB_INFLATED_BYTES] of uncompressed data
     * backed by a tiny compressed payload on disk (a repeated byte deflates
     * ~1000:1), streamed into the JAR so the fixture never holds the
     * inflated size. The listing must skip it within the byte bounds.
     */
    @Test
    fun `a zip-bomb manifest entry is skipped during listing without being inflated`() =
        runTest {
            val pluginDir = pluginDirectory()
            zipBombJar(pluginDir)

            val startedAt = System.nanoTime()
            val plugins = LocalPluginRepository(pluginDir).listPlugins().getOrThrow()
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            assertTrue(plugins.isEmpty(), "a zip-bomb manifest must be skipped, not listed")
            assertTrue(
                elapsedMs < 5_000,
                "the scan must reject the bomb at the cap, not inflate it; took ${elapsedMs}ms",
            )
        }

    /** Writes a plugin JAR whose manifest entry inflates to 1 GiB from a tiny on-disk payload. */
    private fun zipBombJar(directory: File): File {
        val jar = File(directory, "zip-bomb.jar")
        val chunk = ByteArray(CHUNK_BYTES) { 'x'.code.toByte() }
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry(PluginManifestConstants.MANIFEST_PATH))
            var remaining = ZIP_BOMB_INFLATED_BYTES
            while (remaining > 0) {
                val toWrite = minOf(CHUNK_BYTES, remaining)
                out.write(chunk, 0, toWrite)
                remaining -= toWrite
            }
            out.closeEntry()
        }
        assertTrue(
            jar.length() < 16 * 1024 * 1024,
            "the zip-bomb fixture must stay tiny on disk, was ${jar.length()} bytes",
        )
        return jar
    }

    private companion object {
        /** 1 GiB of inflated manifest data - comfortably over any plausible test heap. */
        const val ZIP_BOMB_INFLATED_BYTES: Int = 1024 * 1024 * 1024

        /** Chunk size used to stream the bomb into the JAR; the inflated size is never held. */
        const val CHUNK_BYTES: Int = 1024 * 1024
    }
}
