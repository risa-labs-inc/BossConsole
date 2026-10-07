package ai.rever.boss.components.plugin

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PluginUpdateProcessRegistryTest {
    @Test
    fun `concurrent callers share properties and only one string owner claim`() {
        val key = PluginUpdateProcessRegistry.ownerKey("fixture-${UUID.randomUUID()}")
        val workers = 8
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val futures =
                List(workers) {
                    pool.submit(
                        Callable {
                            ready.countDown()
                            check(start.await(10, TimeUnit.SECONDS)) { "Bootstrap start timed out" }
                            val owners = PluginUpdateProcessRegistry.owners()
                            val token = UUID.randomUUID().toString()
                            Triple(owners, token, owners.putIfAbsent(key, token) == null)
                        },
                    )
                }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Bootstrap workers did not become ready")
            start.countDown()
            val results = futures.map { it.get(10, TimeUnit.SECONDS) }
            val owners = results.first().first
            results.forEach { assertSame(owners, it.first) }
            val winners = results.filter { it.third }
            assertEquals(1, winners.size, "Only one concurrent caller may claim the owner")
            val winner = winners.single()
            assertSame(winner.second, owners[key])
            assertTrue(owners.remove(key, winner.second))
        } finally {
            start.countDown()
            pool.shutdownNow()
            // Remove only this fixture key after all callers stop using it.
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "Bootstrap workers did not finish")
            val owners = PluginUpdateProcessRegistry.owners()
            val token = owners[key]
            if (token != null) owners.remove(key, token)
        }
    }

    @Test
    fun `independent classloader copies share string properties without retaining plugin state`() {
        val owners = PluginUpdateProcessRegistry.owners()
        val type = PluginUpdateProcessRegistry::class.java
        val bytes = type.getResourceAsStream("/${type.name.replace('.', '/')}.class")!!.use { it.readBytes() }
        val loader =
            object : ClassLoader(type.classLoader) {
                override fun loadClass(
                    name: String,
                    resolve: Boolean,
                ): Class<*> =
                    if (name == type.name) {
                        synchronized(getClassLoadingLock(name)) {
                            (findLoadedClass(name) ?: defineClass(name, bytes, 0, bytes.size)).also {
                                if (resolve) resolveClass(it)
                            }
                        }
                    } else {
                        super.loadClass(name, resolve)
                    }
            }
        val copy = loader.loadClass(type.name)
        assertTrue(copy !== type)
        val instance = copy.getField("INSTANCE").get(null)
        assertSame(owners, copy.getMethod("owners").invoke(instance))
        val path = "fixture-${UUID.randomUUID()}"
        val key = PluginUpdateProcessRegistry.ownerKey(path)
        assertEquals(key, copy.getMethod("ownerKey", String::class.java).invoke(instance, path))
        val token = UUID.randomUUID().toString()
        assertEquals(null, owners.putIfAbsent(key, token))
        try {
            assertIs<String>(owners[key])
            assertEquals(null, owners[key]!!.javaClass.classLoader)
            owners.store(StringWriter(), "test")
            owners.list(PrintWriter(StringWriter()))
        } finally {
            owners.remove(key, token)
        }
    }

    @Test
    fun `foreign owner remains busy and malformed isolated properties fail closed`() {
        val owners = PluginUpdateProcessRegistry.owners()
        val foreign = "foreign owner"
        val directory = Files.createTempDirectory("plugin-update-foreign-owner").toFile()
        val hash =
            MessageDigest
                .getInstance("SHA-256")
                .digest("plugin".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        val path = File(directory, ".plugin-update-locks/$hash.lock").canonicalPath
        val key = PluginUpdateProcessRegistry.ownerKey(path)
        assertEquals(null, owners.putIfAbsent(key, foreign))
        try {
            val result =
                PluginUpdateLease.acquire(
                    directory,
                    "plugin",
                    openChannel = { error("Busy property must reject before opening a channel") },
                )
            assertIs<PluginUpdateLeaseBusyException>(result.exceptionOrNull())
            assertSame(foreign, owners[key])
        } finally {
            owners.remove(key, foreign)
            directory.deleteRecursively()
        }

        // Malformed entries stay local so parallel system-property consumers remain safe.
        val isolated = Properties()
        val malformed = Any()
        val candidate = UUID.randomUUID().toString()
        isolated[key] = malformed
        assertSame(malformed, isolated.putIfAbsent(key, candidate))
        assertSame(malformed, isolated[key])
        assertTrue(!isolated.remove(key, candidate))
        assertSame(malformed, isolated[key])
        assertTrue(isolated.remove(key, malformed))
    }
}
