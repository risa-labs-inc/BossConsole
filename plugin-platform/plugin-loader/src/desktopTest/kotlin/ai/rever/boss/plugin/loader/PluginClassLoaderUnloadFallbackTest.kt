package ai.rever.boss.plugin.loader

import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Two classes that exist BOTH in the test's own classpath (so the host/parent
 * loader can supply them) and in the synthetic plugin jar these tests build (so
 * the plugin loader can define its own copy). That duplication is the whole
 * point: it is what makes the parent fallback observable — without the sandbox,
 * asking a plugin loader for [OwnedByPluginB] hands back the PARENT's copy
 * instead of failing, and after close it would splice two class graphs
 * together.
 *
 * They are deliberately dependency-free and live in a package that is NOT in
 * [PluginClassLoader.defaultSharedPackages], so they take the child-first path
 * and may not resolve against the host at all.
 */
class OwnedByPluginA

class OwnedByPluginB

/**
 * Regression cover for the silent parent fallback in
 * [PluginClassLoader.loadClassChildFirst].
 *
 * A closed [java.net.URLClassLoader] answers `findClass` with
 * ClassNotFoundException for every name — including classes its own jar
 * carries. The old catch block treated that as "the plugin doesn't have it" and
 * delegated to the host, which is how a terminal-tab hot-reload produced a
 * loader constraint LinkageError on `io/ktor/util/AttributeKey` between
 * `CIOApplicationEngine` (plugin loader) and `HttpRequestLifecycleKt` (loader
 * 'app') even though the plugin jar contained both.
 */
class PluginClassLoaderUnloadFallbackTest {
    private val tempJars = mutableListOf<File>()

    private val hostLoader: ClassLoader = PluginClassLoaderUnloadFallbackTest::class.java.classLoader

    @AfterTest
    fun cleanup() {
        tempJars.forEach { it.delete() }
    }

    /** Copy the given classes out of the test classpath into a fresh jar. */
    private fun jarContaining(vararg classes: Class<*>): File {
        val jar = File.createTempFile("plugin-cl-unload-test", ".jar")
        // Backstop for the cleanup(): a test that throws before its close()
        // leaves the jar locked on Windows, where delete() then silently fails.
        jar.deleteOnExit()
        tempJars.add(jar)
        JarOutputStream(jar.outputStream()).use { out ->
            for (cls in classes) {
                val path = cls.name.replace('.', '/') + ".class"
                val bytes =
                    requireNotNull(hostLoader.getResourceAsStream(path)) {
                        "test class $path missing from the test classpath"
                    }.use { it.readBytes() }
                out.putNextEntry(JarEntry(path))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return jar
    }

    private fun loaderOver(vararg classes: Class<*>): PluginClassLoader =
        PluginClassLoader(
            pluginId = PLUGIN_ID,
            urls = arrayOf(jarContaining(*classes).toURI().toURL()),
            parent = hostLoader,
        )

    // --- the legitimate case, which must keep working exactly as before ------

    @Test
    fun `an open loader refuses a host class outside the shared packages`() {
        val loader = loaderOver(OwnedByPluginA::class.java)

        // [OwnedByPluginB] is resolvable through the parent - it is right there
        // on the test classpath - but its package is not shared, so a plugin
        // must not reach it by name.
        assertFailsWith<ClassNotFoundException> {
            loader.loadClass(OwnedByPluginB::class.java.name)
        }
        loader.close()
    }

    @Test
    fun `an open loader defines its own copy of a class the jar carries`() {
        val loader = loaderOver(OwnedByPluginA::class.java)

        val resolved = loader.loadClass(OwnedByPluginA::class.java.name)

        assertSame(loader, resolved.classLoader, "child-first must win over the parent's copy")
        loader.close()
    }

    // --- the bug: delegation after teardown ----------------------------------

    @Test
    fun `a closed loader refuses to resolve a plugin class against the host`() {
        val loader = loaderOver(OwnedByPluginA::class.java, OwnedByPluginB::class.java)
        // Touch one class so the loader is genuinely live before it is closed.
        assertSame(loader, loader.loadClass(OwnedByPluginA::class.java.name).classLoader)

        loader.close()

        // OwnedByPluginB IS in the plugin jar, but the jar is shut. Falling back
        // would silently hand over the host's copy — the LinkageError machine.
        val failure =
            assertFailsWith<ClassNotFoundException> {
                loader.loadClass(OwnedByPluginB::class.java.name)
            }
        assertTrue(
            failure.message.orEmpty().contains(OwnedByPluginB::class.java.name),
            "refusal must name the class that was requested: ${failure.message}",
        )
        assertTrue(
            failure.message.orEmpty().contains(PLUGIN_ID),
            "refusal must name the plugin: ${failure.message}",
        )
        assertTrue(
            failure.message.orEmpty().contains(ClassLoaderState.UNLOADED.name),
            "refusal must name the loader state: ${failure.message}",
        )
        assertNotNull(failure.cause, "the underlying findClass miss is kept as the cause")

        // Logging of a refusal is deduped per class name; the refusal is not.
        // A retry loop must keep failing, not start succeeding on the 2nd call.
        assertFailsWith<ClassNotFoundException> {
            loader.loadClass(OwnedByPluginB::class.java.name)
        }
    }

    @Test
    fun `an unloading loader refuses to resolve a missing class against the host`() {
        val loader = loaderOver(OwnedByPluginA::class.java)

        // markUnloading only — the jar is still open, so this exercises the
        // UNLOAD_IN_PROGRESS state rather than the closed-jar one.
        loader.markUnloading()
        assertEquals(ClassLoaderState.UNLOAD_IN_PROGRESS, loader.state)

        assertFailsWith<ClassNotFoundException> {
            loader.loadClass(OwnedByPluginB::class.java.name)
        }
        loader.close()
    }

    // --- teardown must not be wedged by the refusal --------------------------

    @Test
    fun `an unloading loader still loads from its own jar`() {
        val loader = loaderOver(OwnedByPluginA::class.java)

        loader.markUnloading()

        // The jar is open until close(), so the plugin's own classes must still
        // resolve — what is refused is the parent FALLBACK, not loading itself.
        // Without this, a mutation that refuses everything post-ACTIVE before
        // even trying findClass would survive the suite.
        val resolved = loader.loadClass(OwnedByPluginA::class.java.name)
        assertSame(loader, resolved.classLoader, "the plugin's own jar must still answer")
        loader.close()
    }

    @Test
    fun `classes the loader already defined still resolve after close`() {
        val loader = loaderOver(OwnedByPluginA::class.java)
        val before = loader.loadClass(OwnedByPluginA::class.java.name)

        loader.close()

        assertSame(before, loader.loadClass(OwnedByPluginA::class.java.name))
    }

    @Test
    fun `an unloading loader keeps resolving shared host classes`() {
        val loader = loaderOver(OwnedByPluginA::class.java)

        loader.markUnloading()

        // kotlin.* is parent-first by [PluginClassLoader.defaultSharedPackages]:
        // teardown code runs on these, so the refusal must not touch them.
        assertSame(Unit::class.java, loader.loadClass("kotlin.Unit"))
        loader.close()
    }

    @Test
    fun `a closed loader keeps resolving shared host classes`() {
        val loader = loaderOver(OwnedByPluginA::class.java)

        loader.close()

        assertSame(Enum::class.java, loader.loadClass("java.lang.Enum"))
    }

    @Test
    fun `a class the plugin never carried is refused while open and after close`() {
        val loader = loaderOver(OwnedByPluginA::class.java)
        // Non-shared names are refused while ACTIVE, not just after teardown.
        assertFailsWith<ClassNotFoundException> {
            loader.loadClass(OwnedByPluginB::class.java.name)
        }

        loader.close()

        // ...and still refused once closed. This direct loadClass() call does
        // not register this loader as an initiating loader for B, so the
        // findLoadedClass early return does not cover it. In production a
        // JVM-resolved host class WOULD be registered and would keep resolving
        // after close — only first-time names reach the refusal.
        assertFailsWith<ClassNotFoundException> {
            loader.loadClass(OwnedByPluginB::class.java.name)
        }
    }

    private companion object {
        const val PLUGIN_ID = "com.example.unload"
    }
}
