package ai.rever.boss.components.plugin.remote

import ai.rever.boss.process.ManagedProcess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PluginProcessMonitorTest {
    private class FakeBackend(
        var alive: Boolean,
        var connected: Boolean = false,
        var restartAllowed: Boolean = true,
        var aliveChecksToFail: Int = 0,
        val aliveOverrides: MutableMap<String, Boolean> = mutableMapOf(),
    ) : PluginProcessMonitorBackend {
        override fun getManagedProcess(pluginId: String): ManagedProcess? = null

        override fun isAlive(pluginId: String): Boolean {
            if (aliveChecksToFail > 0) {
                aliveChecksToFail--
                error("Temporary backend failure")
            }
            return aliveOverrides[pluginId] ?: alive
        }

        override fun isConnected(pluginId: String): Boolean = connected

        override fun isRestartAllowed(): Boolean = restartAllowed
    }

    @Test
    fun `crashed plugin is restarted automatically`() =
        runBlocking {
            val backend = FakeBackend(alive = false)
            val monitor = PluginProcessMonitor(backend)
            var restartAttempts = 0

            try {
                monitor.monitor(
                    pluginId = "test-plugin",
                    displayName = "Test Plugin",
                    maxRestarts = 3,
                    restartAction = {
                        restartAttempts++
                        backend.alive = true
                        backend.connected = true
                        Result.success(Unit)
                    },
                )

                monitor.checkHealthNow()

                val health = monitor.healthStates.value["test-plugin"]
                assertEquals(1, restartAttempts)
                assertEquals(PluginProcessState.RUNNING, health?.processState)
                assertEquals(1, health?.restartCount)
                assertEquals(true, health?.connected)
            } finally {
                monitor.dispose()
            }
        }

    @Test
    fun `failed restarts stop after configured maximum`() =
        runBlocking {
            val backend = FakeBackend(alive = false)
            val monitor = PluginProcessMonitor(backend)
            var restartAttempts = 0

            try {
                monitor.monitor(
                    pluginId = "test-plugin",
                    displayName = "Test Plugin",
                    maxRestarts = 2,
                    restartAction = {
                        restartAttempts++
                        Result.failure(IllegalStateException("restart failed"))
                    },
                )

                monitor.checkHealthNow()
                assertEquals(
                    PluginProcessState.CRASHED,
                    monitor.healthStates.value["test-plugin"]?.processState,
                )

                monitor.checkHealthNow()
                assertEquals(
                    PluginProcessState.FAILED,
                    monitor.healthStates.value["test-plugin"]?.processState,
                )

                monitor.checkHealthNow()

                val health = monitor.healthStates.value["test-plugin"]
                assertEquals(2, restartAttempts)
                assertEquals(2, health?.restartCount)
                assertEquals("restart failed", health?.lastError)
            } finally {
                monitor.dispose()
            }
        }

    @Test
    fun `concurrent restart requests execute only once`() =
        runBlocking {
            val backend = FakeBackend(alive = false)
            val monitor = PluginProcessMonitor(backend)
            val restartEntered = CompletableDeferred<Unit>()
            val releaseRestart = CompletableDeferred<Unit>()
            var restartAttempts = 0

            try {
                monitor.monitor(
                    pluginId = "test-plugin",
                    displayName = "Test Plugin",
                    maxRestarts = 3,
                    restartAction = {
                        restartAttempts++
                        restartEntered.complete(Unit)
                        releaseRestart.await()
                        backend.alive = true
                        Result.success(Unit)
                    },
                )

                val healthCheck =
                    async {
                        monitor.checkHealthNow()
                    }

                withTimeout(1_000) {
                    restartEntered.await()
                }

                monitor.restartPlugin("test-plugin")
                assertEquals(1, restartAttempts)

                releaseRestart.complete(Unit)
                healthCheck.await()

                assertEquals(
                    PluginProcessState.RUNNING,
                    monitor.healthStates.value["test-plugin"]?.processState,
                )
            } finally {
                monitor.dispose()
            }
        }

    @Test
    fun `unmonitored plugin is not restarted`() =
        runBlocking {
            val backend = FakeBackend(alive = true)
            val monitor = PluginProcessMonitor(backend)
            var restartAttempts = 0

            try {
                monitor.monitor(
                    pluginId = "test-plugin",
                    displayName = "Test Plugin",
                    maxRestarts = 3,
                    restartAction = {
                        restartAttempts++
                        Result.success(Unit)
                    },
                )

                monitor.unmonitor("test-plugin")
                backend.alive = false
                monitor.checkHealthNow()

                assertEquals(0, restartAttempts)
                assertNull(monitor.healthStates.value["test-plugin"])
            } finally {
                monitor.dispose()
            }
        }

    @Test
    fun `health check defers restart while process reaping is active`() =
        runBlocking {
            val backend =
                FakeBackend(
                    alive = false,
                    restartAllowed = false,
                )
            val monitor = PluginProcessMonitor(backend)
            var restartAttempts = 0

            try {
                monitor.monitor(
                    pluginId = "test-plugin",
                    displayName = "Test Plugin",
                    maxRestarts = 3,
                    restartAction = {
                        restartAttempts++
                        backend.alive = true
                        Result.success(Unit)
                    },
                )

                monitor.checkHealthNow()

                val deferredHealth = monitor.healthStates.value["test-plugin"]
                assertEquals(0, restartAttempts)
                assertEquals(PluginProcessState.RUNNING, deferredHealth?.processState)
                assertEquals(0, deferredHealth?.restartCount)

                backend.restartAllowed = true
                monitor.checkHealthNow()

                val recoveredHealth = monitor.healthStates.value["test-plugin"]
                assertEquals(1, restartAttempts)
                assertEquals(PluginProcessState.RUNNING, recoveredHealth?.processState)
                assertEquals(1, recoveredHealth?.restartCount)
            } finally {
                monitor.dispose()
            }
        }

    @Test
    fun `zero restart budget cleans resources without spawning replacement`() =
        runBlocking {
            val backend = FakeBackend(alive = false)
            val monitor = PluginProcessMonitor(backend)
            var restartAttempts = 0
            var terminalCleanups = 0

            try {
                monitor.monitor(
                    pluginId = "test-plugin",
                    displayName = "Test Plugin",
                    maxRestarts = 0,
                    restartAction = {
                        restartAttempts++
                        Result.success(Unit)
                    },
                    terminalFailureAction = {
                        terminalCleanups++
                    },
                )

                monitor.checkHealthNow()
                monitor.checkHealthNow()

                val health = monitor.healthStates.value["test-plugin"]
                assertEquals(0, restartAttempts)
                assertEquals(1, terminalCleanups)
                assertEquals(PluginProcessState.FAILED, health?.processState)
                assertEquals(0, health?.restartCount)
            } finally {
                monitor.dispose()
            }
        }

    @Test
    fun `disposed monitor rejects further monitoring and restart work`() =
        runBlocking {
            val backend = FakeBackend(alive = false)
            val monitor = PluginProcessMonitor(backend)
            var restartAttempts = 0

            monitor.monitor(
                pluginId = "test-plugin",
                displayName = "Test Plugin",
                maxRestarts = 3,
                restartAction = {
                    restartAttempts++
                    Result.success(Unit)
                },
            )

            monitor.dispose()
            monitor.checkHealthNow()
            monitor.restartPlugin("test-plugin")

            monitor.monitor(
                pluginId = "second-plugin",
                displayName = "Second Plugin",
                restartAction = {
                    restartAttempts++
                    Result.success(Unit)
                },
            )

            assertEquals(0, restartAttempts)
            assertEquals(emptyMap(), monitor.healthStates.value)
        }

    @Test
    fun `periodic monitoring survives a temporary backend failure`() =
        runBlocking {
            val backend =
                FakeBackend(
                    alive = false,
                    aliveChecksToFail = 1,
                )
            val monitor =
                PluginProcessMonitor(
                    backend = backend,
                    checkIntervalMs = 10,
                )
            var restartAttempts = 0

            try {
                monitor.monitor(
                    pluginId = "test-plugin",
                    displayName = "Test Plugin",
                    restartAction = {
                        restartAttempts++
                        backend.alive = true
                        Result.success(Unit)
                    },
                )
                monitor.start()

                withTimeout(1_000) {
                    while (
                        monitor.healthStates.value["test-plugin"]?.processState !=
                        PluginProcessState.RUNNING ||
                        restartAttempts != 1
                    ) {
                        yield()
                    }
                }

                assertEquals(1, restartAttempts)
                assertEquals(
                    PluginProcessState.RUNNING,
                    monitor.healthStates.value["test-plugin"]?.processState,
                )
            } finally {
                monitor.dispose()
            }
        }

    @Test
    fun `periodic monitoring survives a cancellation exception from a restart action`() =
        runBlocking {
            val backend = FakeBackend(alive = true)
            val monitor =
                PluginProcessMonitor(
                    backend = backend,
                    checkIntervalMs = 10,
                )
            var flakyAttempts = 0
            var bystanderRestarts = 0
            val await: suspend (condition: () -> Boolean) -> Unit = { condition ->
                withTimeout(2_000) {
                    while (!condition()) {
                        yield()
                    }
                }
            }

            try {
                monitor.monitor(
                    "flaky",
                    "flaky",
                    restartAction = {
                        flakyAttempts++
                        if (flakyAttempts == 1) withTimeout(50) { delay(5_000) }
                        backend.aliveOverrides["flaky"] = true
                        Result.success(Unit)
                    },
                )
                monitor.monitor(
                    "bystander",
                    "bystander",
                    restartAction = {
                        bystanderRestarts++
                        backend.aliveOverrides["bystander"] = true
                        Result.success(Unit)
                    },
                )
                monitor.start()

                await {
                    monitor.healthStates.value["flaky"]?.processState ==
                        PluginProcessState.RUNNING &&
                        monitor.healthStates.value["bystander"]?.processState ==
                        PluginProcessState.RUNNING
                }

                backend.aliveOverrides["flaky"] = false

                await { flakyAttempts >= 1 }

                backend.aliveOverrides["bystander"] = false

                await {
                    bystanderRestarts == 1 &&
                        monitor.healthStates.value["bystander"]?.processState ==
                        PluginProcessState.RUNNING
                }
            } finally {
                monitor.dispose()
            }
        }
}
