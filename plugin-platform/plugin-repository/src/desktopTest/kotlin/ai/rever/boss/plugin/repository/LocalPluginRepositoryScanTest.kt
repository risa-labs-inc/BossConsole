package ai.rever.boss.plugin.repository

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The local repository scan is the shared path that turns files under the
 * plugins directory into loadable plugins. A symlinked or escaping entry must
 * be skipped, never loaded - see card v10.
 */
class LocalPluginRepositoryScanTest {
    @TempDir
    lateinit var temporary: File

    private val isWindows: Boolean
        get() = System.getProperty("os.name").lowercase().contains("win")

    private fun pluginDir(): File = File(temporary, "plugins").apply { mkdirs() }

    private fun manifestJar(
        dir: File,
        fileName: String,
        pluginId: String,
        version: String = "1.0.0",
        payloadBytes: Int = 0,
    ): File {
        val jar = File(dir, fileName)
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
            out.write(
                """
                {
                  "manifestVersion": 1,
                  "pluginId": "$pluginId",
                  "displayName": "Scan Guard Test",
                  "version": "$version",
                  "apiVersion": "1.0.0",
                  "mainClass": "com.example.Missing"
                }
                """.trimIndent().toByteArray(),
            )
            out.closeEntry()
            if (payloadBytes > 0) {
                out.putNextEntry(JarEntry("payload.bin"))
                out.write(ByteArray(payloadBytes) { (it % 251).toByte() })
                out.closeEntry()
            }
        }
        return jar
    }

    @Test
    fun `listPlugins skips a jar symlink escaping the plugins root`() =
        runTest {
            assumeTrue(!isWindows, "Symlink creation is not available on Windows CI")

            val dir = pluginDir()
            val real = manifestJar(dir, "real-plugin-1.0.0.jar", "ai.rever.test.real")
            val outside = manifestJar(temporary, "evil.jar", "ai.rever.test.evil")
            Files.createSymbolicLink(File(dir, "evil.jar").toPath(), outside.toPath())

            val plugins = LocalPluginRepository(dir).listPlugins().getOrThrow()

            assertEquals(
                listOf("ai.rever.test.real"),
                plugins.map { it.pluginId },
                "the symlinked jar must be skipped, not scanned",
            )
            assertTrue(real.exists())
        }

    @Test
    fun `listPlugins reports nothing when the plugins dir itself is a symlink`() =
        runTest {
            assumeTrue(!isWindows, "Symlink creation is not available on Windows CI")

            val real = File(temporary, "real-plugins").apply { mkdirs() }
            manifestJar(real, "plugin-1.0.0.jar", "ai.rever.test.hidden")
            val link = File(temporary, "plugins")
            Files.createSymbolicLink(link.toPath(), real.toPath())

            val repository = LocalPluginRepository(link)

            assertFalse(repository.isAvailable, "a symlinked plugins dir must not count as available")
            assertTrue(repository.listPlugins().getOrThrow().isEmpty())
        }

    @Test
    fun `listPlugins returns in-root jars`() =
        runTest {
            val dir = pluginDir()
            manifestJar(dir, "one-1.0.0.jar", "ai.rever.test.one")
            manifestJar(dir, "two-1.0.0.jar", "ai.rever.test.two")

            val plugins = LocalPluginRepository(dir).listPlugins().getOrThrow()

            assertEquals(
                setOf("ai.rever.test.one", "ai.rever.test.two"),
                plugins.map { it.pluginId }.toSet(),
            )
        }

    @Test
    fun `downloadPlugin refuses to copy a jar reachable only through a symlink`() =
        runTest {
            assumeTrue(!isWindows, "Symlink creation is not available on Windows CI")

            val dir = pluginDir()
            val outside = manifestJar(temporary, "evil.jar", "ai.rever.test.evil")
            Files.createSymbolicLink(File(dir, "evil.jar").toPath(), outside.toPath())

            val result =
                LocalPluginRepository(dir)
                    .downloadPlugin("ai.rever.test.evil", null, File(temporary, "copied.jar").absolutePath)

            assertTrue(result.isFailure, "a symlinked source must not be found or copied")
        }

    @Test
    fun `downloadPlugin copies an in-root jar to the target`() =
        runTest {
            val dir = pluginDir()
            manifestJar(dir, "real-1.0.0.jar", "ai.rever.test.real")
            val target = File(temporary, "target.jar")

            val result =
                LocalPluginRepository(dir)
                    .downloadPlugin("ai.rever.test.real", null, target.absolutePath)

            assertTrue(result.isSuccess)
            val copied = assertNotNull(result.getOrNull())
            assertEquals(target.absolutePath, copied)
            assertTrue(target.exists())
        }

    @Test
    fun `downloadPlugin verifies a jar larger than the digest buffer`() =
        runTest {
            val dir = pluginDir()
            // Well past the 64 KB streaming buffer in sha256Hex: the copy check
            // only passes if the digest is computed across buffer boundaries.
            val source = manifestJar(dir, "big-1.0.0.jar", "ai.rever.test.big", payloadBytes = 200 * 1024)
            val target = File(temporary, "big-copied.jar")

            val result =
                LocalPluginRepository(dir)
                    .downloadPlugin("ai.rever.test.big", null, target.absolutePath)

            assertTrue(result.isSuccess)
            assertTrue(
                source.readBytes().contentEquals(target.readBytes()),
                "a verified copy is byte-identical to its source",
            )
        }
}
