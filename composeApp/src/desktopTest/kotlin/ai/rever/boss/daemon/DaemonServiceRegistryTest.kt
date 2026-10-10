package ai.rever.boss.daemon

import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DaemonServiceRegistryTest {
    private val directory = Files.createTempDirectory("boss-daemon-test").toFile()
    private val registry = DaemonServiceRegistry(File(directory, "daemon"))

    @AfterTest
    fun cleanup() {
        registry.close()
        directory.deleteRecursively()
    }

    private fun artifact(): File {
        val jar = File(directory, "plugin.jar")
        val prefix = "fixtures/daemon/"
        val source =
            File(
                javaClass.protectionDomain.codeSource.location
                    .toURI(),
            )
        JarOutputStream(jar.outputStream()).use { out ->
            File(source, prefix).walkTopDown().filter { it.isFile }.forEach { file ->
                out.putNextEntry(JarEntry(file.relativeTo(source).invariantSeparatorsPath))
                out.write(file.readBytes())
                out.closeEntry()
            }
            out.putNextEntry(JarEntry("worker-resource.txt"))
            out.write("snapshot-resource".toByteArray())
            out.closeEntry()
        }
        return jar
    }

    private fun connect(
        plugin: String = "plugin.one",
        service: String = "worker",
        jar: File = artifact(),
        config: Map<String, String> = emptyMap(),
    ) = DaemonRequest(
        "",
        "connect",
        plugin,
        service,
        jar.absolutePath,
        sha256(jar),
        "fixtures.daemon.WorkerFixture",
        config,
    )

    private fun request(
        plugin: String,
        method: String,
        payload: String = "",
    ) = registry
        .dispatch(
            DaemonRequest(
                "",
                "request",
                plugin,
                "worker",
                method = method,
                payload = payload,
                instanceId = registry.dispatch(connect(plugin = plugin)).endpoints.getValue("boss.service.instanceId"),
            ),
        ).payload

    @Test
    fun `same service id is isolated by plugin and dropping a connection does not stop it`() {
        val first = registry.dispatch(connect())
        val second = registry.dispatch(connect(plugin = "plugin.two"))
        assertTrue(first.endpoints != second.endpoints)
        assertEquals("one", request("plugin.one", "write", "one"))
        assertEquals("two", request("plugin.two", "write", "two"))
        assertEquals("one", request("plugin.one", "read"))
        assertEquals(2, registry.count())
        assertEquals(first.endpoints, registry.dispatch(connect()).endpoints)
    }

    @Test
    fun `stale handles cannot request or stop a replacement worker`() {
        val descriptor = connect()
        val old = registry.dispatch(descriptor).endpoints.getValue("boss.service.instanceId")
        val stop = DaemonRequest("", "stop", "plugin.one", "worker", instanceId = old)
        registry.dispatch(stop)
        registry.dispatch(stop) // Idempotent after the original worker is gone.
        val fresh = registry.dispatch(descriptor).endpoints.getValue("boss.service.instanceId")
        assertNotEquals(old, fresh)
        assertFailsWith<IllegalStateException> {
            registry.dispatch(
                DaemonRequest(
                    "",
                    "request",
                    "plugin.one",
                    "worker",
                    method = "write",
                    payload = "stale",
                    instanceId = old,
                ),
            )
        }
        registry.dispatch(stop)
        assertEquals(1, registry.count())
        assertEquals(fresh, registry.dispatch(descriptor).endpoints.getValue("boss.service.instanceId"))
        assertEquals("fresh", request("plugin.one", "write", "fresh"))
        registry.close()
        val restored = DaemonServiceRegistry(File(directory, "daemon"))
        try {
            restored.restore()
            assertEquals(1, restored.count(), "Stale stop must preserve the replacement registration")
        } finally {
            restored.close()
        }
    }

    @Test
    fun `requests without an instance are refused`() {
        registry.dispatch(connect())
        assertFailsWith<IllegalStateException> {
            registry.dispatch(DaemonRequest("", "request", "plugin.one", "worker", method = "read"))
        }
    }

    @Test
    fun `UI artifact deletion cannot break late worker code or context loader resources`() {
        val jar = artifact()
        val descriptor = connect(jar = jar)
        registry.dispatch(descriptor)
        assertTrue(jar.delete())
        assertEquals("late:snapshot-resource", request("plugin.one", "late"))
        // Reconnect uses the running worker, without re-reading a removed UI artifact.
        assertTrue(registry.dispatch(descriptor).endpoints.isNotEmpty())
    }

    @Test
    fun `entry point cannot resolve from host or bootstrap classes`() {
        val descriptor = connect().copy(entryPoint = "java.lang.StringBuilder")
        assertFailsWith<IllegalArgumentException> { registry.dispatch(descriptor) }
        assertEquals(0, registry.count())
    }

    @Test
    fun `tampered plugin artifact is refused before starting service`() {
        val jar = artifact()
        val descriptor = connect(jar = jar)
        jar.appendText("changed")
        assertFailsWith<IllegalArgumentException> { registry.dispatch(descriptor) }
        assertEquals(0, registry.count())
    }

    @Test
    fun `explicit stop removes registration and drains scope before returning`() {
        val response = registry.dispatch(connect())
        val data = File(response.endpoints.getValue("data"))
        assertTrue(File(data, "started").isFile)
        registry.stopPlugin("plugin.one")
        assertEquals(0, registry.count())
        assertTrue(File(data, "stopped").isFile)
        assertTrue(File(data, "scope-drained").isFile)
        assertTrue(File(directory, "daemon/registrations").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `process shutdown preserves registrations for login but explicit stop removes them`() {
        val response = registry.dispatch(connect())
        registry.close()
        val restored = DaemonServiceRegistry(File(directory, "daemon"))
        try {
            restored.restore()
            assertEquals(1, restored.count())
            val next = restored.dispatch(connect()).endpoints
            assertEquals(response.endpoints["data"], next["data"])
            assertNotEquals(response.endpoints["boss.service.instanceId"], next["boss.service.instanceId"])
            restored.stopPlugin("plugin.one")
        } finally {
            restored.close()
        }
        val again = DaemonServiceRegistry(File(directory, "daemon"))
        try {
            again.restore()
            assertEquals(0, again.count())
        } finally {
            again.close()
        }
    }

    @Test
    fun `disabled plugin cannot resurrect a registration whose worker failed to restore`() {
        registry.dispatch(connect())
        registry.close()
        File(directory, "daemon/artifacts").listFiles().orEmpty().forEach { it.delete() }
        val restored = DaemonServiceRegistry(File(directory, "daemon"))
        try {
            restored.restore()
            assertEquals(0, restored.count())
            restored.stopPlugin("plugin.one")
            assertTrue(File(directory, "daemon/registrations").listFiles().orEmpty().isEmpty())
        } finally {
            restored.close()
        }
    }

    @Test
    fun `failed startup cancels child work before closing loader`() {
        assertFailsWith<IllegalStateException> { registry.dispatch(connect(config = mapOf("fail" to "true"))) }
        assertEquals(0, registry.count())
        val serviceData = File(directory, "daemon/services").listFiles().orEmpty().single()
        assertTrue(File(serviceData, "scope-drained").isFile)
        assertTrue(File(serviceData, "stopped").isFile)
    }
}
