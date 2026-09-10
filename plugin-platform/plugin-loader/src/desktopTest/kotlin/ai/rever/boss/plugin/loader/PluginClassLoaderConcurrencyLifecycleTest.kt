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
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

open class ConcurrentPluginBase

class ConcurrentPluginChild : ConcurrentPluginBase()

class PluginClassLoaderConcurrencyLifecycleTest {
    @TempDir
    lateinit var tempDir: Path

    private val hostLoader = javaClass.classLoader

    @Test
    fun `shared names remain parent first under contention`() {
        PluginClassLoader(
            "shared-concurrent",
            arrayOf(pluginJar(ConcurrentPluginType::class.java).toUri().toURL()),
            hostLoader,
            sharedPackages = PluginClassLoader.defaultSharedPackages + ConcurrentPluginType::class.java.name,
        ).use { loader ->
            val pool = executor()
            try {
                val start = CyclicBarrier(2)
                val requests =
                    List(2) {
                        pool.submit(
                            Callable {
                                start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                                loader.loadClass(ConcurrentPluginType::class.java.name)
                            },
                        )
                    }
                requests.forEach {
                    assertSame(ConcurrentPluginType::class.java, it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                }
            } finally {
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `recursive superclass loading and a competing first load complete`() {
        val jar = pluginJar(ConcurrentPluginBase::class.java, ConcurrentPluginChild::class.java)
        PluginClassLoader("recursive-concurrent", arrayOf(jar.toUri().toURL()), hostLoader).use { loader ->
            val pool = executor()
            try {
                val start = CyclicBarrier(2)
                val requests =
                    listOf(ConcurrentPluginBase::class.java, ConcurrentPluginChild::class.java).map { type ->
                        pool.submit(
                            Callable {
                                start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                                loader.loadClass(type.name)
                            },
                        )
                    }
                val base = requests[0].get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                val child = requests[1].get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                assertSame(loader, base.classLoader)
                assertSame(loader, child.classLoader)
                assertSame(base, child.superclass)
            } finally {
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `unload marker refuses a waiting miss without draining an admitted parent lookup`() {
        val enteredParent = CountDownLatch(1)
        val releaseParent = CountDownLatch(1)
        val waitingStarted = CountDownLatch(1)
        val name = ParentOnlyConcurrentType::class.java.name
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
        PluginClassLoader("unloading-concurrent", emptyArray(), parent).use { loader ->
            val pool = executor()
            try {
                val admitted = pool.submit(Callable { loader.loadClass(name) })
                assertTrue(enteredParent.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                loader.markUnloading()
                val waiting =
                    pool.submit(
                        Callable {
                            waitingStarted.countDown()
                            runCatching { loader.loadClass(name) }
                        },
                    )
                assertTrue(waitingStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                releaseParent.countDown()
                assertSame(ParentOnlyConcurrentType::class.java, admitted.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                // Direct loadClass delegation does not register this plugin as an initiating
                // loader. The waiting call is still a miss and must recheck lifecycle state.
                assertIs<ClassNotFoundException>(waiting.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).exceptionOrNull())
            } finally {
                releaseParent.countDown()
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            }
        }
    }

    private fun executor() =
        Executors.newFixedThreadPool(2) { task ->
            Thread(task, "classloader-concurrency-test").apply { isDaemon = true }
        }

    private fun pluginJar(vararg types: Class<*>): Path {
        val jar = Files.createTempFile(tempDir, "concurrent-lifecycle-", ".jar")
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            types.forEach { type ->
                val path = type.name.replace('.', '/') + ".class"
                output.putNextEntry(JarEntry(path))
                requireNotNull(hostLoader.getResourceAsStream(path)).use { it.copyTo(output) }
                output.closeEntry()
            }
        }
        return jar
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
    }
}
