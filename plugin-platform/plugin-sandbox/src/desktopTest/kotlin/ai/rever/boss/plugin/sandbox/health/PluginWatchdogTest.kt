package ai.rever.boss.plugin.sandbox.health

import ai.rever.boss.plugin.sandbox.PluginSandbox
import ai.rever.boss.plugin.sandbox.SandboxConfig
import ai.rever.boss.plugin.sandbox.SandboxState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [PluginWatchdog], centred on the question it exists to answer:
 * is a stale heartbeat evidence about the plugin, or about the host?
 *
 * The watchdog is driven with two injected clocks so a host suspend (wall
 * clock runs ahead of the monotonic one) and a host freeze (both run ahead of
 * the loop's own interval) can be reproduced exactly, without sleeping a
 * laptop or provoking a garbage collection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginWatchdogTest {
    private val config =
        SandboxConfig(
            heartbeatIntervalMs = 5_000,
            unhealthyThresholdMs = 15_000,
            stallGraceMs = 2_000,
            maxRestartAttempts = 3,
            // Comfortably more than the four checks a silent plugin takes to
            // time out, so a run of good checks cannot be ended by the very
            // timeout it was counting towards.
            healthyChecksToClearRestarts = 8,
        )

    /**
     * Clocks derived from the test scheduler's virtual time, each with a skew
     * the test can inject.
     *
     * - [frozenMs] advances both clocks: the process was stopped while awake
     *   (a long GC pause, a breakpoint).
     * - [suspendedMs] advances only the wall clock: the machine slept, which
     *   a monotonic clock does not count on macOS or Linux.
     */
    private class Clocks(
        private val scope: TestScope,
    ) {
        var frozenMs = 0L
        var suspendedMs = 0L

        fun monotonic(): Long = scope.testScheduler.currentTime + frozenMs

        fun wallClock(): Long = scope.testScheduler.currentTime + frozenMs + suspendedMs
    }

    private class FakeSandbox : PluginSandbox {
        override val pluginId: String = "test-plugin"

        private val _state = MutableStateFlow(SandboxState.RUNNING)
        override val state: StateFlow<SandboxState> = _state.asStateFlow()

        private val _healthMetrics = MutableStateFlow(PluginHealthMetrics.initial())
        override val healthMetrics: StateFlow<PluginHealthMetrics> = _healthMetrics.asStateFlow()

        override val sandboxScope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)

        var stopCount = 0
        var markUnhealthyCount = 0
        var resetRestartAttemptsCount = 0

        fun beatAt(wallClockMs: Long) {
            _healthMetrics.update { it.copy(lastHeartbeat = wallClockMs) }
        }

        fun setRestartAttempts(attempts: Int) {
            _healthMetrics.update { it.copy(restartAttempts = attempts) }
        }

        override suspend fun start(): Result<Unit> = Result.success(Unit)

        override suspend fun stop(): Result<Unit> {
            stopCount++
            _state.value = SandboxState.STOPPED
            return Result.success(Unit)
        }

        override suspend fun restart(): Result<Unit> = Result.success(Unit)

        override fun recordHeartbeat() = Unit

        override fun recordSuccess() = Unit

        override fun recordError(error: Throwable) = Unit

        override fun markUnhealthy() {
            markUnhealthyCount++
        }

        override fun resetHealth() = Unit

        override fun resetRestartAttempts() {
            resetRestartAttemptsCount++
            _healthMetrics.update { it.withRestartAttemptsCleared() }
        }
    }

    private class Harness(
        val sandbox: FakeSandbox,
        val clocks: Clocks,
        val watchdog: PluginWatchdog,
        val scope: TestScope,
    ) {
        val restartsRequested = mutableListOf<String>()

        /** Clock cost a restart incurs inside the tick that triggered it. */
        var restartCost: () -> Unit = {}

        /** Model a plugin that stays silent even after being restarted. */
        var stopBeatingOnRestart = false

        /** Run one watchdog check interval of virtual time. */
        fun tick(count: Int = 1) {
            repeat(count) {
                scope.testScheduler.advanceTimeBy(5_000)
                scope.runCurrent()
            }
        }

        /** Refresh the plugin's heartbeat to "now", as its heartbeat job does. */
        fun beat() = sandbox.beatAt(clocks.wallClock())
    }

    private fun TestScope.harness(): Harness {
        val sandbox = FakeSandbox()
        val clocks = Clocks(this)
        lateinit var harness: Harness
        val watchdog =
            PluginWatchdog(
                sandbox = sandbox,
                config = config,
                scope = this,
                onRestartRequested = {
                    harness.restartsRequested.add(it)
                    // Restarting is not free: the manager waits out a backoff
                    // and the sandbox blocks in awaitTermination, all inside
                    // this tick.
                    harness.restartCost()
                    // A real restart refreshes the heartbeat, which is what
                    // stops the watchdog asking again on the very next tick.
                    if (!harness.stopBeatingOnRestart) harness.beat()
                },
                monotonicMillis = clocks::monotonic,
                wallClockMillis = clocks::wallClock,
            )
        harness = Harness(sandbox, clocks, watchdog, this)
        harness.beat()
        watchdog.start()
        return harness
    }

    @Nested
    inner class HostStallTests {
        @Test
        fun `a sleeping machine does not restart the plugin`() =
            runTest {
                val h = harness()

                h.tick()
                h.beat()

                // The lid closes: 43s of skew on top of the 5s tick, so the
                // heartbeat ages the 48 seconds the real logs showed. Only the
                // wall clock moves.
                h.clocks.suspendedMs += 43_000
                h.tick()

                assertTrue(
                    h.restartsRequested.isEmpty(),
                    "a host suspend aged every heartbeat at once and must not be read as a plugin fault",
                )

                h.watchdog.stop()
            }

        @Test
        fun `a frozen host does not restart the plugin`() =
            runTest {
                val h = harness()

                h.tick()
                h.beat()

                // A stop-the-world pause of the same length, but both clocks
                // jump together.
                h.clocks.frozenMs += 43_000
                h.tick()

                assertTrue(h.restartsRequested.isEmpty())

                h.watchdog.stop()
            }

        @Test
        fun `the plugin gets a grace window to beat again after a stall`() =
            runTest {
                val h = harness()

                h.tick()
                h.beat()

                // Wake up with every heartbeat 48 seconds stale.
                h.clocks.suspendedMs += 43_000
                h.tick()

                // The plugin's own heartbeat job is overdue too and has not run
                // yet. The checks in this window must stay silent rather than
                // race it.
                h.tick(2)
                assertTrue(
                    h.restartsRequested.isEmpty(),
                    "checks resumed before the plugin's heartbeat job could run",
                )

                // It catches up, and the sandbox stays up.
                repeat(4) {
                    h.beat()
                    h.tick()
                }
                assertTrue(h.restartsRequested.isEmpty())

                h.watchdog.stop()
            }

        @Test
        fun `a chronically slow host does not mute the watchdog for ever`() =
            runTest {
                val h = harness()

                // Every tick overruns the grace: not a lid close, a host that
                // is simply always late. Re-arming the suppression on each one
                // would mean checkHealth never runs again for the life of the
                // watchdog - no restarts, no escalation, one INFO line per tick
                // as the only trace.
                repeat(12) {
                    h.clocks.frozenMs += 4_000
                    h.tick()
                }

                assertTrue(
                    h.restartsRequested.isNotEmpty(),
                    "a permanently slow host must degrade to late detection, not none",
                )

                h.watchdog.stop()
            }

        @Test
        fun `a host that stalls every other tick does not mute the watchdog either`() =
            runTest {
                val h = harness()

                // The pattern a consecutive-stall bound misses entirely: the
                // good ticks reset any such counter, while the 15s deadline
                // each stalled tick arms suppresses the ticks between them.
                repeat(16) { tick ->
                    if (tick % 2 == 0) h.clocks.frozenMs += 4_000
                    h.tick()
                }

                assertTrue(
                    h.restartsRequested.isNotEmpty(),
                    "alternating stalls suppressed every check without ever counting as consecutive",
                )

                h.watchdog.stop()
            }

        @Test
        fun `a plugin that never beats again is still restarted after the stall`() =
            runTest {
                val h = harness()

                h.tick()
                h.beat()

                h.clocks.suspendedMs += 43_000
                h.tick()

                // Grace expires and the plugin has gone quiet for real.
                h.tick(6)

                assertEquals(listOf("test-plugin"), h.restartsRequested)
                assertEquals(1, h.sandbox.markUnhealthyCount)

                h.watchdog.stop()
            }
    }

    @Nested
    inner class GenuineFailureTests {
        @Test
        fun `a wedged plugin is restarted`() =
            runTest {
                val h = harness()

                // No clock jumps, no heartbeats: this one really is gone.
                h.tick(4)

                assertEquals(listOf("test-plugin"), h.restartsRequested)

                h.watchdog.stop()
            }

        @Test
        fun `a plugin past its restart budget is still delegated, not stopped here`() =
            runTest {
                val h = harness()
                h.sandbox.setRestartAttempts(config.maxRestartAttempts)

                h.tick(4)

                // The watchdog used to enforce the budget itself, duplicating
                // the check in PluginSandboxManager.handleRestartRequest and
                // shadowing it - and the copy that ran was the one that stopped
                // the sandbox WITHOUT marking it disabled, so no fallback UI,
                // no notification and no isPluginDisabled. The manager's copy
                // does all three, so the decision has to reach it.
                assertEquals(listOf("test-plugin"), h.restartsRequested)
                assertEquals(0, h.sandbox.stopCount, "stopping is the manager's call, with its own bookkeeping")

                h.watchdog.stop()
            }

        @Test
        fun `ordinary jitter under the grace still gets the plugin checked`() =
            runTest {
                val h = harness()

                // A late tick, but only by a fraction of the grace - a busy
                // scheduler, not a stalled host. Discarding these would put the
                // original bug straight back.
                h.clocks.frozenMs += 1_500
                h.tick(4)

                assertEquals(listOf("test-plugin"), h.restartsRequested)

                h.watchdog.stop()
            }

        @Test
        fun `the time checkHealth spends restarting is not read as a host freeze`() =
            runTest {
                val h = harness()
                // What a real restart costs inside the tick: an exponential
                // backoff and then awaitTermination, comfortably over the
                // grace. Measured tick-to-tick it looked like a frozen host and
                // suppressed the next 15 seconds of checks on the one plugin
                // that actually needed watching.
                h.restartCost = { h.clocks.frozenMs += 4_000 }

                h.tick(4)
                assertEquals(1, h.restartsRequested.size)

                // Still quiet, so the next window must convict it again rather
                // than being written off as a stall.
                h.stopBeatingOnRestart = true
                h.tick(4)

                assertEquals(2, h.restartsRequested.size, "the watchdog's own restart cost suppressed the next check")

                h.watchdog.stop()
            }
    }

    @Nested
    inner class RestartCounterTests {
        @Test
        fun `sustained health forgives earlier restarts`() =
            runTest {
                val h = harness()
                h.sandbox.setRestartAttempts(2)

                repeat(config.healthyChecksToClearRestarts) {
                    h.beat()
                    h.tick()
                }

                assertEquals(1, h.sandbox.resetRestartAttemptsCount)
                assertEquals(0, h.sandbox.healthMetrics.value.restartAttempts)

                h.watchdog.stop()
            }

        @Test
        fun `a run of good checks broken by a timeout does not forgive anything`() =
            runTest {
                val h = harness()
                h.sandbox.setRestartAttempts(2)

                repeat(3) {
                    h.beat()
                    h.tick()
                }

                // Going quiet long enough to be restarted ends the run, however
                // many good checks it had accumulated.
                h.tick(4)
                assertEquals(listOf("test-plugin"), h.restartsRequested)

                h.beat()
                h.tick()

                assertEquals(0, h.sandbox.resetRestartAttemptsCount)

                h.watchdog.stop()
            }
    }
}
