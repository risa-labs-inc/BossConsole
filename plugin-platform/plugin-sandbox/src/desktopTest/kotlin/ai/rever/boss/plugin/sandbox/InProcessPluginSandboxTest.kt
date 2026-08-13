package ai.rever.boss.plugin.sandbox

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for [InProcessPluginSandbox].
 */
class InProcessPluginSandboxTest {
    private lateinit var sandbox: InProcessPluginSandbox

    @BeforeEach
    fun setUp() {
        sandbox =
            InProcessPluginSandbox(
                pluginId = "test-plugin",
                config =
                    SandboxConfig(
                        maxThreads = 2,
                        heartbeatIntervalMs = 1000,
                        maxConsecutiveErrors = 3,
                    ),
            )
    }

    @AfterEach
    fun tearDown() =
        runTest {
            sandbox.stop()
        }

    @Nested
    inner class LifecycleTests {
        @Test
        fun `sandbox starts in STOPPED state`() {
            assertEquals(SandboxState.STOPPED, sandbox.state.value)
        }

        @Test
        fun `start transitions to RUNNING state`() =
            runTest {
                val result = sandbox.start()

                assertTrue(result.isSuccess)
                assertEquals(SandboxState.RUNNING, sandbox.state.value)
            }

        @Test
        fun `stop transitions to STOPPED state`() =
            runTest {
                sandbox.start()

                val result = sandbox.stop()

                assertTrue(result.isSuccess)
                assertEquals(SandboxState.STOPPED, sandbox.state.value)
            }

        @Test
        fun `restart transitions through RESTARTING to RUNNING`() =
            runTest {
                sandbox.start()

                val result = sandbox.restart()

                assertTrue(result.isSuccess)
                assertEquals(SandboxState.RUNNING, sandbox.state.value)
            }

        @Test
        fun `double start is idempotent`() =
            runTest {
                sandbox.start()
                val result = sandbox.start()

                assertTrue(result.isSuccess)
                assertEquals(SandboxState.RUNNING, sandbox.state.value)
            }

        @Test
        fun `double stop is idempotent`() =
            runTest {
                sandbox.start()
                sandbox.stop()
                val result = sandbox.stop()

                assertTrue(result.isSuccess)
                assertEquals(SandboxState.STOPPED, sandbox.state.value)
            }
    }

    @Nested
    inner class HealthMetricsTests {
        @Test
        fun `initial health metrics are correct`() =
            runTest {
                sandbox.start()

                val metrics = sandbox.healthMetrics.value
                assertEquals(0, metrics.consecutiveErrors)
                assertEquals(0, metrics.errorCount)
                assertEquals(0, metrics.crashCount)
            }

        @Test
        fun `recordHeartbeat updates lastHeartbeat`() =
            runTest {
                sandbox.start()
                val initialMetrics = sandbox.healthMetrics.value

                delay(10) // Small delay to ensure time difference
                sandbox.recordHeartbeat()

                val updatedMetrics = sandbox.healthMetrics.value
                assertTrue(updatedMetrics.lastHeartbeat >= initialMetrics.lastHeartbeat)
            }

        @Test
        fun `recordSuccess resets consecutive errors`() =
            runTest {
                sandbox.start()

                // Record some errors
                sandbox.recordError(RuntimeException("Test error 1"))
                sandbox.recordError(RuntimeException("Test error 2"))
                assertEquals(2, sandbox.healthMetrics.value.consecutiveErrors)

                // Record success
                sandbox.recordSuccess()

                assertEquals(0, sandbox.healthMetrics.value.consecutiveErrors)
            }

        @Test
        fun `recordError increments error counters`() =
            runTest {
                sandbox.start()

                sandbox.recordError(RuntimeException("Test error"))

                val metrics = sandbox.healthMetrics.value
                assertEquals(1, metrics.consecutiveErrors)
                assertEquals(1, metrics.errorCount)
            }

        @Test
        fun `multiple errors increment consecutiveErrors`() =
            runTest {
                sandbox.start()

                sandbox.recordError(RuntimeException("Error 1"))
                sandbox.recordError(RuntimeException("Error 2"))
                sandbox.recordError(RuntimeException("Error 3"))

                val metrics = sandbox.healthMetrics.value
                assertEquals(3, metrics.consecutiveErrors)
                assertEquals(3, metrics.errorCount)
            }
    }

    @Nested
    inner class UnhealthyStateTests {
        @Test
        fun `exceeding maxConsecutiveErrors marks sandbox as UNHEALTHY`() =
            runTest {
                sandbox.start()

                // Record errors up to the threshold
                repeat(3) {
                    sandbox.recordError(RuntimeException("Error $it"))
                }

                assertEquals(SandboxState.UNHEALTHY, sandbox.state.value)
            }

        @Test
        fun `markUnhealthy changes state from RUNNING to UNHEALTHY`() =
            runTest {
                sandbox.start()

                sandbox.markUnhealthy()

                assertEquals(SandboxState.UNHEALTHY, sandbox.state.value)
            }

        @Test
        fun `markUnhealthy does nothing when not RUNNING`() =
            runTest {
                // Don't start the sandbox
                sandbox.markUnhealthy()

                assertEquals(SandboxState.STOPPED, sandbox.state.value)
            }
    }

    @Nested
    inner class DisabledStateTests {
        @Test
        fun `setDisabled changes state to DISABLED`() =
            runTest {
                sandbox.start()

                sandbox.setDisabled()

                assertEquals(SandboxState.DISABLED, sandbox.state.value)
            }

        @Test
        fun `setState allows direct state changes`() =
            runTest {
                sandbox.start()

                sandbox.setState(SandboxState.CRASHED)

                assertEquals(SandboxState.CRASHED, sandbox.state.value)
            }
    }

    @Nested
    inner class SandboxScopeTests {
        @Test
        fun `sandboxScope is available after start`() =
            runTest {
                sandbox.start()

                assertNotNull(sandbox.sandboxScope)
            }

        @Test
        fun `coroutines can be launched in sandboxScope`() =
            runTest {
                sandbox.start()
                var executed = false

                val job =
                    sandbox.sandboxScope.launch {
                        executed = true
                    }
                job.join()

                assertTrue(executed)
            }

        @Test
        fun `restart keeps the same sandboxScope instance`() =
            runTest {
                sandbox.start()
                val originalScope = sandbox.sandboxScope

                sandbox.restart()

                // Plugins read pluginScope once, in register(), and hand it to
                // components that outlive any restart. Handing out a fresh
                // scope here left all of them holding a cancelled one, and a
                // cancelled scope swallows launch() without running or
                // throwing - so the plugin went quietly inert until the user
                // reloaded it by hand.
                assertSame(originalScope, sandbox.sandboxScope)
                assertTrue(sandbox.sandboxScope.isActive)
            }

        @Test
        fun `work launched after a restart still runs`() =
            runTest {
                sandbox.start()
                val scopeCapturedAtRegisterTime = sandbox.sandboxScope

                sandbox.restart()

                val ran = CompletableDeferred<Boolean>()
                scopeCapturedAtRegisterTime.launch { ran.complete(true) }

                assertTrue(ran.await(), "the scope a plugin captured before the restart is dead")
            }

        @Test
        fun `restart cancels work that was in flight`() =
            runTest {
                sandbox.start()
                val started = CompletableDeferred<Unit>()
                val job =
                    sandbox.sandboxScope.launch {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                started.await()

                sandbox.restart()

                job.join()
                assertTrue(job.isCancelled)
            }

        @Test
        fun `a stopped sandbox runs nothing`() =
            runTest {
                sandbox.start()
                sandbox.stop()

                var executed = false
                sandbox.sandboxScope.launch { executed = true }.join()

                assertFalse(executed, "a stopped sandbox must not keep running plugin code")
            }

        @Test
        fun `starting again after a stop re-arms the same scope`() =
            runTest {
                sandbox.start()
                val scopeCapturedAtRegisterTime = sandbox.sandboxScope
                sandbox.stop()

                // What a disable/enable cycle does. The plugin is never handed
                // a new scope, so this one has to come back to life.
                sandbox.start()

                val ran = CompletableDeferred<Boolean>()
                scopeCapturedAtRegisterTime.launch { ran.complete(true) }

                assertSame(scopeCapturedAtRegisterTime, sandbox.sandboxScope)
                assertTrue(ran.await(), "enable left the plugin with a dead scope")
            }
    }

    @Nested
    inner class RestartAccountingTests {
        @Test
        fun `restart attempts accumulate across restarts`() =
            runTest {
                sandbox.start()

                sandbox.restart()
                sandbox.restart()

                // A restart that merely returned is not proof the plugin
                // recovered. Zeroing the counter here made maxRestartAttempts
                // unreachable, so a plugin could restart-loop forever with
                // every attempt logged as "attempt 1".
                assertEquals(2, sandbox.healthMetrics.value.restartAttempts)
            }

        @Test
        fun `resetRestartAttempts clears the counter`() =
            runTest {
                sandbox.start()
                sandbox.restart()

                sandbox.resetRestartAttempts()

                assertEquals(0, sandbox.healthMetrics.value.restartAttempts)
            }
    }

    @Nested
    inner class PluginExceptionTests {
        @Test
        fun `recordError wraps non-PluginException errors`() =
            runTest {
                sandbox.start()
                val originalError = RuntimeException("Original error")

                sandbox.recordError(originalError)

                // The error is wrapped internally - we verify by checking metrics increased
                assertEquals(1, sandbox.healthMetrics.value.errorCount)
            }

        @Test
        fun `PluginException preserves pluginId`() {
            val error = PluginException.createByPlugin("test-plugin", RuntimeException("Test"))

            assertEquals("test-plugin", error.pluginId)
        }

        @Test
        fun `PluginException getPluginId extracts correct id`() {
            val error = PluginException("my-plugin", message = "Test error")

            assertEquals("my-plugin", PluginException.getPluginId(error))
        }

        @Test
        fun `PluginException getPluginId returns null for non-PluginException`() {
            val error = RuntimeException("Regular error")

            assertEquals(null, PluginException.getPluginId(error))
        }
    }
}
