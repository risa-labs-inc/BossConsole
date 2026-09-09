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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins the ordering invariant that [PluginClassLoader]'s refusal policy rests
 * on: a plugin's own `dispose()` runs while its classloader is still
 * [ClassLoaderState.ACTIVE].
 *
 * Since the loader refuses to resolve anything new against the host once the
 * state leaves ACTIVE, a "mark it dead before we touch it" refactor of
 * `unloadPlugin` would make every plugin's dispose() that lazily touches a host
 * class start throwing — surfacing as scattered NoClassDefFoundErrors rather
 * than a red test. This is that red test. The prose lives in
 * DynamicPluginLoader.unloadPlugin; the enforcement lives here.
 */
class PluginUnloadOrderingTest {
    private val tempJars = mutableListOf<File>()

    @BeforeTest
    fun resetSharedState() {
        PluginClassLoaderManager.resetSharedApiLayerForTests()
        UnloadOrderProbe.reset()
    }

    @AfterTest
    fun cleanup() {
        PluginClassLoaderManager.resetSharedApiLayerForTests()
        UnloadOrderProbe.reset()
        tempJars.forEach { it.delete() }
    }

    /**
     * A manifest-only jar. The plugin classloader misses on the mainClass and
     * (while ACTIVE) delegates to the test classpath, which is where
     * [OrderProbePlugin] lives — the same trick [ForceUnloadTest] uses.
     */
    private fun probePluginJar(): String {
        val jar = File.createTempFile("unload-ordering", ".jar")
        jar.deleteOnExit()
        tempJars.add(jar)
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
            out.write(
                """
                {
                  "manifestVersion": 1,
                  "pluginId": "$FIXTURE_ID",
                  "displayName": "Unload Order Probe",
                  "version": "1.0.0",
                  "apiVersion": "1.0.0",
                  "mainClass": "${OrderProbePlugin::class.java.name}"
                }
                """.trimIndent().toByteArray(),
            )
            out.closeEntry()
        }
        return jar.absolutePath
    }

    @Test
    fun `plugin dispose runs while its classloader is still ACTIVE`() =
        runBlocking<Unit> {
            val loader = DynamicPluginLoaderImpl()
            loader.loadPlugin(probePluginJar()).getOrThrow()

            UnloadOrderProbe.classLoader =
                assertNotNull(
                    loader.getClassLoaderManager().getClassLoader(FIXTURE_ID),
                    "fixture must have a classloader while loaded",
                )

            loader.unloadPlugin(FIXTURE_ID).getOrThrow()

            assertEquals(
                ClassLoaderState.ACTIVE,
                UnloadOrderProbe.stateAtDispose,
                "dispose() must run before the classloader is marked for unload - the refusal " +
                    "in PluginClassLoader.loadClassChildFirst assumes it",
            )
        }

    @Test
    fun `the classloader is unloaded by the time unloadPlugin returns`() =
        runBlocking<Unit> {
            val loader = DynamicPluginLoaderImpl()
            loader.loadPlugin(probePluginJar()).getOrThrow()
            val classLoader =
                assertNotNull(loader.getClassLoaderManager().getClassLoader(FIXTURE_ID))

            loader.unloadPlugin(FIXTURE_ID).getOrThrow()

            // The other end of the window: ACTIVE during dispose, UNLOADED after.
            assertEquals(ClassLoaderState.UNLOADED, classLoader.state)
        }

    @Test
    fun `a disposal linkage error does not strand the plugin or its loader`() =
        runBlocking<Unit> {
            val loader = DynamicPluginLoaderImpl()
            loader.loadPlugin(probePluginJar()).getOrThrow()
            val classLoader = assertNotNull(loader.getClassLoaderManager().getClassLoader(FIXTURE_ID))
            UnloadOrderProbe.disposalFailure = NoClassDefFoundError("missing disposal dependency")

            loader.unloadPlugin(FIXTURE_ID).getOrThrow()

            assertNull(loader.getPlugin(FIXTURE_ID))
            assertNull(loader.getClassLoaderManager().getClassLoader(FIXTURE_ID))
            assertEquals(ClassLoaderState.UNLOADED, classLoader.state)
        }

    private companion object {
        const val FIXTURE_ID = "com.example.unload.ordering"
    }
}

/** Cross-classloader handoff between the test and its fixture plugin. */
object UnloadOrderProbe {
    @Volatile var classLoader: PluginClassLoader? = null

    @Volatile var stateAtDispose: ClassLoaderState? = null

    @Volatile var disposalFailure: Throwable? = null

    fun reset() {
        disposalFailure = null
        classLoader = null
        stateAtDispose = null
    }
}

/** Records the classloader state observed from inside `dispose()`. */
class OrderProbePlugin : Plugin {
    override val pluginId = "com.example.unload.ordering"
    override val displayName = "Unload Order Probe"

    override fun register(context: PluginContext) = Unit

    override fun dispose() {
        UnloadOrderProbe.stateAtDispose = UnloadOrderProbe.classLoader?.state
        UnloadOrderProbe.disposalFailure?.let { throw it }
    }
}
