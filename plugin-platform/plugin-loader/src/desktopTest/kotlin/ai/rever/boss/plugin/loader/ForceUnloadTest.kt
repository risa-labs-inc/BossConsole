package ai.rever.boss.plugin.loader

import ai.rever.boss.plugin.api.Plugin
import ai.rever.boss.plugin.api.PluginContext
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The central fix of the hot-swap PR: `unloadPlugin(force = true)` must bypass
 * the loader-level `canUnload=false` protection (reload/upgrade/API-swap flows
 * force-unload system plugins), while a non-forced unload still refuses —
 * without force, a swap left the canUnload=false system plugins stranded on
 * the CLOSED old ApiClassLoader.
 */
class ForceUnloadTest {
    private val tempJars = mutableListOf<File>()

    @BeforeTest
    fun resetSharedState() {
        PluginClassLoaderManager.resetSharedApiLayerForTests()
    }

    @AfterTest
    fun cleanup() {
        PluginClassLoaderManager.resetSharedApiLayerForTests()
        tempJars.forEach { it.delete() }
    }

    private fun lockedPluginJar(): String {
        val jar = File.createTempFile("force-unload", ".jar")
        tempJars.add(jar)
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
            out.write(
                """
                {
                  "manifestVersion": 1,
                  "pluginId": "$FIXTURE_ID",
                  "displayName": "Locked Fixture",
                  "version": "1.0.0",
                  "apiVersion": "1.0.0",
                  "mainClass": "${LockedFixturePlugin::class.java.name}",
                  "systemPlugin": true,
                  "canUnload": false
                }
                """.trimIndent().toByteArray(),
            )
            out.closeEntry()
        }
        return jar.absolutePath
    }

    @Test
    fun `non-forced unload refuses a canUnload=false plugin`() =
        runBlocking<Unit> {
            val loader = DynamicPluginLoaderImpl()
            assertNotNull(loader.loadPlugin(lockedPluginJar()).getOrThrow())

            val result = loader.unloadPlugin(FIXTURE_ID)

            assertIs<PluginUnloadException>(result.exceptionOrNull())
            assertNotNull(loader.getPlugin(FIXTURE_ID), "plugin must remain loaded after refused unload")

            loader.unloadPlugin(FIXTURE_ID, force = true)
        }

    @Test
    fun `forced unload bypasses canUnload=false`() =
        runBlocking<Unit> {
            val loader = DynamicPluginLoaderImpl()
            assertNotNull(loader.loadPlugin(lockedPluginJar()).getOrThrow())

            val result = loader.unloadPlugin(FIXTURE_ID, force = true)

            assertTrue(result.isSuccess, "force unload failed: ${result.exceptionOrNull()}")
            assertNull(loader.getPlugin(FIXTURE_ID), "plugin must be gone after forced unload")
        }

    private companion object {
        const val FIXTURE_ID = "com.example.locked.fixture"
    }
}

/** Loadable fixture: the plugin classloader is parent-first, so the manifest's
 *  mainClass resolves to this test-classpath class. Top-level because the
 *  manifest validator rejects nested-class names (the `$`). */
class LockedFixturePlugin : Plugin {
    override val pluginId = "com.example.locked.fixture"
    override val displayName = "Locked Fixture"

    override fun register(context: PluginContext) {}
}
