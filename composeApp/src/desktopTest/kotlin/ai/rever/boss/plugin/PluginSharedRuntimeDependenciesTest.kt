package ai.rever.boss.plugin

import ai.rever.boss.plugin.loader.PluginClassLoader
import ai.rever.boss.plugin.loader.PluginClassLoaderManager
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class PluginSharedRuntimeDependenciesTest {
    @Test
    fun `bundled Ktor wins and new library fallback is refused after close`() {
        val parent = javaClass.classLoader
        val name = "io.ktor.util.reflect.TypeInfo"
        val path = name.replace('.', '/') + ".class"
        val jar = Files.createTempFile("plugin-ktor", ".jar")
        try {
            JarOutputStream(Files.newOutputStream(jar)).use { output ->
                output.putNextEntry(JarEntry(path))
                requireNotNull(parent.getResourceAsStream(path)).use { it.copyTo(output) }
                output.closeEntry()
            }
            val loader = PluginClassLoader("test.bundled-runtime", arrayOf(jar.toUri().toURL()), parent)
            loader.use { assertSame(loader, it.loadClass(name).classLoader) }
            assertFailsWith<ClassNotFoundException> { loader.loadClass("io.ktor.client.HttpClient") }
        } finally {
            Files.deleteIfExists(jar)
        }
    }

    @Test
    fun `thin plugins resolve their networking and icon libraries from the host`() {
        val parent = javaClass.classLoader
        PluginClassLoader("test.shared-runtime", emptyArray(), parent).use { loader ->
            listOf(
                "io.github.jan.supabase.SupabaseClientBuilder",
                "io.ktor.client.HttpClient",
                "io.ktor.client.engine.cio.CIO",
                "compose.icons.FeatherIcons",
            ).forEach { name ->
                assertSame(Class.forName(name, false, parent), loader.loadClass(name), name)
            }
        }
    }

    @Test
    fun `shared libraries do not restore access to host implementation classes`() {
        PluginClassLoader("test.shared-boundary", emptyArray(), javaClass.classLoader).use { loader ->
            assertFailsWith<ClassNotFoundException> {
                loader.loadClass(PluginClassLoaderManager::class.java.name)
            }
            listOf("io.ktorx.Hidden", "io.github.jan.supabasex.Hidden", "compose.iconsx.Hidden")
                .forEach { name ->
                    val parent =
                        object : ClassLoader(javaClass.classLoader) {
                            override fun loadClass(name: String): Class<*> = Unit::class.java
                        }
                    PluginClassLoader("test.prefix-boundary", emptyArray(), parent).use { isolated ->
                        assertFailsWith<ClassNotFoundException>(name) { isolated.loadClass(name) }
                    }
                }
        }
    }
}
