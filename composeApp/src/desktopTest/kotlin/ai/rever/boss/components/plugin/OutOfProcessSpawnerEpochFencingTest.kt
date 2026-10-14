package ai.rever.boss.components.plugin

import ai.rever.boss.components.plugin.remote.PluginProcessMonitor
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.process.ManagedProcess
import ai.rever.boss.process.ProcessConfig
import ai.rever.boss.process.ProcessSpawner
import ai.rever.boss.process.ProcessType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the spawner's restart fencing against the REAL spawner: the epoch checks in
 * `restartAfterCrash` (both, including the one after the teardown suspension point)
 * and in `cleanupAfterTerminalFailure`, plus the live-child guard in the spawn path.
 * The manager-level tests exercise these through a fake backend that implements a
 * different mechanism; only here does `RecordingSpawner`-style reasoning stop applying.
 */
class OutOfProcessSpawnerEpochFencingTest {
    @Test
    fun `a second spawn for a plugin with a live child refuses and keeps the first child`() =
        runBlocking {
            val pluginId = "com.example.double-spawn"
            val spawner = OutOfProcessPluginSpawnerImpl(ProcessSpawner("unused"))
            val process =
                managedProcess(
                    pluginId,
                    FakeProcess(
                        420,
                    ),
                )

            @Suppress("UNCHECKED_CAST")
            val managed =
                spawner.javaClass
                    .getDeclaredField("managedProcesses")
                    .apply { isAccessible = true }
                    .get(spawner) as MutableMap<String, ManagedProcess>
            managed[pluginId] = process

            val second =
                spawner.spawn(
                    PluginManifest(
                        pluginId = pluginId,
                        displayName = "Double spawn",
                        version = "1.0.0",
                        apiVersion = "1.0.0",
                        mainClass = "com.example.Main",
                    ),
                    "unused.jar",
                )

            assertTrue(second.isFailure, "A second spawn for a live child must refuse")
            assertTrue(second.exceptionOrNull() is IllegalStateException)
            assertSame(process, managed[pluginId], "The refusal must keep the first child in place")
            spawner.dispose()
        }

    @Test
    fun `a restart admitted before a terminate is refused once the epoch goes stale`() =
        runBlocking {
            val pluginId = "com.example.stale-epoch"
            val spawner = OutOfProcessPluginSpawnerImpl(ProcessSpawner("unused"))
            val monitor = monitorOf(spawner)
            val epoch = spawner.restartEpochForTest(pluginId)
            val manifest =
                PluginManifest(
                    pluginId = pluginId,
                    displayName = "Stale epoch",
                    version = "1.0.0",
                    apiVersion = "1.0.0",
                    mainClass = "com.example.Main",
                )

            try {
                monitor.monitor(pluginId = pluginId, displayName = "Stale epoch")
                val admittedEpoch = epoch.get()
                spawner.terminate(pluginId)
                monitor.monitor(pluginId = pluginId, displayName = "Stale epoch")
                assertEquals(admittedEpoch + 1, epoch.get(), "terminate must bump the epoch")

                val staleRestart = spawner.restartAfterCrash(manifest, "unused.jar", admittedEpoch)

                assertTrue(staleRestart.isFailure, "A restart admitted before the terminate must be refused")
                assertTrue(monitor.isMonitored(pluginId), "The stale restart must not evict the re-armed entry")
            } finally {
                spawner.dispose()
            }
        }

    @Test
    fun `a restart whose epoch goes stale mid-teardown is refused at the second check`() =
        runBlocking {
            val pluginId = "com.example.mid-teardown-stale"
            val spawner = OutOfProcessPluginSpawnerImpl(ProcessSpawner("unused"))
            val monitor = monitorOf(spawner)
            val epoch = spawner.restartEpochForTest(pluginId)
            val destroyed = CompletableDeferred<Unit>()
            val process =
                managedProcess(
                    pluginId,
                    FakeProcess(
                        423,
                        ignoreDestroys = 1,
                        delayedForce = true,
                        onDestroy = { destroyed.complete(Unit) },
                    ),
                )

            @Suppress("UNCHECKED_CAST")
            val managed =
                spawner.javaClass
                    .getDeclaredField("managedProcesses")
                    .apply { isAccessible = true }
                    .get(spawner) as MutableMap<String, ManagedProcess>
            managed[pluginId] = process
            val manifest =
                PluginManifest(
                    pluginId = pluginId,
                    displayName = "Mid teardown stale",
                    version = "1.0.0",
                    apiVersion = "1.0.0",
                    mainClass = "com.example.Main",
                )

            try {
                monitor.monitor(pluginId = pluginId, displayName = "Mid teardown stale")
                val admittedEpoch = epoch.get()
                val restart = async { spawner.restartAfterCrash(manifest, "unused.jar", admittedEpoch) }

                // The restart holds the per-plugin lock through its teardown, and the
                // child only dies to destroyForcibly after the 5s grace, so landing the
                // terminate once the destroy has landed deterministically puts the
                // epoch bump and unmonitor BETWEEN the restart's two checks: the
                // first check has already passed, the post-teardown wait gives the
                // terminate its window, and the second check must reject the stale
                // restart.
                withTimeout(5_000) { destroyed.await() }
                val termination = async { spawner.terminate(pluginId) }

                val staleRestart = withTimeout(20_000) { restart.await() }
                assertTrue(
                    staleRestart.isFailure,
                    "The post-teardown epoch check must catch a terminate that landed mid-restart",
                )
                assertTrue(withTimeout(20_000) { termination.await() }.isSuccess)
                assertFalse(process.isAlive, "The child must be force-killed exactly once")
                assertFalse(monitor.isMonitored(pluginId))
            } finally {
                spawner.dispose()
            }
        }

    @Test
    fun `terminal failure cleanup with a stale epoch is a no-op`() =
        runBlocking {
            val pluginId = "com.example.stale-cleanup"
            val spawner = OutOfProcessPluginSpawnerImpl(ProcessSpawner("unused"))
            val monitor = monitorOf(spawner)
            val epoch = spawner.restartEpochForTest(pluginId)

            try {
                monitor.monitor(pluginId = pluginId, displayName = "Stale cleanup")
                val admittedEpoch = epoch.get()
                spawner.terminate(pluginId)
                monitor.monitor(pluginId = pluginId, displayName = "Stale cleanup")

                spawner.cleanupAfterTerminalFailure(pluginId, admittedEpoch)

                assertTrue(monitor.isMonitored(pluginId), "Stale terminal cleanup must not touch the re-armed entry")
            } finally {
                spawner.dispose()
            }
        }

    private fun monitorOf(spawner: OutOfProcessPluginSpawnerImpl): PluginProcessMonitor =
        spawner.javaClass
            .getDeclaredField("processMonitor")
            .apply { isAccessible = true }
            .get(spawner) as PluginProcessMonitor

    private fun managedProcess(
        pluginId: String,
        process: FakeProcess,
    ): ManagedProcess =
        ManagedProcess(
            config =
                ProcessConfig(
                    processId = "window::plugin::$pluginId",
                    processType = ProcessType.PLUGIN,
                    displayName = pluginId,
                    mainClass = "com.example.Main",
                ),
            process = process,
            ipcAddress = "127.0.0.1:0",
        )

    private class FakeProcess(
        private val pidValue: Long,
        private val ignoreDestroys: Int = 0,
        private val delayedForce: Boolean = false,
        private val onDestroy: () -> Unit = {},
    ) : Process() {
        private var alive = true
        private var destroys = 0
        private var forciblyKilled = false

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = InputStream.nullInputStream()

        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(): Int = 0

        override fun waitFor(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean {
            if (delayedForce && forciblyKilled) alive = false
            if (!alive) return true
            Thread.sleep(unit.toMillis(timeout))
            return !alive
        }

        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0

        override fun destroy() {
            onDestroy()
            destroys++
            if (destroys > ignoreDestroys) alive = false
        }

        override fun destroyForcibly(): Process {
            forciblyKilled = true
            if (!delayedForce) alive = false
            return this
        }

        override fun isAlive(): Boolean = alive

        override fun toHandle(): ProcessHandle = super.toHandle()

        override fun pid(): Long = pidValue
    }
}
