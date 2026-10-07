package ai.rever.boss.components.plugin

import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.management.ManagementFactory
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.management.ObjectName
import javax.management.modelmbean.RequiredModelMBean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PluginUpdateProcessRegistryTest {
    @Test
    fun `concurrent bootstrap callers share one registry and one owner claim`() {
        val name = ObjectName("boss.plugins.test:type=UpdateLeaseRegistry,fixture=${UUID.randomUUID()}")
        val server = ManagementFactory.getPlatformMBeanServer()
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
                            val owners = PluginUpdateProcessRegistry.owners(name)
                            val token = Any()
                            Triple(owners, token, owners.putIfAbsent("fixture-owner", token) == null)
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
            assertSame(winner.second, owners["fixture-owner"])
            assertTrue(owners.remove("fixture-owner", winner.second))
        } finally {
            start.countDown()
            pool.shutdownNow()
            // Never remove a registry while a fixture worker could still be using it.
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "Bootstrap workers did not finish")
            if (server.isRegistered(name)) server.unregisterMBean(name)
        }
    }

    @Test
    fun `independent classloader copies share bootstrap registry without property pollution`() {
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
        System.getProperties().store(StringWriter(), "test")
        System.getProperties().list(PrintWriter(StringWriter()))
        val info = ManagementFactory.getPlatformMBeanServer().getMBeanInfo(registryName)
        ObjectOutputStream(ByteArrayOutputStream()).use { it.writeObject(info) }
        assertTrue(info.attributes.isEmpty())
        assertTrue(
            info.operations
                .single()
                .signature
                .isEmpty(),
        )
    }

    @Test
    fun `foreign registry metadata and resource fail closed without replacing production bean`() {
        val owners = PluginUpdateProcessRegistry.owners()
        val server = ManagementFactory.getPlatformMBeanServer()
        val info = server.getMBeanInfo(registryName)
        for (wrongMetadata in listOf(true, false)) {
            val name = ObjectName("boss.plugins.test:type=UpdateLeaseRegistry,id=${UUID.randomUUID()}")
            val bean =
                if (wrongMetadata) {
                    RequiredModelMBean()
                } else {
                    RequiredModelMBean(info as javax.management.modelmbean.ModelMBeanInfo).also {
                        it.setManagedResource(AtomicReference("wrong resource"), "ObjectReference")
                    }
                }
            server.registerMBean(bean, name)
            try {
                assertFailsWith<IllegalStateException> { PluginUpdateProcessRegistry.owners(name) }
            } finally {
                server.unregisterMBean(name)
            }
        }
        assertSame(owners, PluginUpdateProcessRegistry.owners())
    }

    companion object {
        private val registryName = ObjectName("boss.plugins:type=UpdateLeaseRegistry,protocol=2")
    }
}
