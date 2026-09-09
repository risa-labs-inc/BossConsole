package ai.rever.boss.plugin.loader

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Present in both the host and the synthetic plugin JAR, outside shared packages.
class ConcurrentPluginType

class ParentOnlyConcurrentType

class PluginClassLoaderConcurrencyTest {
    @TempDir
    lateinit var tempDir: Path

    private val hostLoader = javaClass.classLoader

    @Test
    fun `plugin loader registers as parallel capable`() {
        pluginLoader().use { loader ->
            assertTrue(loader.isRegisteredAsParallelCapable)
        }
    }

    @Test
    fun `concurrent first loads define one plugin class`() {
        val jar = pluginJar()
        val executor = Executors.newFixedThreadPool(WORKERS)
        try {
            // Fresh loaders keep every round a first load, rather than testing only the cache.
            repeat(ROUNDS) {
                PluginClassLoader("concurrent-$it", arrayOf(jar.toUri().toURL()), hostLoader).use { loader ->
                    val start = CyclicBarrier(WORKERS)
                    val loads =
                        List(WORKERS) {
                            executor.submit(
                                Callable {
                                    start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                                    loader.loadClass(ConcurrentPluginType::class.java.name)
                                },
                            )
                        }
                    // Drain every worker before closing the loader, including on a regression.
                    val results = loads.map { runCatching { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) } }
                    val classes = results.map { it.getOrThrow() }
                    val first = classes.first()
                    assertSame(loader, first.classLoader, "must define the plugin's copy, not the host's")
                    classes.forEach { assertSame(first, it) }
                    assertSame(first, loader.loadClass(first.name), "subsequent loads must reuse the class")
                }
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `waiting for a parent class does not block an unrelated plugin class`() {
        val enteredParent = CountDownLatch(1)
        val releaseParent = CountDownLatch(1)
        val parent =
            object : ClassLoader(hostLoader) {
                override fun loadClass(
                    name: String,
                    resolve: Boolean,
                ): Class<*> {
                    if (name == ParentOnlyConcurrentType::class.java.name) {
                        enteredParent.countDown()
                        check(releaseParent.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    }
                    return hostLoader.loadClass(name)
                }
            }
        pluginLoader(parent).use { loader ->
            val executor = Executors.newFixedThreadPool(2)
            try {
                val waiting = executor.submit(Callable { loader.loadClass(ParentOnlyConcurrentType::class.java.name) })
                assertTrue(enteredParent.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                val independent = executor.submit(Callable { loader.loadClass(ConcurrentPluginType::class.java.name) })
                assertSame(loader, independent.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).classLoader)
                releaseParent.countDown()
                assertSame(ParentOnlyConcurrentType::class.java, waiting.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            } finally {
                releaseParent.countDown()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            }
        }
    }

    private fun pluginLoader(parent: ClassLoader = hostLoader): PluginClassLoader =
        PluginClassLoader("concurrent-test", arrayOf(pluginJar().toUri().toURL()), parent)

    private fun pluginJar(): Path {
        val jar = Files.createTempFile(tempDir, "concurrent-plugin-", ".jar")
        val classPath = ConcurrentPluginType::class.java.name.replace('.', '/') + ".class"
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            output.putNextEntry(JarEntry(classPath))
            requireNotNull(hostLoader.getResourceAsStream(classPath)).use { it.copyTo(output) }
            output.closeEntry()
        }
        return jar
    }

    private companion object {
        const val WORKERS = 16
        const val ROUNDS = 20
        const val TIMEOUT_SECONDS = 10L
    }
}
