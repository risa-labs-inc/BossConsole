package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeferredUpdateCleanupTest {
    @TempDir
    lateinit var dir: File

    private fun jar(
        name: String,
        id: String,
        version: String,
    ): File {
        val file = File(dir, name)
        JarOutputStream(file.outputStream()).use { out ->
            out.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
            out.write(
                """
                {"manifestVersion":1,"pluginId":"$id","displayName":"Fixture",
                 "version":"$version","apiVersion":"1.0.0","mainClass":"example.Plugin"}
                """.trimIndent().toByteArray(),
            )
            out.closeEntry()
        }
        return file
    }

    @Test
    fun `a live swap removes its old artifacts without sweeping a deferred plugin`() {
        val old = jar("old.jar", "example.regular", "1.0.0")
        val installed = jar("installed.jar", "example.regular", "2.0.0")
        val browser = jar("browser-old.jar", "example.browser", "1.0.0")
        jar("browser-new.jar", "example.browser", "2.0.0")
        PluginSignatureSidecar.write(old.absolutePath, "b2xk")

        PluginUpdateBridge.reconcileUpdatedPlugin(dir, "example.regular", deferred = false)

        assertFalse(old.exists())
        assertFalse(File(PluginSignatureSidecar.pathFor(old.absolutePath)).exists())
        assertTrue(installed.exists())
        assertTrue(browser.exists())
    }

    @Test
    fun `a deferred update keeps the running artifact`() {
        val old = jar("old.jar", "example.browser", "1.0.0")
        val staged = jar("staged.jar", "example.browser", "2.0.0")
        PluginUpdateBridge.reconcileUpdatedPlugin(dir, "example.browser", deferred = true)
        assertTrue(old.exists())
        assertTrue(staged.exists())
    }
}
