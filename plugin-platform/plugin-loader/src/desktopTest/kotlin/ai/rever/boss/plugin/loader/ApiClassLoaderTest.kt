package ai.rever.boss.plugin.loader

import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Verifies the runtime-updatable API layer semantics:
 *
 * - A type present only in the api jar resolves through the ApiClassLoader
 *   with ONE Class identity shared by all plugin classloaders (the property
 *   cross-plugin `getPluginAPI` depends on).
 * - A type the parent (host) already has wins parent-first — identity with
 *   host-side implementations is preserved.
 * - [ApiClassLoader.fromPluginDir] picks the highest-version api jar by
 *   semver, ignores non-plugin jars, and degrades to an empty loader.
 * - The manager's disposeAll/close lifecycle never touches the api layer.
 */
class ApiClassLoaderTest {
    private val tempFiles = mutableListOf<File>()

    private val jarOnlyTypeName = "ai.rever.boss.plugin.api.apitest.JarOnlyType"

    /** Parent with no application classes: simulates a host that lacks the type. */
    private val bareParent: ClassLoader = ClassLoader.getPlatformClassLoader()

    /** Parent that HAS the type (the test classpath): simulates the host owning it. */
    private val hostLikeParent: ClassLoader = javaClass.classLoader

    @kotlin.test.BeforeTest
    fun resetSharedState() {
        // The ApiClassLoader install is process-wide (shared across manager
        // instances by design); isolate tests from each other.
        PluginClassLoaderManager.resetSharedApiLayerForTests()
    }

    @AfterTest
    fun cleanup() {
        PluginClassLoaderManager.resetSharedApiLayerForTests()
        tempFiles.forEach { it.deleteRecursively() }
    }

    private fun tempDir(): File =
        File.createTempFile("api-cl-test", "").let {
            it.delete()
            it.mkdirs()
            tempFiles.add(it)
            it
        }

    private fun emptyJar(
        dir: File? = null,
        name: String = "empty.jar",
    ): File {
        val jar = if (dir != null) File(dir, name) else File.createTempFile("empty", ".jar").also { tempFiles.add(it) }
        JarOutputStream(jar.outputStream()).close()
        return jar
    }

    /** Build a synthetic boss-plugin-api jar carrying JarOnlyType's real bytes. */
    private fun apiJar(
        dir: File,
        version: String,
        pluginId: String = ApiClassLoader.API_PLUGIN_ID,
        includeClass: Boolean = true,
    ): File {
        val jar = File(dir, "boss-plugin-api-$version.jar")
        val manifest =
            Manifest().apply {
                mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
                mainAttributes[Attributes.Name.IMPLEMENTATION_VERSION] = version
            }
        JarOutputStream(jar.outputStream(), manifest).use { out ->
            out.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
            out.write(
                """
                {
                  "manifestVersion": 1,
                  "pluginId": "$pluginId",
                  "displayName": "API Test",
                  "version": "$version",
                  "apiVersion": "1.0.0",
                  "mainClass": "com.example.Main"
                }
                """.trimIndent().toByteArray(),
            )
            out.closeEntry()
            if (includeClass) {
                val resourcePath = jarOnlyTypeName.replace('.', '/') + ".class"
                val bytes = javaClass.classLoader.getResourceAsStream(resourcePath)!!.readBytes()
                out.putNextEntry(JarEntry(resourcePath))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return jar
    }

    private fun pluginLoader(
        pluginId: String,
        parent: ClassLoader,
    ): PluginClassLoader =
        PluginClassLoader(
            pluginId = pluginId,
            urls = arrayOf(emptyJar().toURI().toURL()),
            parent = parent,
        )

    @Test
    fun `api-jar-only type is unresolvable without the ApiClassLoader`() {
        val loader = pluginLoader("com.example.noapi", bareParent)
        assertFailsWith<ClassNotFoundException> { loader.loadClass(jarOnlyTypeName) }
    }

    @Test
    fun `api-jar-only type resolves with one identity across plugin classloaders`() {
        val dir = tempDir()
        val apiLoader = ApiClassLoader(apiJar(dir, "1.0.99").toURI().toURL(), bareParent)

        val pluginA = pluginLoader("com.example.a", apiLoader)
        val pluginB = pluginLoader("com.example.b", apiLoader)

        val fromA = pluginA.loadClass(jarOnlyTypeName)
        val fromB = pluginB.loadClass(jarOnlyTypeName)

        assertSame(fromA, fromB, "both plugins must see the same Class identity")
        assertSame(apiLoader, fromA.classLoader, "the ApiClassLoader must define the jar-only type")
    }

    @Test
    fun `type present in the host wins parent-first over the api jar`() {
        val dir = tempDir()
        // Parent (test classpath) has JarOnlyType; the jar carries a copy too.
        val apiLoader = ApiClassLoader(apiJar(dir, "1.0.99").toURI().toURL(), hostLikeParent)
        val plugin = pluginLoader("com.example.hostwins", apiLoader)

        val loaded = plugin.loadClass(jarOnlyTypeName)
        assertSame(
            Class.forName(jarOnlyTypeName, false, hostLikeParent),
            loaded,
            "host-compiled copy must shadow the api jar's copy",
        )
        assertSame(hostLikeParent, loaded.classLoader)
    }

    @Test
    fun `fromPluginDir picks the highest semver api jar`() {
        val dir = tempDir()
        apiJar(dir, "1.0.9")
        apiJar(dir, "1.0.10")
        emptyJar(dir, "not-a-plugin.jar")

        val loader = ApiClassLoader.fromPluginDir(dir, bareParent)
        assertEquals("1.0.10", loader.apiVersion, "1.0.10 > 1.0.9 by semver (not lexicographic)")
    }

    @Test
    fun `fromPluginDir ignores jars with a different pluginId`() {
        val dir = tempDir()
        apiJar(dir, "9.9.9", pluginId = "com.example.impostor")

        val loader = ApiClassLoader.fromPluginDir(dir, bareParent)
        assertNull(loader.apiVersion, "non-api plugin jars must not be selected")
    }

    @Test
    fun `fromPluginDir degrades to an empty loader when no api jar exists`() {
        val dir = tempDir()
        emptyJar(dir, "random.jar")

        val loader = ApiClassLoader.fromPluginDir(dir, bareParent)
        assertNull(loader.apiVersion)
        assertFailsWith<ClassNotFoundException> { loader.loadClass(jarOnlyTypeName) }
    }

    @Test
    fun `manager parents new plugin classloaders to the installed api layer`() {
        val dir = tempDir()
        val manager = PluginClassLoaderManager(parentClassLoader = bareParent)
        val installed = manager.initializeApiLayer(dir.also { apiJar(it, "1.0.42") })
        assertEquals("1.0.42", installed.apiVersion)

        val manifest =
            ai.rever.boss.plugin.api.PluginManifest(
                pluginId = "com.example.viaapi",
                displayName = "Test",
                version = "1.0.0",
                apiVersion = "1.0.0",
                mainClass = "com.example.Main",
            )
        val pluginCl = manager.createClassLoader(manifest, emptyJar().absolutePath)
        val loaded = pluginCl.loadClass(jarOnlyTypeName)
        assertSame(installed, loaded.classLoader)

        // Plugin unload/disposal must never close the api layer.
        manager.closeClassLoader(manifest.pluginId, pluginCl)
        manager.disposeAll()
        assertNotNull(manager.getApiClassLoader())
        assertSame(loaded, installed.loadClass(jarOnlyTypeName), "api layer stays usable after disposeAll")
    }

    @Test
    fun `initializeApiLayer is idempotent`() {
        val dir = tempDir()
        apiJar(dir, "1.0.1")
        val manager = PluginClassLoaderManager(parentClassLoader = bareParent)

        val first = manager.initializeApiLayer(dir)
        apiJar(dir, "1.0.2") // newer jar appears later (mid-session update)
        val second = manager.initializeApiLayer(dir)

        assertSame(first, second, "a second call must return the installed loader (updates apply on restart)")
        assertEquals("1.0.1", second.apiVersion)
    }

    @Test
    fun `swapApiLayer replaces the shared layer and closes the old loader`() {
        val dir = tempDir()
        apiJar(dir, "1.0.5")
        val manager = PluginClassLoaderManager(parentClassLoader = bareParent)
        val old = manager.initializeApiLayer(dir)
        assertEquals("1.0.5", old.apiVersion)

        // A plugin classloader existed and was closed (the orchestrator's
        // contract) before the newer api jar lands and the swap runs.
        val manifest =
            ai.rever.boss.plugin.api.PluginManifest(
                pluginId = "com.example.preswap",
                displayName = "Test",
                version = "1.0.0",
                apiVersion = "1.0.0",
                mainClass = "com.example.Main",
            )
        val pluginCl = manager.createClassLoader(manifest, emptyJar().absolutePath)
        assertSame(old, pluginCl.loadClass(jarOnlyTypeName).classLoader)
        manager.closeClassLoader(manifest.pluginId, pluginCl)

        apiJar(dir, "1.0.6")
        val fresh = manager.swapApiLayer(dir)

        assertEquals("1.0.6", fresh.apiVersion, "swap must resolve the newest jar")
        assertNotSame(old, fresh)
        assertSame(fresh, manager.getApiClassLoader(), "shared layer must point at the new loader")

        // New plugin classloaders parent to the NEW layer, and a second
        // manager instance (another window) sees it too.
        val postSwapCl = manager.createClassLoader(manifest, emptyJar().absolutePath)
        assertSame(fresh, postSwapCl.loadClass(jarOnlyTypeName).classLoader)
        assertSame(fresh, PluginClassLoaderManager(parentClassLoader = bareParent).getApiClassLoader())
        manager.closeClassLoader(manifest.pluginId, postSwapCl)
    }

    @Test
    fun `api layer is shared across manager instances (second-window gap)`() {
        val dir = tempDir()
        apiJar(dir, "1.0.7")

        // Window 1's manager resolves and installs the API layer.
        val window1 = PluginClassLoaderManager(parentClassLoader = bareParent)
        val installed = window1.initializeApiLayer(dir)

        // Window 2 builds its OWN manager and never calls initializeApiLayer
        // (the persisted-load once-flag gates it). It must still see the
        // installed layer, so its plugin classloaders get the shared parent
        // and its loader enforces the minApiVersion gate.
        val window2 = PluginClassLoaderManager(parentClassLoader = bareParent)
        assertSame(installed, window2.getApiClassLoader(), "window 2 must share window 1's API layer")

        val manifest =
            ai.rever.boss.plugin.api.PluginManifest(
                pluginId = "com.example.window2",
                displayName = "Test",
                version = "1.0.0",
                apiVersion = "1.0.0",
                mainClass = "com.example.Main",
            )
        val pluginCl = window2.createClassLoader(manifest, emptyJar().absolutePath)
        assertSame(
            installed,
            pluginCl.loadClass(jarOnlyTypeName).classLoader,
            "a plugin loaded by window 2 must resolve api-jar types via the shared layer",
        )
    }
}
