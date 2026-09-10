package ai.rever.boss.crash

import ai.rever.boss.crash.pluginprobe.PluginProbeJar
import ai.rever.boss.plugin.loader.PluginClassLoader
import ai.rever.boss.plugin.sandbox.PluginExecutionBoundary
import java.lang.reflect.InvocationTargetException
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
        const val PLUGIN_ID = PluginProbeJar.PLUGIN_ID

        /** Deliberately different from [PLUGIN_ID], so "the tag won" is distinguishable. */
        const val BOUNDARY_PLUGIN_ID = "test.plugin.boundary"
        const val PROBE_CLASS = PluginProbeJar.PROBE_CLASS
        const val EXCEPTION_CLASS = PluginProbeJar.EXCEPTION_CLASS
    }

    /** Shared with the context-menu wiring test; see [PluginProbeJar]. */
    private val probe = PluginProbeJar.open(javaClass.classLoader)
    private val loader get() = probe.loader

    @AfterTest
    fun tearDown() = probe.close()

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

    @Test
    fun `a callback owned by a real plugin classloader resolves to that plugin`() {
        // The assertion the sandbox-module test cannot make, and the one that
        // matters: PluginClassLoader declares `val pluginId` as a constructor
        // property, so the JVM gives it a PRIVATE backing field and a public
        // getPluginId(). A field-only reflective lookup returns null for every real
        // plugin - which shipped in the first version of PluginExecutionBoundary
        // and left every plugin callback unwrapped, so the crash it existed to
        // attribute would have terminated the app. The unit-test fixture hid it by
        // declaring @JvmField, the one form getField can see.
        val instance = loader.loadClass(PROBE_CLASS).getDeclaredConstructor().newInstance()

        assertEquals(PLUGIN_ID, PluginExecutionBoundary.pluginIdOfOwner(instance))
    }

    @Test
    fun `a lambda created inside plugin code resolves to that plugin`() {
        // Kotlin 2.x compiles a lambda to an invokedynamic call site backed by a
        // hidden class, and wrapPluginCallback depends on that hidden class
        // reporting its host's classloader - the plugin's. A compiler or -Xlambdas
        // change flipping that would make every plugin callback look host-owned and
        // silently un-attributed, with no visible failure until a session dies over
        // a plugin's bug. This is the callback shape the whole feature is built on:
        // ContextMenuItemData.onClick is exactly this.
        val action = probe.action("probeAction")

        assertEquals(PLUGIN_ID, PluginExecutionBoundary.pluginIdOfOwner(action))
    }

    @Test
    fun `the resolver main installs identifies a real plugin classloader`() {
        // The intersection nothing else covers: PluginExecutionBoundaryTest pins
        // resolver + fake loader, the tests above pin real loader + no resolver. A
        // typo in the `as?` cast would live exactly here - and because an installed
        // resolver's answer is final including null, that typo would silently make
        // every plugin callback unattributed rather than fail loudly.
        val resolver = hostPluginIdResolver()

        assertEquals(PLUGIN_ID, resolver(probe.loader))
        assertNull(resolver(javaClass.classLoader), "a host classloader is not a plugin")
    }

    @Test
    fun `a host-owned object resolves to no plugin`() {
        assertNull(PluginExecutionBoundary.pluginIdOfOwner(this))
    }

    @Test
    fun `a boundary tag attributes a crash whose stack holds no plugin frames`() {
        // The case the stack scan cannot answer, and the reason the boundary
        // exists: a plugin registers a callback, the HOST invokes it, and by the
        // time the uncaught handler looks there is nothing plugin-defined on the
        // stack. Only what was recorded on the way in still knows.
        val hostLookingCrash = RuntimeException("thrown through host frames only")
        assertNull(CrashHandler.attributePluginId(hostLookingCrash), "precondition: the stack blames nobody")

        PluginExecutionBoundary.tag(hostLookingCrash, BOUNDARY_PLUGIN_ID)

        assertEquals(BOUNDARY_PLUGIN_ID, CrashHandler.attributePluginId(hostLookingCrash))
    }

    @Test
    fun `a boundary tag outranks the stack scan`() {
        // Both sources have an answer. The tag was taken at the call the host
        // actually made; the stack merely shows whose code happened to be running,
        // which for a shared helper or a callback relayed between plugins is not
        // the same thing.
        val crash = throwFromPlugin("boom")
        PluginExecutionBoundary.tag(crash, BOUNDARY_PLUGIN_ID)

        assertEquals(BOUNDARY_PLUGIN_ID, CrashHandler.attributePluginId(crash))
    }

    @Test
    fun `a boundary tag outranks an explicitly captured scope`() {
        val crash = throwFromPlugin("boom")
        PluginExecutionBoundary.tag(crash, BOUNDARY_PLUGIN_ID)

        assertEquals(BOUNDARY_PLUGIN_ID, CrashHandler.attributePluginId(crash, "other.plugin.scope"))
    }

    @Test
    fun `captured scope outranks plugin frames`() {
        val crash = throwFromPlugin("boom")
        assertEquals(BOUNDARY_PLUGIN_ID, CrashHandler.attributePluginId(crash, BOUNDARY_PLUGIN_ID))
    }

    @Test
    fun `captured null scans plugin frames without sampling the writer scope`() {
        val crash = throwFromPlugin("boom")
        PluginExecutionBoundary.runAttributed("writer.plugin") {
            assertEquals(PLUGIN_ID, CrashHandler.attributePluginId(crash, null))
        }
    }

    @Test
    fun `captured null leaves a host fault unattributed despite the writer scope`() {
        PluginExecutionBoundary.runAttributed("writer.plugin") {
            assertNull(CrashHandler.attributePluginId(RuntimeException("host fault"), null))
        }
    }
}
