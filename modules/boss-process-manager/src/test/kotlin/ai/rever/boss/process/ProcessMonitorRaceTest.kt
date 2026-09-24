package ai.rever.boss.process

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * startMonitoring installs exactly one monitor per process id, even when callers race.
 *
 * The kernel starts the global monitor loop before it spawns services, so the loop's re-attach
 * pass and a spawn's own start call race into startMonitoring for the same id. If the
 * check-and-register is not atomic, both launches run and the job map tracks only one of them:
 * the untracked coroutine is invisible to stopMonitoring and stopSupervision, uncancellable,
 * and reports the same death a second time - which makes the kernel respawn twice, evict a
 * live child from the registry, and burn the restart budget two at a time.
 *
 * The ProcessFailure emission is the only thing the kernel reacts to, so these tests count
 * emissions: one death must produce exactly one, from real racing threads, across repeated
 * crashes, and with the stop paths intact.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProcessMonitorRaceTest {
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

    /**
     * Run [block] from [times] real threads released together. Racing startMonitoring calls on
     * runTest's single virtual-time thread would serialize and hide the check-then-act window
     * this exists to hit.
     */
    private fun concurrently(
        times: Int,
        block: () -> Unit,
    ) {
        val ready = CountDownLatch(times)
        val go = CountDownLatch(1)
        val done = CountDownLatch(times)
        repeat(times) {
            thread(isDaemon = true) {
                ready.countDown()
                check(go.await(10, TimeUnit.SECONDS)) { "start barrier did not open" }
                try {
                    block()
                } finally {
                    done.countDown()
                }
            }
        }
        check(ready.await(10, TimeUnit.SECONDS)) { "racing threads did not start" }
        go.countDown()
        check(done.await(10, TimeUnit.SECONDS)) { "racing threads did not finish" }
    }

    @Test
    fun `racing starts install one monitor - one failure and one restart count per death`() {
        val registry = ProcessRegistry()
        val monitor = ProcessMonitor(registry)
        val failures = CopyOnWriteArrayList<ProcessFailure>()
        val collectorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val collector = collectorScope.launch { monitor.failures.collect { failures += it } }

        try {
            Thread.sleep(100) // let the failure collector subscribe before anything can emit
            val deaths = 3
            repeat(deaths) { round ->
                val proc = FakeProcess(4000L + round)
                registry.register("svc-race", managed("svc-race", proc))

                concurrently(times = 16) { monitor.startMonitoring("svc-race") }

                Thread.sleep(150) // the monitor has taken at least one alive-check
                proc.die(exitCode = 7 + round)

                val deadline = System.currentTimeMillis() + 5_000
                while (failures.size <= round && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10)
                }
                Thread.sleep(250) // a duplicate monitor reports within a heartbeat of the first
                assertEquals(
                    round + 1,
                    failures.size,
                    "one death must produce exactly one emission: $failures",
                )

                // Exactly what the kernel's failure handler does per emission it receives.
                registry.incrementRestartCount("svc-race")
            }

            assertEquals(
                deaths,
                registry.getRestartCount("svc-race"),
                "the restart budget must be consumed once per crash, not once per duplicate monitor",
            )
        } finally {
            collector.cancel()
            collectorScope.cancel()
            monitor.stopAll()
        }
    }

    @Test
    fun `a monitor stopped before the crash does not report it`() {
        runTest {
            val registry = ProcessRegistry()
            val monitor = ProcessMonitor(registry, backgroundScope)
            val seen = mutableListOf<ProcessFailure>()
            backgroundScope.launch { monitor.failures.collect { seen += it } }

            val proc = FakeProcess(4400)
            registry.register("svc-stop", managed("svc-stop", proc))
            monitor.startMonitoring("svc-stop")
            advanceTimeBy(50) // the monitor is live and ticking
            monitor.stopMonitoring("svc-stop")
            proc.die(exitCode = 3)
            advanceTimeBy(500)

            assertTrue(seen.isEmpty(), "a monitor stopped before the crash must not report it: $seen")
        }
    }

    @Test
    fun `a finished monitor deregisters so a successor takes the id cleanly`() {
        runTest {
            val registry = ProcessRegistry()
            val monitor = ProcessMonitor(registry, backgroundScope)
            val seen = mutableListOf<ProcessFailure>()
            backgroundScope.launch { monitor.failures.collect { seen += it } }

            val first = FakeProcess(5501)
            registry.register("svc-chain", managed("svc-chain", first))
            monitor.startMonitoring("svc-chain")
            first.die(exitCode = 1)
            advanceTimeBy(200) // the monitor reports once, completes, and removes its registration

            val second = FakeProcess(5502)
            registry.register("svc-chain", managed("svc-chain", second))
            monitor.startMonitoring("svc-chain")
            advanceTimeBy(50) // the successor is live and ticking
            second.die(exitCode = 2)
            advanceTimeBy(200)

            assertEquals(2, seen.size, "each death must be reported exactly once: $seen")
            assertEquals(listOf(1, 2), seen.map { it.exitCode })
        }
    }

    @Test
    fun `stopSupervision leaves no monitor to report a later death`() {
        runTest {
            val registry = ProcessRegistry()
            val monitor = ProcessMonitor(registry, backgroundScope)
            val seen = mutableListOf<ProcessFailure>()
            backgroundScope.launch { monitor.failures.collect { seen += it } }

            val proc = FakeProcess(6600)
            registry.register("svc-halt", managed("svc-halt", proc))
            monitor.startGlobalMonitor(checkIntervalMs = 10)
            advanceTimeBy(50) // the global monitor has attached supervision
            monitor.stopSupervision()
            proc.die(exitCode = 8)
            advanceTimeBy(500)

            assertTrue(seen.isEmpty(), "no monitor may outlive stopSupervision: $seen")
        }
    }
}
