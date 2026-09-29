package ai.rever.boss.plugin.loader

import ai.rever.boss.plugin.api.PluginManifest
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Card v12 regression cover: parent delegation from a plugin classloader is
 * confined to [PluginClassLoader.defaultSharedPackages] plus manifest
 * `sharedPackages` entries contained inside that set. A plugin jar that names
 * a host class outside the shared surface - a credential holder, a process
 * handle, anything - must get ClassNotFoundException, not the host's copy.
 *
 * The names used below are all resolvable on the test classpath, so every
 * refusal is observable: without the sandbox they would return host classes.
 */
class PluginClassLoaderSandboxTest {
    private val tempJars = mutableListOf<File>()

    private val hostLoader: ClassLoader = PluginClassLoaderSandboxTest::class.java.classLoader

    @AfterTest
    fun cleanup() {
        tempJars.forEach { it.delete() }
    }

    private fun emptyJar(): File {
        val jar = File.createTempFile("plugin-cl-sandbox", ".jar")
        jar.deleteOnExit()
        tempJars.add(jar)
        JarOutputStream(jar.outputStream()).close()
        return jar
    }

    private fun manifest(
        pluginId: String,
        sharedPackages: List<String> = emptyList(),
    ) = PluginManifest(
        pluginId = pluginId,
        displayName = "Sandbox Test",
        version = "1.0.0",
        apiVersion = "1.0.0",
        mainClass = "com.example.Main",
        sharedPackages = sharedPackages,
    )

    @Test
    fun `a non-shared host class is refused while the loader is active`() {
        val loader =
            PluginClassLoader(
                pluginId = "com.example.sandbox",
                urls = arrayOf(emptyJar().toURI().toURL()),
                parent = hostLoader,
            )
        try {
            // PluginClassLoaderManager sits on the host classpath right here -
            // an unrestricted fallback would return it.
            assertFailsWith<ClassNotFoundException> {
                loader.loadClass(PluginClassLoaderManager::class.java.name)
            }
        } finally {
            loader.close()
        }
    }

    @Test
    fun `a shared host class still resolves parent first`() {
        val loader =
            PluginClassLoader(
                pluginId = "com.example.shared",
                urls = arrayOf(emptyJar().toURI().toURL()),
                parent = hostLoader,
            )
        try {
            assertSame(Unit::class.java, loader.loadClass("kotlin.Unit"))
        } finally {
            loader.close()
        }
    }

    @Test
    fun `rendering classes use the host identity even when bundled by a plugin`() {
        val names = listOf("org.jetbrains.skia.Image", "org.jetbrains.skiko.SkiaLayer")
        val jar = emptyJar()
        JarOutputStream(jar.outputStream()).use { output ->
            for (name in names) {
                val resource = name.replace('.', '/') + ".class"
                output.putNextEntry(JarEntry(resource))
                requireNotNull(hostLoader.getResourceAsStream(resource)).use { it.copyTo(output) }
                output.closeEntry()
            }
        }
        val manager = PluginClassLoaderManager(parentClassLoader = hostLoader)
        for (pluginJar in listOf(emptyJar(), jar)) {
            val id = "com.example.rendering"
            val loader = manager.createClassLoader(manifest(id), pluginJar.absolutePath)
            try {
                for (name in names) {
                    assertSame(hostLoader.loadClass(name), loader.loadClass(name))
                    val resource = name.replace('.', '/') + ".class"
                    assertEquals(
                        hostLoader.getResource(resource)?.toExternalForm(),
                        loader.getResource(resource)?.toExternalForm(),
                    )
                }
                // Prove the neighbouring class exists on the host before testing the fence.
                // A broad "org.jetbrains." shared prefix would incorrectly expose it.
                hostLoader.loadClass("org.jetbrains.annotations.NotNull")
                assertFailsWith<ClassNotFoundException> {
                    loader.loadClass("org.jetbrains.annotations.NotNull")
                }
                // Sharing the renderer must not open the rest of the host classpath.
                assertFailsWith<ClassNotFoundException> {
                    loader.loadClass(PluginClassLoaderManager::class.java.name)
                }
            } finally {
                manager.closeClassLoader(id, loader)
            }
        }
    }

    @Test
    fun `manifest sharedPackages outside the shared surface are dropped`() {
        val manager = PluginClassLoaderManager(parentClassLoader = hostLoader)
        // "org.junit" is on the test classpath but outside every shared root;
        // keeping it would let the plugin name any host class under it.
        val loader =
            manager.createClassLoader(
                manifest(
                    "com.example.dropped",
                    sharedPackages = listOf("org.junit", "ai.rever.boss.kernel"),
                ),
                emptyJar().absolutePath,
            )
        try {
            assertFailsWith<ClassNotFoundException> {
                loader.loadClass("org.junit.jupiter.api.Test")
            }
        } finally {
            manager.closeClassLoader("com.example.dropped", loader)
        }
    }

    @Test
    fun `manifest sharedPackages inside the shared surface are kept`() {
        val manager = PluginClassLoaderManager(parentClassLoader = hostLoader)
        val loader =
            manager.createClassLoader(
                manifest(
                    "com.example.kept",
                    sharedPackages = listOf("kotlinx.serialization.json"),
                ),
                emptyJar().absolutePath,
            )
        try {
            val loaded = loader.loadClass("kotlinx.serialization.json.Json")
            assertSame(
                kotlinx.serialization.json.Json::class.java,
                loaded,
                "an in-allowlist manifest package must still share the host's class",
            )
        } finally {
            manager.closeClassLoader("com.example.kept", loader)
        }
    }
}
