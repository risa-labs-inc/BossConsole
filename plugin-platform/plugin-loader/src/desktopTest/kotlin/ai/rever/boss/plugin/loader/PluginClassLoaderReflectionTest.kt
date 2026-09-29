package ai.rever.boss.plugin.loader

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ReflectionPluginFixture {
    @Suppress("FunctionOnlyReturningConstant") // A real method is needed to exercise generated reflection accessors.
    fun answer(): Int = 42
}

class PluginClassLoaderReflectionTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `repeated plugin reflection survives JDK accessor inflation`() {
        val parent = javaClass.classLoader
        val name = ReflectionPluginFixture::class.java.name
        val resource = name.replace('.', '/') + ".class"
        val jar = tempDir.resolve("reflection.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            output.putNextEntry(JarEntry(resource))
            requireNotNull(parent.getResourceAsStream(resource)).use { it.copyTo(output) }
            output.closeEntry()
        }
        PluginClassLoader("reflection-test", arrayOf(jar.toUri().toURL()), parent).use { loader ->
            val pluginClass = loader.loadClass(name)
            assertSame(loader, pluginClass.classLoader)
            val constructor = pluginClass.getConstructor()
            val method = pluginClass.getMethod("answer")
            // The first few calls use native reflection and miss the production failure.
            repeat(100) { assertEquals(42, method.invoke(constructor.newInstance())) }
            assertFailsWith<ClassNotFoundException> {
                loader.loadClass(PluginClassLoaderManager::class.java.name)
            }
            assertFailsWith<ClassNotFoundException> { loader.loadClass("jdk.internal.reflectx.Hidden") }
        }
    }
}
