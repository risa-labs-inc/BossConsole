package ai.rever.boss.process

import ai.rever.boss.ipc.auth.ProcessAuthRegistry
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Every spawned process lands in the registry.
 *
 * This is the invariant the kernel's shutdown hook depends on: it reaps by iterating
 * [ProcessRegistry.getAllProcesses], so a process that is spawned but not registered can never be
 * killed on exit. Registration used to be each caller's job and the out-of-process plugin spawner
 * did not do it, which stranded a full cohort of plugin JVMs on every host exit - 434 of them,
 * holding 27 GB, on one machine. Owning registration inside [ProcessSpawner] is what makes that
 * unrepeatable, and these tests are what keep it that way.
 */
class ProcessSpawnerRegistrationTest {
    private val logDir: File = Files.createTempDirectory("spawner-test-logs").toFile()
    private val spawned = mutableListOf<ManagedProcess>()

    @AfterTest
    fun cleanUp() {
        spawned.forEach { runCatching { it.destroyForcibly() } }
        logDir.deleteRecursively()
    }

    /**
     * A config that runs a real but trivially short-lived process.
     *
     * `nativeImagePath` points at this JVM's own `java` binary. On POSIX that path exists and is
     * executable, so the child runs natively and exits immediately after printing usage. On Windows
     * `findJavaExecutable()` may yield a path without `.exe`, which fails `buildCommand`'s
     * `exists()` check and falls back to JVM mode with the placeholder main class below - a real
     * process is still spawned and still has to be registered, which is all these tests assert.
     * Either way the test needs no shell and no built fat JAR.
     */
    private fun config(
        id: String,
        type: ProcessType,
    ) = ProcessConfig(
        processId = id,
        processType = type,
        displayName = id,
        mainClass = "unused.WhenNativeImageIsSet",
        nativeImagePath = ProcessSpawner.findJavaExecutable(),
    )

    private fun spawner(
        registry: ProcessRegistry?,
        processAuthRegistry: ProcessAuthRegistry = ProcessAuthRegistry.shared,
    ) = ProcessSpawner(
        kernelIpcAddress = "unix:///tmp/boss-spawner-test-kernel.sock",
        logDir = logDir,
        registry = registry,
        processAuthRegistry = processAuthRegistry,
    )

    @Test
    fun `a spawned plugin child is registered`() {
        val registry = ProcessRegistry()

        val process = spawner(registry).spawn(config("plugin-under-test", ProcessType.PLUGIN))
        spawned += process

        assertSame(
            process,
            registry.getProcess("plugin-under-test"),
            "an unregistered plugin child is invisible to the shutdown hook and leaks on exit",
        )
    }

    @Test
    fun `a spawned service is registered`() {
        val registry = ProcessRegistry()

        val process = spawner(registry).spawn(config("svc-under-test", ProcessType.SERVICE))
        spawned += process

        assertSame(process, registry.getProcess("svc-under-test"))
    }

    @Test
    fun `a registered child is reachable by what the shutdown hook iterates`() {
        val registry = ProcessRegistry()
        val spawner = spawner(registry)

        spawned += spawner.spawn(config("plugin-a", ProcessType.PLUGIN))
        spawned += spawner.spawn(config("svc-b", ProcessType.SERVICE))

        assertEquals(
            listOf("plugin-a", "svc-b"),
            registry.getAllProcesses().map { it.config.processId }.sorted(),
        )
        assertEquals(2, registry.processCount.value)
    }

    @Test
    fun `spawning without a registry still works`() {
        // KERNEL mode always supplies one, but the parameter is optional and the fallback must not
        // be a crash on a null registry.
        val process = spawner(registry = null).spawn(config("no-registry", ProcessType.SERVICE))
        spawned += process

        // pid is a non-nullable Long, so assertNotNull here could never fail - assert the process
        // actually started instead.
        assertTrue(process.pid > 0, "a real child process should have been started")
    }

    @Test
    fun `a spawned process is issued an auth token, and destroying it revokes that token`() {
        // Own instance, not ProcessAuthRegistry.shared: a token this test issues must not collide with,
        // or be confused for, one some other suite - or the production kernel - issued.
        val authRegistry = ProcessAuthRegistry()

        val process = spawner(registry = null, processAuthRegistry = authRegistry).spawn(
            config("token-under-test", ProcessType.SERVICE),
        )
        spawned += process

        val token = process.authToken
        assertEquals(
            "token-under-test",
            authRegistry.processIdFor(requireNotNull(token) { "spawn() must always issue a token" }),
        )

        process.destroyForcibly()

        assertNull(
            authRegistry.processIdFor(token),
            "a destroyed process's token must not remain valid for the rest of the host's lifetime",
        )
    }
}
