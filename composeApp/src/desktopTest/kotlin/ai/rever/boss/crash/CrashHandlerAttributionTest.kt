package ai.rever.boss.crash

import ai.rever.boss.plugin.loader.PluginClassLoader
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for crash → plugin attribution: [CrashHandler.attributePluginId] and
 * the [PluginClassLoader] weak registry / defining-loader identity check.
 *
 * The fixture classes in [ai.rever.boss.crash.pluginprobe] are repackaged from
 * the test classpath into a temp jar and loaded through a REAL
 * PluginClassLoader (child-first for non-shared packages), so the throwables
 * they produce carry genuinely plugin-defined frames — no mocking.
 */
class CrashHandlerAttributionTest {

    private companion object {
        const val PLUGIN_ID = "test.plugin.probe"
        const val PROBE_CLASS = "ai.rever.boss.crash.pluginprobe.PluginProbe"
        const val EXCEPTION_CLASS = "ai.rever.boss.crash.pluginprobe.ProbeException"
    }

    private val jarFile: File = buildProbeJar()
    private val loader = PluginClassLoader(
        pluginId = PLUGIN_ID,
        urls = arrayOf(jarFile.toURI().toURL()),
        parent = javaClass.classLoader
    )

    @AfterTest
    fun tearDown() {
        loader.close()
        jarFile.delete()
    }

    /** Copy the fixture .class files from the test classpath into a jar. */
    private fun buildProbeJar(): File {
        val jar = File.createTempFile("plugin-probe", ".jar")
        JarOutputStream(jar.outputStream()).use { out ->
            for (className in listOf(PROBE_CLASS, EXCEPTION_CLASS)) {
                val resource = className.replace('.', '/') + ".class"
                val bytes = checkNotNull(javaClass.classLoader.getResourceAsStream(resource)) {
                    "fixture class $resource not on test classpath"
                }.use { it.readBytes() }
                out.putNextEntry(JarEntry(resource))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return jar
    }

    /** Invoke PluginProbe.<method>() via the plugin loader and return the cause. */
    private fun throwFromPlugin(method: String): Throwable {
        val cls = loader.loadClass(PROBE_CLASS)
        // The loader must have DEFINED its own copy (child-first), not delegated
        // to the test classpath — otherwise the fixture proves nothing.
        assertEquals(loader, cls.classLoader, "probe class should be defined by the plugin loader")
        val instance = cls.getDeclaredConstructor().newInstance()
        return try {
            cls.getMethod(method).invoke(instance)
            error("$method should have thrown")
        } catch (e: InvocationTargetException) {
            e.cause!!
        }
    }

    @Test
    fun `crash with plugin-defined stack frames attributes to the plugin`() {
        val crash = throwFromPlugin("boom") // IllegalStateException thrown inside plugin code
        assertEquals(PLUGIN_ID, CrashHandler.attributePluginId(crash))
    }

    @Test
    fun `plugin-defined exception class attributes even without walking frames`() {
        val crash = throwFromPlugin("boomCustom") // ProbeException is itself plugin-defined
        assertEquals(EXCEPTION_CLASS, crash.javaClass.name)
        assertEquals(PLUGIN_ID, CrashHandler.attributePluginId(crash))
    }

    @Test
    fun `wrapped plugin exception attributes via the cause chain`() {
        val wrapped = RuntimeException("host wrapper", throwFromPlugin("boom"))
        assertEquals(PLUGIN_ID, CrashHandler.attributePluginId(wrapped))
    }

    @Test
    fun `host-only crash attributes to nothing`() {
        assertNull(CrashHandler.attributePluginId(RuntimeException("host crash")))
    }

    @Test
    fun `self-referential cause chain terminates`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b) // cycle
        assertNull(CrashHandler.attributePluginId(a))
    }

    @Test
    fun `registry finds the defining plugin for a loaded class`() {
        loader.loadClass(PROBE_CLASS)
        assertEquals(PLUGIN_ID, PluginClassLoader.findPluginForClass(PROBE_CLASS))
    }

    @Test
    fun `shared parent-first classes are never attributed to a plugin`() {
        // Force the plugin loader to initiate loads that resolve parent-first.
        loader.loadClass(PROBE_CLASS)
        assertNull(PluginClassLoader.findPluginForClass("java.lang.String"))
        assertNull(PluginClassLoader.findPluginForClass("kotlin.Unit"))
        assertNull(PluginClassLoader.findPluginForClass(CrashHandler::class.java.name))
    }

    @Test
    fun `unknown class names are not attributed`() {
        assertNull(PluginClassLoader.findPluginForClass("com.example.DoesNotExist"))
    }

    @Test
    fun `attribution survives a closed loader`() {
        val crash = throwFromPlugin("boom")
        loader.close()
        // A crash caused by a just-unloaded plugin should still attribute.
        assertEquals(PLUGIN_ID, CrashHandler.attributePluginId(crash))
    }

    @Test
    fun `attribution prefers the root cause origin`() {
        // Root cause thrown in plugin code, wrapped twice by host layers: the
        // crash ORIGIN (deepest cause) must win.
        val wrapped = RuntimeException("outer host", IllegalStateException("mid host", throwFromPlugin("boom")))
        assertEquals(PLUGIN_ID, CrashHandler.attributePluginId(wrapped))
        assertTrue(wrapped.cause?.cause != null)
    }
}
