package ai.rever.boss.process

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Which registered processes the global monitor attaches health supervision to.
 *
 * Plugin children are in the registry so the kernel's shutdown hook can reap them on exit,
 * but their health belongs to `PluginProcessMonitor` on the host side. If this monitor
 * supervised them too, a plugin the operator disables would exit on purpose, read as a crash
 * here, and come back through the kernel's respawn path behind the operator's back.
 */
class ProcessMonitorSupervisionTest {
    /** A [Process] whose liveness the test controls. */
    private class FakeProcess(
        private val pidValue: Long,
    ) : Process() {
        private var alive = true
        private var exit = 0

        fun die(exitCode: Int) {
            exit = exitCode
            alive = false
        }

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = InputStream.nullInputStream()

        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(): Int = exit

        override fun waitFor(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean = !alive

        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else exit

        override fun destroy() = die(143)

        override fun isAlive(): Boolean = alive

        override fun pid(): Long = pidValue
    }

    private fun managed(
        id: String,
        type: ProcessType,
        process: Process,
    ) = ManagedProcess(
        config =
            ProcessConfig(
                processId = id,
                processType = type,
                displayName = id,
                mainClass = "Main",
                heartbeatIntervalMs = 100,
            ),
        process = process,
        ipcAddress = "unix:///tmp/$id",
    )

    /**
     * Run the global monitor over a single registered process of [type], kill it, and return
     * every failure the monitor reported.
     */
    private fun failuresAfterDeath(type: ProcessType): List<ProcessFailure> {
        val seen = mutableListOf<ProcessFailure>()
        runTest {
            val registry = ProcessRegistry()
            val monitor = ProcessMonitor(registry, backgroundScope)
            backgroundScope.launch { monitor.failures.collect { seen += it } }

            val proc = FakeProcess(4242)
            registry.register("p1", managed("p1", type, proc))

            monitor.startGlobalMonitor(checkIntervalMs = 10)
            advanceTimeBy(50)
            proc.die(exitCode = 9)
            advanceTimeBy(500)
        }
        return seen
    }

    @Test
    fun `a dead service is reported as a failure`() {
        val failures = failuresAfterDeath(ProcessType.SERVICE)

        // Reported repeatedly, not once: monitorProcess breaks out on death and its monitor
        // deregisters itself, so the global monitor re-attaches on its next tick and reports
        // again. In the real kernel the failure handler respawns or unregisters, which ends it.
        // This test pins that a service death is seen at all, not how many times.
        assertTrue(failures.isNotEmpty(), "a service death must reach the kernel's failure path")
        assertTrue(
            failures.all { it.processId == "p1" && it.exitCode == 9 && it.reason == FailureReason.PROCESS_EXIT },
            "unexpected failure contents: $failures",
        )
    }

    @Test
    fun `a dead plugin is not reported - PluginProcessMonitor owns plugin health`() {
        assertEquals(
            emptyList(),
            failuresAfterDeath(ProcessType.PLUGIN),
            "a plugin exit must not reach the kernel's respawn path",
        )
    }

    /**
     * Run the global monitor over one registered process of [type] and return the registry ids
     * still present after it dies.
     */
    private fun registeredIdsAfterDeath(
        type: ProcessType,
        kill: Boolean,
    ): List<String> {
        var remaining = emptyList<String>()
        runTest {
            val registry = ProcessRegistry()
            val monitor = ProcessMonitor(registry, backgroundScope)

            val proc = FakeProcess(7272)
            registry.register("p1", managed("p1", type, proc))

            monitor.startGlobalMonitor(checkIntervalMs = 10)
            advanceTimeBy(50)
            if (kill) proc.die(exitCode = 1)
            advanceTimeBy(500)

            remaining = registry.getAllProcesses().map { it.config.processId }
        }
        return remaining
    }

    @Test
    fun `a dead plugin is pruned from the registry`() {
        // Only a deliberate terminate unregisters a plugin, so a plugin that crashed or ran out of
        // restart budget would otherwise leave a dead handle in the registry for the whole session
        // and keep processCount over-reporting it.
        assertEquals(emptyList(), registeredIdsAfterDeath(ProcessType.PLUGIN, kill = true))
    }

    @Test
    fun `a live plugin is left in the registry`() {
        assertEquals(listOf("p1"), registeredIdsAfterDeath(ProcessType.PLUGIN, kill = false))
    }

    @Test
    fun `pruning a dead plugin cannot evict a live replacement under the same id`() {
        // getAllProcesses() hands back a snapshot, so the prune decides on a handle it read earlier.
        // If a respawn registers a new child under the same id in between, removing by id alone
        // would drop the live one - out of the registry that is the only thing the shutdown hook
        // can see, making it exactly the orphan this whole change exists to prevent.
        runTest {
            val registry = ProcessRegistry()
            val monitor = ProcessMonitor(registry, backgroundScope)

            val dead = FakeProcess(1111)
            registry.register("plugin-x", managed("plugin-x", ProcessType.PLUGIN, dead))
            dead.die(exitCode = 1)

            // The replacement lands before the monitor gets to act on its snapshot.
            val liveReplacement = managed("plugin-x", ProcessType.PLUGIN, FakeProcess(2222))
            registry.register("plugin-x", liveReplacement)

            monitor.startGlobalMonitor(checkIntervalMs = 10)
            advanceTimeBy(500)

            val survivor = registry.getProcess("plugin-x")
            assertNotNull(survivor, "the live replacement must still be registered")
            assertSame(liveReplacement, survivor, "the dead handle's prune took the live one with it")
            assertTrue(survivor.isAlive)
        }
    }

    @Test
    fun `plugins stay in the registry so the shutdown hook can still reap them`() {
        val registry = ProcessRegistry()
        val proc = FakeProcess(6262)
        registry.register("plugin-y", managed("plugin-y", ProcessType.PLUGIN, proc))

        // Exactly what the kernel's JVM shutdown hook iterates.
        val reapable = registry.getAllProcesses()
        assertEquals(listOf("plugin-y"), reapable.map { it.config.processId })

        assertTrue(proc.isAlive)
        reapable.forEach { it.destroy() }
        assertFalse(proc.isAlive, "the hook's destroy must reach a registered plugin child")
    }
}
