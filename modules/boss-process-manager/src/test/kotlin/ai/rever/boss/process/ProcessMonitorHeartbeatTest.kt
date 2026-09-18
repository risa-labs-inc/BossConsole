package ai.rever.boss.process

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the monitor does about a child that is alive but has stopped heartbeating.
 *
 * Liveness cannot see a wedge: a deadlocked, stalled or IPC-blind child keeps its process slot
 * indefinitely and everything waiting on it stalls too. The heartbeat watch is what lets the
 * monitor tell a wedged child from a healthy one, and this pins the whole arc — detect through
 * the watch, tear the wedged child down so it cannot become an invisible live handle, and hand
 * the failure to the recovery path as [FailureReason.HEARTBEAT_TIMEOUT] — plus the two ways the
 * check must stand down: a current beat, and no watch to consult at all.
 */
class ProcessMonitorHeartbeatTest {
    /** A [Process] whose liveness the test controls. */
    private class FakeProcess(
        private val pidValue: Long,
    ) : Process() {
        private var alive = true

        fun die() {
            alive = false
        }

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = InputStream.nullInputStream()

        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(): Int = 137

        override fun waitFor(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean = !alive

        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 137

        override fun destroy() = die()

        override fun isAlive(): Boolean = alive

        override fun pid(): Long = pidValue
    }

    /** A [HeartbeatWatch] the test scripts, recording what the monitor asked it to forget. */
    private class ScriptedWatch(
        private val stalled: Set<String> = emptySet(),
    ) : HeartbeatWatch {
        val forgotten = mutableListOf<String>()

        override fun isHeartbeatTimedOut(
            processId: String,
            thresholdMs: Long,
        ): Boolean = processId in stalled

        override fun forget(processId: String) {
            forgotten += processId
        }
    }

    private fun managed(
        id: String,
        process: Process,
    ) = ManagedProcess(
        config =
            ProcessConfig(
                processId = id,
                processType = ProcessType.SERVICE,
                displayName = id,
                mainClass = "Main",
                heartbeatIntervalMs = 100,
            ),
        process = process,
        ipcAddress = "unix:///tmp/$id",
    )

    @Test
    fun `a wedged child is killed reported as heartbeat timeout and its beat forgotten`() {
        val seen = mutableListOf<ProcessFailure>()
        val watch = ScriptedWatch(stalled = setOf("p1"))
        val proc = FakeProcess(9001)
        runTest {
            val registry = ProcessRegistry()
            val monitor = ProcessMonitor(registry, backgroundScope, watch)
            backgroundScope.launch { monitor.failures.collect { seen += it } }

            registry.register("p1", managed("p1", proc))

            monitor.startMonitoring("p1")
            advanceTimeBy(300)
        }

        assertEquals(listOf("p1"), watch.forgotten, "the dying generation's beat must be forgotten")
        assertTrue(!proc.isAlive, "the wedged child must be killed, not merely reported")
        assertEquals(1, seen.size, "one wedge must be reported once, not repeatedly: $seen")
        val failure = seen.single()
        assertEquals("p1", failure.processId)
        assertEquals(FailureReason.HEARTBEAT_TIMEOUT, failure.reason)
        assertTrue(failure.errorMessage.isNotEmpty(), "the failure must say what was observed")
    }

    @Test
    fun `a heartbeating child is left alone`() {
        val seen = mutableListOf<ProcessFailure>()
        val watch = ScriptedWatch(stalled = emptySet())
        val proc = FakeProcess(9002)
        runTest {
            val registry = ProcessRegistry()
            val monitor = ProcessMonitor(registry, backgroundScope, watch)
            backgroundScope.launch { monitor.failures.collect { seen += it } }

            registry.register("p1", managed("p1", proc))

            monitor.startMonitoring("p1")
            advanceTimeBy(1_000)
        }

        assertEquals(emptyList(), seen, "a current beat is not a wedge")
        assertTrue(proc.isAlive, "a heartbeating child must not be killed")
        assertEquals(emptyList(), watch.forgotten)
    }

    @Test
    fun `without a watch supervision stays liveness-only`() {
        val seen = mutableListOf<ProcessFailure>()
        val proc = FakeProcess(9003)
        runTest {
            val registry = ProcessRegistry()
            // The two-argument construction is what every existing caller uses; it must keep
            // meaning liveness-only rather than gaining a heartbeat source that does not exist.
            val monitor = ProcessMonitor(registry, backgroundScope)
            backgroundScope.launch { monitor.failures.collect { seen += it } }

            registry.register("p1", managed("p1", proc))

            monitor.startMonitoring("p1")
            advanceTimeBy(1_000)
        }

        assertEquals(emptyList(), seen, "no watch means no heartbeat evidence means no kill")
        assertTrue(proc.isAlive, "an alive child must survive liveness-only supervision")
    }
}
