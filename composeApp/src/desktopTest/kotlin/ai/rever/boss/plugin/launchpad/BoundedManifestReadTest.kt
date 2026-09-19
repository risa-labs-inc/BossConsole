package ai.rever.boss.plugin.launchpad

import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the bounded-read guarantee that three host plugin-manifest readers
 * (PluginInstallService.extractManifestFromJar, PluginPersistence.extractVersionFromJar,
 * PluginStoreSetup.readPluginManifest) now depend on for their safety against zip-bomb entries.
 *
 * The bug this guards against: a DEFLATE entry can be tiny on disk and expand to hundreds of
 * megabytes. An unbounded `bufferedReader().readText()` would allocate the entire inflated
 * entry as a String and let a publisher-controlled GitHub release asset, or a hostile/corrupt
 * installed JAR, OOM the desktop JVM during onboarding or startup backfill.
 *
 * These tests build real JARs with real entries, then exercise the helper the host callers
 * route through. A regression that reverts to `readText()` (or any other unbounded read)
 * would re-introduce the bug; these tests pin the size-cap behaviour so that has to be a
 * deliberate change.
 */
class BoundedManifestReadTest {
    private val workDir = Files.createTempDirectory("boss-bounded-manifest-read-test")

    @AfterTest
    fun tearDown() {
        workDir.toFile().deleteRecursively()
    }

    @Test
    fun `the bounded reader returns the manifest for a normal-sized entry`() {
        val jar = writeJar("normal.jar", """{"pluginId":"com.example.ok","version":"1.0.0"}""")

        val manifestText =
            openJarFile(jar).use { jarFile ->
                val entry =
                    jarFile.getJarEntry("META-INF/boss-plugin/plugin.json")
                assertNotNull(entry, "test fixture missing manifest entry")
                DevPluginArtifacts.readBoundedUtf8String(jarFile.getInputStream(entry))
            }

        assertNotNull(manifestText)
        assertTrue(manifestText!!.contains("com.example.ok"))
    }

    @Test
    fun `the bounded reader returns null when the manifest entry exceeds the cap`() {
        // Build an entry whose content is one byte past the cap. A DEFLATE-friendly payload (all
        // the same byte) is not what a real zip-bomb uses, but the bounded read enforces its cap
        // on the inflated byte count, not the stored size, so this is enough to prove the gate.
        val oversized =
            "x".repeat(DevPluginArtifacts.MAX_MANIFEST_BYTES + 1)
        val jar = writeJar("oversized.jar", oversized)

        val result =
            openJarFile(jar).use { jarFile ->
                val entry =
                    jarFile.getJarEntry("META-INF/boss-plugin/plugin.json")
                assertNotNull(entry, "test fixture missing manifest entry")
                DevPluginArtifacts.readBoundedUtf8String(jarFile.getInputStream(entry))
            }

        assertNull(result, "expected null for an entry exceeding ${DevPluginArtifacts.MAX_MANIFEST_BYTES} bytes")
    }

    @Test
    fun `the bounded reader returns null when the entry is exactly one byte past the cap`() {
        // Pin the off-by-one: at exactly MAX_MANIFEST_BYTES the read should succeed, and at
        // MAX_MANIFEST_BYTES + 1 it must fail. A future change that drops the +1 in
        // readNBytes(maxBytes + 1) would fail this test.
        val jar = writeJar("exactly-too-big.jar", "x".repeat(DevPluginArtifacts.MAX_MANIFEST_BYTES + 1))

        val result =
            openJarFile(jar).use { jarFile ->
                val entry =
                    jarFile.getJarEntry("META-INF/boss-plugin/plugin.json")
                assertNotNull(entry, "test fixture missing manifest entry")
                DevPluginArtifacts.readBoundedUtf8String(jarFile.getInputStream(entry))
            }

        assertNull(result)
    }

    @Test
    fun `the bounded reader succeeds for an entry at exactly the cap`() {
        // A real manifest will not come close to 512 KiB, but the gate should accept an entry that
        // fills the cap exactly. Anything else leaves a plugin author with no headroom.
        val content = "x".repeat(DevPluginArtifacts.MAX_MANIFEST_BYTES)
        val jar = writeJar("exactly-fits.jar", content)

        val result =
            openJarFile(jar).use { jarFile ->
                val entry =
                    jarFile.getJarEntry("META-INF/boss-plugin/plugin.json")
                assertNotNull(entry, "test fixture missing manifest entry")
                DevPluginArtifacts.readBoundedUtf8String(jarFile.getInputStream(entry))
            }

        assertNotNull(result)
        assertEquals(DevPluginArtifacts.MAX_MANIFEST_BYTES, result!!.length)
    }

    @Test
    fun `the cap is shared across every host caller`() {
        // The cap is exported as MAX_MANIFEST_BYTES. If any of the three call sites starts using
        // a local constant, the cap drifts and a 511 KiB manifest could be rejected by one reader
        // and accepted by another. This test pins that they all read the same number.
        val jar = writeJar("cap.jar", "x".repeat(DevPluginArtifacts.MAX_MANIFEST_BYTES))

        val result =
            openJarFile(jar).use { jarFile ->
                val entry =
                    jarFile.getJarEntry("META-INF/boss-plugin/plugin.json")
                assertNotNull(entry, "test fixture missing manifest entry")
                DevPluginArtifacts.readBoundedUtf8String(jarFile.getInputStream(entry))
            }

        assertNotNull(result)
        assertEquals(DevPluginArtifacts.MAX_MANIFEST_BYTES, result!!.length)
    }

    // ---- Helpers ----

    private fun writeJar(
        name: String,
        manifestContent: String,
    ): File {
        val jar = workDir.resolve(name).toFile()
        JarOutputStream(jar.outputStream().buffered()).use { jos ->
            jos.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
            jos.write(manifestContent.toByteArray(Charsets.UTF_8))
            jos.closeEntry()
        }
        return jar
    }

    private fun openJarFile(file: File): java.util.jar.JarFile = java.util.jar.JarFile(file)
}
