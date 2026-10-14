package ai.rever.boss.components.plugin

import ai.rever.boss.components.plugin.remote.PluginProcessMonitor
import ai.rever.boss.process.ProcessSpawner
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutOfProcessPluginSpawnerLifecycleTest {
    @Test
    fun `terminate disarms monitoring while lifecycle lock is held`() =
        runBlocking {
            val pluginId = "com.example.parked-restart"
            val spawner = OutOfProcessPluginSpawnerImpl(ProcessSpawner("unused"))
            val monitor =
                spawner.javaClass
                    .getDeclaredField("processMonitor")
                    .apply {
                        isAccessible = true
                    }.get(spawner) as PluginProcessMonitor

            @Suppress("UNCHECKED_CAST")
            val locks =
                spawner.javaClass
                    .getDeclaredField("pluginLifecycleMutexes")
                    .apply {
                        isAccessible = true
                    }.get(spawner) as MutableMap<String, Mutex>

            val restartLock = Mutex(locked = true)
            locks[pluginId] = restartLock

            try {
                monitor.monitor(pluginId = pluginId, displayName = "Parked restart")
                val termination = async { spawner.terminate(pluginId) }

                withTimeout(5_000) {
                    while (monitor.isMonitored(pluginId)) delay(10)
                }
                assertFalse(termination.isCompleted, "Termination should still be waiting for the restart lock")

                restartLock.unlock()
                assertTrue(withTimeout(5_000) { termination.await() }.isSuccess)
                assertFalse(monitor.isMonitored(pluginId))
            } finally {
                if (restartLock.isLocked) restartLock.unlock()
                spawner.dispose()
            }
        }
}
