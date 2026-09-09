package ai.rever.boss.plugin

import ai.rever.boss.plugin.loader.PluginManifestException
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeferredPluginManifestTest {
    @TempDir
    lateinit var dir: File

    private fun jar(
        id: String,
        version: String = "2.3.4",
    ): String {
        val file = File(dir, "download.jar")
        JarOutputStream(file.outputStream()).use { out ->
            out.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
            out.write(
                """
                {"manifestVersion":1,"pluginId":"$id","displayName":"Browser",
                 "version":"$version","apiVersion":"1.0.0","mainClass":"example.Plugin"}
                """.trimIndent().toByteArray(),
            )
            out.closeEntry()
        }
        return file.absolutePath
    }

    @Test
    fun `a deferred update records the manifest version`() {
        assertEquals("2.3.4", readDeferredPluginManifest("example.browser", jar("example.browser")).version)
    }

    @Test
    fun `a deferred unrecognized version is refused`() {
        assertFailsWith<PluginManifestException> {
            readDeferredPluginManifest("example.browser", jar("example.browser", "dev"))
        }
    }

    @Test
    fun `a deferred jar cannot lose to a newer sibling at startup`() {
        File(jar("example.browser", "9.0.0")).renameTo(File(dir, "newer.jar"))
        assertFailsWith<IllegalArgumentException> {
            readDeferredPluginManifest("example.browser", jar("example.browser", "2.3.4"))
        }
    }

    @Test
    fun `another plugin's jar cannot be recorded as a browser update`() {
        assertFailsWith<IllegalArgumentException> {
            readDeferredPluginManifest("example.browser", jar("example.other"))
        }
    }
}
