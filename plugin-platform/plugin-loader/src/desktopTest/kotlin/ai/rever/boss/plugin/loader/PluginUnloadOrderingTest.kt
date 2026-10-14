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
        System.clearProperty(STATE_PROPERTY)
        System.clearProperty(FAIL_PROPERTY)
    }

    @AfterTest
    fun cleanup() {
        PluginClassLoaderManager.resetSharedApiLayerForTests()
        System.clearProperty(STATE_PROPERTY)
        System.clearProperty(FAIL_PROPERTY)
        tempJars.forEach { it.delete() }
    }

    /**
     * A jar carrying the fixture's own bytes: a plugin classloader refuses
     * non-shared names outright, so the manifest's mainClass must come from
     * the jar, not the test classpath.
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
            val classPath = OrderProbePlugin::class.java.name.replace('.', '/') + ".class"
            out.putNextEntry(JarEntry(classPath))
            requireNotNull(javaClass.classLoader.getResourceAsStream(classPath)) {
                "fixture class $classPath missing from the test classpath"
            }.use { it.copyTo(out) }
            out.closeEntry()
        }
        return jar.absolutePath
    }

    @Test
    fun `plugin dispose runs while its classloader is still ACTIVE`() =
        runBlocking<Unit> {
            val loader = DynamicPluginLoaderImpl()
            loader.loadPlugin(probePluginJar()).getOrThrow()

            loader.unloadPlugin(FIXTURE_ID).getOrThrow()

            assertEquals(
                ClassLoaderState.ACTIVE.name,
                System.getProperty(STATE_PROPERTY),
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
            System.setProperty(FAIL_PROPERTY, "true")

            loader.unloadPlugin(FIXTURE_ID).getOrThrow()

            assertNull(loader.getPlugin(FIXTURE_ID))
            assertNull(loader.getClassLoaderManager().getClassLoader(FIXTURE_ID))
            assertEquals(ClassLoaderState.UNLOADED, classLoader.state)
        }

    private companion object {
        const val FIXTURE_ID = "com.example.unload.ordering"
    }
}

/**
 * System properties are the only state a plugin-loaded fixture can share with
 * the test. `const val` keeps them compile-time inlined, so the fixture's
 * bytecode never names a host test class.
 */
private const val STATE_PROPERTY = "boss.test.unloadOrder.state"
private const val FAIL_PROPERTY = "boss.test.unloadOrder.failDispose"

/**
 * Records the classloader state observed from inside `dispose()` through a
 * system property: this class runs inside the plugin classloader, so it may
 * only touch shared (java.*, kotlin.*, plugin-api) types - a direct reference
 * to a host test object would now be refused as non-shared. The property names
 * are `const val`, inlined at compile time, so reading them here does not load
 * the test class either.
 */
class OrderProbePlugin : Plugin {
    override val pluginId = "com.example.unload.ordering"
    override val displayName = "Unload Order Probe"

    override fun register(context: PluginContext) = Unit

    override fun dispose() {
        val loader = javaClass.classLoader
        // PluginClassLoader is host-internal, so read its state reflectively
        // rather than naming the type.
        val state =
            loader.javaClass
                .getMethod("getState")
                .invoke(loader)
                .toString()
        System.setProperty(STATE_PROPERTY, state)
        if (System.getProperty(FAIL_PROPERTY) == "true") {
            throw NoClassDefFoundError("missing disposal dependency")
        }
    }
}
