package ai.rever.boss.plugin.loader

import java.io.File
import java.io.InputStream
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the bounded manifest read in [PluginManifestReader]: a `plugin.json`
 * JAR entry that inflates past [PluginManifestReader.MAX_MANIFEST_BYTES] must
 * be rejected with the same [PluginManifestException] used for malformed
 * manifests, before callers (e.g. signature verification) trust the JAR.
 * Without the bound, a tiny malicious JAR could declare a manifest that
 * decompresses to gigabytes and exhaust the heap (zip bomb).
 */
class PluginManifestReaderBoundedReadTest {
    private val tempJars = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tempJars.forEach { it.delete() }
    }

    /** Writes a temp JAR whose `plugin.json` entry holds [manifestText]. */
    private fun manifestJar(manifestText: String): String {
        val jar = File.createTempFile("bounded-manifest", ".jar")
        tempJars.add(jar)
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
            out.write(manifestText.toByteArray(Charsets.UTF_8))
            out.closeEntry()
        }
        return jar.absolutePath
    }

    /**
     * A structurally valid manifest whose exact UTF-8 size is [totalBytes].
     * The padding lives inside the `description` string value and is pure
     * ASCII, so bytes == chars and the JSON stays well-formed at any size.
     */
    private fun manifestOfTotalBytes(totalBytes: Int): String {
        val skeleton =
            listOf(
                "{\"pluginId\":\"com.example.bounded.read\"",
                "\"displayName\":\"Bounded Read\"",
                "\"version\":\"1.0.0\"",
                "\"apiVersion\":\"1.0.0\"",
                "\"mainClass\":\"com.example.Missing\"",
                "\"description\":\"\"}",
            ).joinToString(",")
        require(totalBytes >= skeleton.length) { "target below skeleton size" }
        val padding = "x".repeat(totalBytes - skeleton.length)
        return skeleton.replace("\"description\":\"\"", "\"description\":\"$padding\"")
    }

    @Test
    fun `a manifest larger than the cap is rejected with PluginManifestException`() {
        val oversize = manifestOfTotalBytes(PluginManifestReader.MAX_MANIFEST_BYTES + 1024)

        val error =
            assertFailsWith<PluginManifestException> {
                PluginManifestReader.readFromJar(manifestJar(oversize))
            }

        assertTrue(error.message.orEmpty().contains("exceeds"), "message should name the size cap: ${error.message}")
    }

    @Test
    fun `an oversize manifest also fails the hasValidManifest pre-check`() {
        val oversize = manifestOfTotalBytes(PluginManifestReader.MAX_MANIFEST_BYTES + 1024)

        assertFalse(PluginManifestReader.hasValidManifest(manifestJar(oversize)))
    }

    @Test
    fun `a small manifest still parses`() {
        val small =
            """
            {
              "pluginId": "com.example.bounded.read",
              "displayName": "Bounded Read",
              "version": "1.0.0",
              "apiVersion": "1.0.0",
              "mainClass": "com.example.Missing"
            }
            """.trimIndent()

        val manifest = PluginManifestReader.readFromJar(manifestJar(small))

        assertEquals("com.example.bounded.read", manifest.pluginId)
    }

    @Test
    fun `a manifest just under the cap still parses`() {
        val manifest =
            PluginManifestReader.readFromJar(
                manifestJar(manifestOfTotalBytes(PluginManifestReader.MAX_MANIFEST_BYTES - 1)),
            )

        assertEquals("com.example.bounded.read", manifest.pluginId)
    }

    @Test
    fun `a manifest exactly at the cap still parses`() {
        val manifest =
            PluginManifestReader.readFromJar(
                manifestJar(manifestOfTotalBytes(PluginManifestReader.MAX_MANIFEST_BYTES)),
            )

        assertEquals("com.example.bounded.read", manifest.pluginId)
    }

    /**
     * A real zip-bomb regression fixture: the manifest entry claims
     * [ZIP_BOMB_INFLATED_BYTES] of uncompressed data but occupies only a
     * tiny compressed payload on disk (a repeated byte deflates ~1000:1),
     * streamed into the JAR so the fixture never holds the inflated size.
     * The reader must reject it within the byte bounds, not inflate it.
     */
    @Test
    fun `a zip-bomb manifest entry is rejected within the byte bounds`() {
        val jar = File.createTempFile("zip-bomb-manifest", ".jar")
        tempJars.add(jar)
        val chunk = ByteArray(CHUNK_BYTES) { 'x'.code.toByte() }
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
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
            "the fixture must stay tiny on disk: ${jar.length()} bytes for $ZIP_BOMB_INFLATED_BYTES inflated",
        )

        val startedAt = System.nanoTime()
        val error =
            assertFailsWith<PluginManifestException> {
                PluginManifestReader.readFromJar(jar.absolutePath)
            }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(
            error.message.orEmpty().contains("exceeds"),
            "message should name the size cap: ${error.message}",
        )
        assertTrue(
            elapsedMs < 5_000,
            "rejection must happen at the cap, not after inflating $ZIP_BOMB_INFLATED_BYTES bytes; took ${elapsedMs}ms",
        )
    }

    /**
     * Pins the bound itself, not just the policy: the reader may never pull
     * more than cap + 1 bytes from the stream. An unbounded
     * readText-then-check-length implementation fails here deterministically
     * (the stream refuses to serve past the cap) instead of passing because
     * the fixture happened to fit in the heap.
     */
    @Test
    fun `the reader never pulls more than cap plus one bytes from the stream`() {
        val cap = PluginManifestReader.MAX_MANIFEST_BYTES
        val stream = BoundedDemandStream(maxBytes = cap + 1)

        val error =
            assertFailsWith<PluginManifestException> {
                PluginManifestReader.readBoundedManifest(stream)
            }

        assertTrue(
            error.message.orEmpty().contains("exceeds"),
            "message should name the size cap: ${error.message}",
        )
        assertEquals(
            cap + 1,
            stream.served,
            "the reader must stop pulling at the cap, not drain the stream",
        )
    }

    /**
     * An effectively infinite stream of manifest bytes that fails the test
     * the moment a reader asks for more than [maxBytes] bytes in total. A
     * bounded reader stops at cap + 1; anything that keeps asking is an
     * unbounded read.
     */
    private class BoundedDemandStream(
        private val maxBytes: Int,
    ) : InputStream() {
        var served: Int = 0
            private set

        override fun read(): Int {
            demand(1)
            return 'x'.code
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (length == 0) return 0
            demand(length)
            buffer.fill('x'.code.toByte(), offset, offset + length)
            return length
        }

        private fun demand(requested: Int) {
            val total = served + requested
            if (total > maxBytes) {
                fail(
                    "unbounded read: the reader pulled $total bytes in total, " +
                        "more than the $maxBytes a bounded reader may ever request",
                )
            }
            served = total
        }
    }

    private companion object {
        /** 1 GiB of inflated manifest data - comfortably over any plausible test heap. */
        const val ZIP_BOMB_INFLATED_BYTES: Int = 1024 * 1024 * 1024

        /** Chunk size used to stream the bomb into the JAR; the inflated size is never held. */
        const val CHUNK_BYTES: Int = 1024 * 1024
    }
}
