package ai.rever.boss.plugin.loader

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Regression coverage for duplicate class definitions during concurrent plugin startup. */
class PluginClassLoaderConcurrencyTest {
    private val tempJars = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tempJars.forEach { it.delete() }
    }

    @Test
    fun `concurrent loads of one plugin class return one definition`() {
        val hostLoader = PluginClassLoaderConcurrencyTest::class.java.classLoader
        val className = OwnedByPluginA::class.java.name
        val resource = className.replace('.', '/') + ".class"
        val jar = File.createTempFile("plugin-cl-concurrency-test", ".jar")
        tempJars += jar
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry(resource))
            hostLoader.getResourceAsStream(resource)!!.use { it.copyTo(out) }
            out.closeEntry()
        }

        val loader =
            PluginClassLoader(
                pluginId = "concurrency-test",
                urls = arrayOf(jar.toURI().toURL()),
                parent = hostLoader,
            )
        val workers = 16
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val futures = (0 until workers).map {
                pool.submit<Class<*>> {
                    ready.countDown()
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    loader.loadClass(className)
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()

            val loaded = futures.map { it.get(10, TimeUnit.SECONDS) }
            val distinctClasses = loaded.distinct()
            assertEquals(1, distinctClasses.size)
            assertSame(loader, distinctClasses.single().classLoader)
        } finally {
            pool.shutdownNow()
            loader.close()
        }
    }
}
