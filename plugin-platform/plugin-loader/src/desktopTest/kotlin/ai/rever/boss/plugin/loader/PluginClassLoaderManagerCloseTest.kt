package ai.rever.boss.plugin.loader

import ai.rever.boss.plugin.api.PluginManifest
import java.io.File
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Verifies that [PluginClassLoaderManager.closeClassLoader] un-registers the
 * classloader from the active map. It used to only close() it, so any load
 * failure past classloader creation (binary-incompatible update, missing main
 * class, instantiation error) left a dead entry behind and every retry for
 * that pluginId threw "Classloader already exists" until app restart — this
 * masked the real load error for Toolbox 1.8.4 on BOSS 9.2.25.
 */
class PluginClassLoaderManagerCloseTest {

    private val tempJars = mutableListOf<File>()

    private fun emptyJar(): String {
        val jar = File.createTempFile("plugin-clm-test", ".jar")
        JarOutputStream(jar.outputStream()).close()
        tempJars.add(jar)
        return jar.absolutePath
    }

    private fun manifest(pluginId: String) = PluginManifest(
        pluginId = pluginId,
        displayName = "Test Plugin",
        version = "1.0.0",
        apiVersion = "1.0.0",
        mainClass = "com.example.Main"
    )

    @AfterTest
    fun cleanup() {
        tempJars.forEach { it.delete() }
    }

    @Test
    fun `closeClassLoader un-registers so the plugin can be loaded again`() {
        val manager = PluginClassLoaderManager()
        val manifest = manifest("com.example.retry")
        val jarPath = emptyJar()

        val first = manager.createClassLoader(manifest, jarPath)
        manager.closeClassLoader(manifest.pluginId, first)

        assertNull(manager.getClassLoader(manifest.pluginId), "closed classloader must not stay registered")

        // The retry path — used to throw "Classloader already exists".
        val second = manager.createClassLoader(manifest, jarPath)
        assertNotSame(first, second)
        manager.closeClassLoader(manifest.pluginId, second)
    }

    @Test
    fun `create still rejects a duplicate while the first is active`() {
        val manager = PluginClassLoaderManager()
        val manifest = manifest("com.example.active")
        val active = manager.createClassLoader(manifest, emptyJar())

        assertFailsWith<PluginLoadException> {
            manager.createClassLoader(manifest, emptyJar())
        }

        manager.closeClassLoader(manifest.pluginId, active)
    }

    @Test
    fun `closing a stale loader does not clobber a newer registration`() {
        val manager = PluginClassLoaderManager()
        val manifest = manifest("com.example.stale")

        val old = manager.createClassLoader(manifest, emptyJar())
        manager.closeClassLoader(manifest.pluginId, old)
        val newer = manager.createClassLoader(manifest, emptyJar())

        // A late close of the OLD loader must not evict the NEW one.
        manager.closeClassLoader(manifest.pluginId, old)
        assertSame(newer, manager.getClassLoader(manifest.pluginId))

        manager.closeClassLoader(manifest.pluginId, newer)
    }
}
