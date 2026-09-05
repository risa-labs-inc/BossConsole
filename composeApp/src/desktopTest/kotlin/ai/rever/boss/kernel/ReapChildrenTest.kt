package ai.rever.boss.kernel

import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import ai.rever.boss.process.ManagedProcess
import ai.rever.boss.process.ProcessConfig
import ai.rever.boss.process.ProcessMonitor
import ai.rever.boss.process.ProcessRegistry
import ai.rever.boss.process.ProcessType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the JVM shutdown hook does with the kernel's children.
 *
 * Two properties, both of which used to be wrong:
 *
 * 1. Supervision stops before anything is killed. Every `destroy()` looks like a crash to the
 *    monitor, whose failure handler respawns, so reaping while it still watches can hand an exiting
 *    host a fresh generation of children.
 * 2. The cost does not scale with the cohort. Kills go out to everything first and are awaited
 *    against one shared deadline; the previous destroy-then-wait-2s per process meant N plugins
 *    could add 2N seconds to a Cmd+Q, and the OS caps how long a shutdown hook runs - a hook cut
 *    off part-way down the list stranded the tail, which is the orphan symptom all over again.
 */
class ReapChildrenTest {
    /** A process that ignores `destroy()` for [ignoreDestroys] calls, to force the wait path. */
    private class FakeProcess(
        private val pidValue: Long,
        private val ignoreDestroys: Int = 0,
    ) : Process() {
        private var alive = true
        private var destroys = 0
        var forciblyKilled = false
            private set

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = InputStream.nullInputStream()

        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(): Int = 0

        override fun waitFor(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean {
            // Blocks for the whole timeout while alive, exactly as the real Process does. A fake
            // that returned immediately would make the shared-deadline test unable to fail: with no
            // blocking, per-process waiting costs the same as one shared budget.
            if (!alive) return true
            Thread.sleep(unit.toMillis(timeout))
            return !alive
        }

        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0

        override fun destroy() {
            destroys++
            if (destroys > ignoreDestroys) alive = false
        }

        override fun destroyForcibly(): Process {
            forciblyKilled = true
            alive = false
            return this
        }

        override fun isAlive(): Boolean = alive

        override fun pid(): Long = pidValue
    }

    private fun managed(
        id: String,
        process: Process,
        type: ProcessType = ProcessType.PLUGIN,
    ) = ManagedProcess(
        config =
            ProcessConfig(
                processId = id,
                processType = type,
                displayName = id,
                mainClass = "Main",
            ),
        process = process,
        ipcAddress = "unix:///tmp/$id",
    )

    @Test
    fun `every registered child is killed`() {
        val registry = ProcessRegistry()
        val processes = (1..16).map { FakeProcess(it.toLong()) }
        processes.forEachIndexed { i, p -> registry.register("plugin-$i", managed("plugin-$i", p)) }

        reapChildren(monitor = null, registry = registry)

        assertTrue(processes.none { it.isAlive }, "a surviving child is an orphan")
    }

    @Test
    fun `a child that ignores destroy is force-killed`() {
        val registry = ProcessRegistry()
        val stubborn = FakeProcess(99, ignoreDestroys = Int.MAX_VALUE)
        registry.register("plugin-stubborn", managed("plugin-stubborn", stubborn))

        reapChildren(monitor = null, registry = registry, gracePeriodMs = 50)

        assertTrue(stubborn.forciblyKilled, "SIGTERM being ignored must escalate")
        assertFalse(stubborn.isAlive)
    }

    @Test
    fun `the grace period is shared, not per process`() {
        val registry = ProcessRegistry()
        // All stubborn, so every one of them exercises the wait path.
        repeat(24) { i ->
            val stubborn = FakeProcess(i.toLong(), ignoreDestroys = Int.MAX_VALUE)
            registry.register("plugin-$i", managed("plugin-$i", stubborn))
        }

        val started = System.currentTimeMillis()
        reapChildren(monitor = null, registry = registry, gracePeriodMs = 200)
        val elapsed = System.currentTimeMillis() - started

        // Per-process waiting would be 24 x 200ms = 4.8s. The shared deadline keeps it flat.
        assertTrue(elapsed < 2_000, "reap took ${elapsed}ms - the deadline is not being shared")
    }

    @Test
    fun `supervision is stopped so a kill cannot be read as a crash`() {
        val registry = ProcessRegistry()
        val scope = CoroutineScope(SupervisorJob())
        val monitor = ProcessMonitor(registry, scope)
        val failures = mutableListOf<String>()
        scope.launch { monitor.failures.collect { failures += it.processId } }

        // A SERVICE, because PLUGIN is exempt from supervision and so could not report a failure
        // even if supervision were still running - the test would pass for the wrong reason.
        registry.register("svc-a", managed("svc-a", FakeProcess(7), ProcessType.SERVICE))
        monitor.startGlobalMonitor(checkIntervalMs = 10)
        Thread.sleep(100) // let the monitor attach before anything is killed

        reapChildren(monitor = monitor, registry = registry, gracePeriodMs = 50)
        Thread.sleep(300) // give a surviving monitor time to notice and report

        assertTrue(
            failures.isEmpty(),
            "the reap's own kills were reported as crashes, which is what triggers a respawn: $failures",
        )
        // stopSupervision, not stopAll: the kernel passes its own scope in and still uses it after
        // reaping (the event bridge, the failure collector), so reaping must not cancel it.
        assertTrue(scope.isActive, "reapChildren must not cancel a scope it does not own")
        scope.cancel()
    }

    @Test
    fun `recovery stands down while a reap is in progress`() {
        // stopSupervision closes the detection path; this is the action path. A handleFailure parked
        // awaiting orchestrator advice can outlive the cancel and respawn a child after the reap's
        // snapshot, leaving the exiting host a child nothing reaps.
        assertFalse(isReaping(), "no reap in flight at rest")

        val registry = ProcessRegistry()
        val observed = mutableListOf<Boolean>()
        val slow =
            object : Process() {
                override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

                override fun getInputStream(): InputStream = InputStream.nullInputStream()

                override fun getErrorStream(): InputStream = InputStream.nullInputStream()

                override fun waitFor(): Int = 0

                override fun waitFor(
                    timeout: Long,
                    unit: TimeUnit,
                ): Boolean {
                    observed += isReaping()
                    return true
                }

                override fun exitValue(): Int = 0

                override fun destroy() = Unit

                override fun isAlive(): Boolean = false

                override fun pid(): Long = 4242
            }
        registry.register("plugin-slow", managed("plugin-slow", slow))

        reapChildren(monitor = null, registry = registry, gracePeriodMs = 100)

        assertEquals(listOf(true), observed, "isReaping() must be true for the duration of the reap")
        assertFalse(isReaping(), "and false again afterwards")
    }

    @Test
    fun `recovery refuses to respawn during a reap`() {
        val registry = ProcessRegistry()
        val alive = FakeProcess(31, ignoreDestroys = Int.MAX_VALUE)
        registry.register("svc-x", managed("svc-x", alive, ProcessType.SERVICE))

        // Outside a reap it is a respawn candidate...
        assertEquals("svc-x", respawnCandidate(registry, "svc-x")?.config?.processId)

        // ...and during one it must not be, or the exiting host gains a child nothing reaps.
        var candidateDuringReap: ManagedProcess? = null
        val probe =
            object : Process() {
                override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

                override fun getInputStream(): InputStream = InputStream.nullInputStream()

                override fun getErrorStream(): InputStream = InputStream.nullInputStream()

                override fun waitFor(): Int = 0

                override fun waitFor(
                    timeout: Long,
                    unit: TimeUnit,
                ): Boolean {
                    candidateDuringReap = respawnCandidate(registry, "svc-x")
                    return true
                }

                override fun exitValue(): Int = 0

                override fun destroy() = Unit

                override fun isAlive(): Boolean = false

                override fun pid(): Long = 32
            }
        registry.register("svc-probe", managed("svc-probe", probe, ProcessType.SERVICE))

        reapChildren(monitor = null, registry = registry, gracePeriodMs = 100)

        assertEquals(null, candidateDuringReap, "respawn must stand down while a reap is in progress")
    }

    @Test
    fun `an empty registry is a no-op`() {
        reapChildren(monitor = null, registry = ProcessRegistry())
        reapChildren(monitor = null, registry = null)
        assertEquals(0, ProcessRegistry().getAllProcesses().size)
    }

    @Test
    fun `a reaped child's IPC credential is revoked - it must not outlive the process (BossConsole#53)`() {
        val registry = ProcessRegistry()
        val tokenRegistry = ProcessTokenRegistry()
        val token = tokenRegistry.issue("plugin-under-test")
        registry.register("plugin-under-test", managed("plugin-under-test", FakeProcess(1)))

        reapChildren(monitor = null, registry = registry, tokenRegistry = tokenRegistry)

        assertEquals(null, tokenRegistry.identityFor(token), "a dead process's credential must stop resolving")
    }

    @Test
    fun `reaping with no tokenRegistry behaves exactly as before - null is not a crash`() {
        val registry = ProcessRegistry()
        val process = FakeProcess(1)
        registry.register("plugin-under-test", managed("plugin-under-test", process))

        reapChildren(monitor = null, registry = registry)

        assertFalse(process.isAlive, "omitting tokenRegistry must not stop the reap itself from working")
    }
}
