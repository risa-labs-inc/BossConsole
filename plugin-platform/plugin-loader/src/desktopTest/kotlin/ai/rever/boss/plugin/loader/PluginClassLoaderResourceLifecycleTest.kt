package ai.rever.boss.plugin.loader

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogEntry
import ai.rever.boss.plugin.logging.LogLevel
import ai.rever.boss.plugin.logging.LogListener
import org.junit.jupiter.api.io.TempDir
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.Collections
import java.util.ServiceLoader
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

interface ResourceProbeService {
    fun source(): String
}

class PluginResourceProvider : ResourceProbeService {
    override fun source(): String = "plugin"
}

class HostResourceProvider : ResourceProbeService {
    override fun source(): String = "host"
}

/** Real colliding JAR resources, including service descriptors consumed by ServiceLoader. */
class PluginClassLoaderResourceLifecycleTest {
    @TempDir
    lateinit var tempDir: Path

    private val servicePath = "META-INF/services/${ResourceProbeService::class.java.name}"
    private val manifestPath = "META-INF/boss-plugin/plugin.json"
    private val sharedPath = "kotlin/resource-probe.txt"
    private val hostOnlyPath = "composeResources/probe/host-only.txt"

    @Test
    fun `active resources remain child first with parent fallback`() {
        withLoader { loader ->
            assertEquals("plugin", contents(loader.getResource(manifestPath)))
            assertEquals(listOf("plugin", "host"), contents(loader.getResources(manifestPath)))
            assertEquals("host-only", contents(loader.getResource(hostOnlyPath)))
            assertEquals(listOf("host-only"), contents(loader.getResources(hostOnlyPath)))
        }
    }

    @Test
    fun `unloading keeps own resources but excludes parent resources`() {
        withLoader { loader ->
            loader.markUnloading()
            assertEquals("plugin", contents(loader.getResource(manifestPath)))
            assertEquals(listOf("plugin"), contents(loader.getResources(manifestPath)))
            assertNull(loader.getResource(hostOnlyPath))
            assertTrue(Collections.list(loader.getResources(hostOnlyPath)).isEmpty())
        }
    }

    @Test
    fun `closed resource and stream lookups do not switch to the host copy`() {
        withLoader { loader ->
            assertEquals("plugin", contents(loader.getResource(manifestPath)))
            loader.close()
            repeat(3) {
                assertNull(loader.getResource(manifestPath))
                assertNull(loader.getResourceAsStream(manifestPath))
                assertNull(loader.getResource(hostOnlyPath))
            }
        }
    }

    @Test
    fun `closed enumeration excludes colliding host manifests and service descriptors`() {
        withLoader { loader ->
            loader.close()
            repeat(3) {
                assertTrue(Collections.list(loader.getResources(manifestPath)).isEmpty())
                assertTrue(Collections.list(loader.getResources(servicePath)).isEmpty())
            }
        }
    }

    @Test
    fun `service discovery retains the plugin during unloading and finds nothing after close`() {
        withLoader { loader ->
            val active = ServiceLoader.load(ResourceProbeService::class.java, loader).toList()
            assertEquals(listOf("plugin", "host"), active.map { it.source() })
            assertSame(loader, active.first().javaClass.classLoader)

            loader.markUnloading()
            val unloading = ServiceLoader.load(ResourceProbeService::class.java, loader).toList()
            assertEquals(listOf("plugin"), unloading.map { it.source() })

            loader.close()
            // A fresh discovery must not substitute the host provider. Existing
            // ServiceLoader instances can retain providers they already cached.
            assertTrue(ServiceLoader.load(ResourceProbeService::class.java, loader).toList().isEmpty())
        }
    }

    @Test
    fun `shared resources keep parent first delegation throughout teardown`() {
        withLoader { loader ->
            assertEquals("host-shared", contents(loader.getResource(sharedPath)))
            assertEquals(listOf("host-shared", "plugin-shared"), contents(loader.getResources(sharedPath)))
            loader.markUnloading()
            assertEquals("host-shared", contents(loader.getResource(sharedPath)))
            assertEquals(listOf("host-shared", "plugin-shared"), contents(loader.getResources(sharedPath)))
            loader.close()
            assertEquals("host-shared", contents(loader.getResource(sharedPath)))
            assertEquals(listOf("host-shared"), contents(loader.getResources(sharedPath)))
        }
    }

    @Test
    fun `missing resources keep the standard null and empty contracts`() {
        withLoader { loader ->
            for (transition in listOf({}, loader::markUnloading, loader::close)) {
                transition()
                assertNull(loader.getResource("missing-resource"))
                assertNull(loader.getResourceAsStream("missing-resource"))
                assertTrue(Collections.list(loader.getResources("missing-resource")).isEmpty())
            }
        }
    }

    @Test
    fun `active enumeration still removes duplicate parent URLs`() {
        val jar = jar("duplicate", mapOf(manifestPath to "plugin"))
        URLClassLoader(arrayOf(jar), javaClass.classLoader).use { parent ->
            PluginClassLoader("resource-dedup", arrayOf(jar), parent).use { loader ->
                assertEquals(listOf("plugin"), contents(loader.getResources(manifestPath)))
            }
        }
    }

    @Test
    fun `singular and plural refusals share one warning per resource per loader`() {
        val warnings = mutableListOf<LogEntry>()
        val listener =
            LogListener { entry ->
                if (entry.level == LogLevel.WARN && entry.data?.get("pluginId") == "resource-probe") {
                    warnings.add(entry)
                }
            }
        BossLogger.addListener(listener)
        try {
            repeat(2) {
                withLoader { loader ->
                    loader.markUnloading()
                    repeat(3) {
                        assertNull(loader.getResource(hostOnlyPath))
                        assertTrue(Collections.list(loader.getResources(hostOnlyPath)).isEmpty())
                    }
                    loader.close()
                    assertNull(loader.getResource(hostOnlyPath))
                }
            }
        } finally {
            BossLogger.removeListener(listener)
        }
        assertEquals(2, warnings.size)
        warnings.forEach {
            assertEquals(hostOnlyPath, it.data?.get("resourceName"))
            assertEquals(ClassLoaderState.UNLOAD_IN_PROGRESS.name, it.data?.get("state"))
            assertNotNull(it.error, "the first refusal should identify the late caller")
        }
    }

    private fun withLoader(block: (PluginClassLoader) -> Unit) {
        val pluginJar =
            jar(
                "plugin",
                mapOf(
                    manifestPath to "plugin",
                    sharedPath to "plugin-shared",
                    servicePath to PluginResourceProvider::class.java.name,
                ),
                PluginResourceProvider::class.java,
            )
        val hostJar =
            jar(
                "host",
                mapOf(
                    manifestPath to "host",
                    sharedPath to "host-shared",
                    servicePath to HostResourceProvider::class.java.name,
                    hostOnlyPath to "host-only",
                ),
            )
        URLClassLoader(arrayOf(hostJar), javaClass.classLoader).use { parent ->
            PluginClassLoader(
                "resource-probe",
                arrayOf(pluginJar),
                parent,
                PluginClassLoader.defaultSharedPackages + ResourceProbeService::class.java.name,
            ).use(block)
        }
    }

    private fun jar(
        name: String,
        resources: Map<String, String>,
        provider: Class<*>? = null,
    ): URL {
        val path =
            java.nio.file.Files
                .createTempFile(tempDir, name, ".jar")
        JarOutputStream(
            java.nio.file.Files
                .newOutputStream(path),
        ).use { output ->
            for ((resource, contents) in resources) {
                output.putNextEntry(JarEntry(resource))
                output.write(contents.toByteArray())
                output.closeEntry()
            }
            if (provider != null) {
                val resource = provider.name.replace('.', '/') + ".class"
                output.putNextEntry(JarEntry(resource))
                requireNotNull(javaClass.classLoader.getResourceAsStream(resource)).use { it.copyTo(output) }
                output.closeEntry()
            }
        }
        return path.toUri().toURL()
    }

    private fun contents(url: URL?): String {
        // Avoid URLConnection's global JAR cache retaining fixture files on Windows.
        val connection = assertNotNull(url).openConnection().apply { useCaches = false }
        return connection.getInputStream().bufferedReader().use { it.readText() }
    }

    private fun contents(urls: java.util.Enumeration<URL>): List<String> = Collections.list(urls).map { contents(it) }
}
