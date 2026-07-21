package ai.rever.boss.plugin.loader

import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Verifies the loader's minApiVersion gate: a plugin requiring a newer
 * runtime API layer than installed is rejected with the actionable
 * [PluginApiLevelException] (instead of downstream "class not found" noise),
 * with the same fail-open posture as minBossVersion.
 */
class MinApiVersionGateTest {

    private val tempJars = mutableListOf<File>()

    @kotlin.test.BeforeTest
    fun resetSharedState() {
        // currentApiVersion falls back to the process-wide ApiClassLoader;
        // clear it so other tests' installs don't leak into the gate checks.
        PluginClassLoaderManager.resetSharedApiLayerForTests()
    }

    @AfterTest
    fun cleanup() {
        PluginClassLoaderManager.resetSharedApiLayerForTests()
        tempJars.forEach { it.delete() }
    }

    /** Jar with only a manifest — enough to exercise the pre-classloader gates. */
    private fun manifestOnlyJar(minApiVersion: String): String {
        val jar = File.createTempFile("min-api-gate", ".jar")
        tempJars.add(jar)
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
            out.write(
                """
                {
                  "manifestVersion": 1,
                  "pluginId": "com.example.gate.${jar.nameWithoutExtension}",
                  "displayName": "Gate Test",
                  "version": "1.0.0",
                  "apiVersion": "1.0.0",
                  "mainClass": "com.example.Missing",
                  "minApiVersion": "$minApiVersion"
                }
                """.trimIndent().toByteArray()
            )
            out.closeEntry()
        }
        return jar.absolutePath
    }

    @Test
    fun `plugin requiring newer api layer is rejected with PluginApiLevelException`() = runBlocking<Unit> {
        val loader = DynamicPluginLoaderImpl().apply { currentApiVersion = "1.0.5" }

        val result = loader.loadPlugin(manifestOnlyJar(minApiVersion = "1.0.10"))

        val error = result.exceptionOrNull()
        assertIs<PluginApiLevelException>(error)
        assertTrue(error.message!!.contains("1.0.10") && error.message!!.contains("1.0.5"))
    }

    @Test
    fun `plugin whose requirement is satisfied passes the gate`() = runBlocking<Unit> {
        val loader = DynamicPluginLoaderImpl().apply { currentApiVersion = "1.0.10" }

        val result = loader.loadPlugin(manifestOnlyJar(minApiVersion = "1.0.10"))

        // Past the gate the load proceeds and fails later on the missing main
        // class — proving the minApiVersion check allowed it through.
        assertIs<PluginClassException>(result.exceptionOrNull())
    }

    @Test
    fun `gate fails open when currentApiVersion is not set`() = runBlocking<Unit> {
        val loader = DynamicPluginLoaderImpl() // currentApiVersion = null

        val result = loader.loadPlugin(manifestOnlyJar(minApiVersion = "1.0.10"))

        assertIs<PluginClassException>(result.exceptionOrNull())
    }

    @Test
    fun `gate fails open on unparseable requirement`() = runBlocking<Unit> {
        val loader = DynamicPluginLoaderImpl().apply { currentApiVersion = "1.0.5" }

        val result = loader.loadPlugin(manifestOnlyJar(minApiVersion = "not-a-version"))

        assertIs<PluginClassException>(result.exceptionOrNull())
    }
}
