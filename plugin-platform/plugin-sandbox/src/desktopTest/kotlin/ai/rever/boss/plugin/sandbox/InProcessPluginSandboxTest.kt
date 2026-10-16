package ai.rever.boss.plugin.sandbox

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogEntry
import ai.rever.boss.plugin.logging.LogLevel
import ai.rever.boss.plugin.logging.LogListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.measureTimeMillis
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

    /**
     * The sandbox-owned portion of teardown: cooperative sandbox coroutines finish
     * before [InProcessPluginSandbox.stop] returns. Independent plugin scopes are outside this test.
     *
     * The distinction is the bug. `job.cancel()` marks and returns; the coroutines are still
     * unwinding afterwards. Nothing else in `stop()` covered them either - `awaitTermination`
     * bounds tasks running on the sandbox's own pool, and a coroutine parked at a suspension point
     * off that pool is not a task the pool has ever seen, so the pool terminated instantly while
     * the coroutine was very much alive. The caller then closed the plugin's classloader and the
     * coroutine resumed into classes that no longer resolved.
     *
     * These tests therefore park a coroutine OFF the sandbox pool deliberately, and assert on when
     * its own cleanup ran rather than on how the wait is implemented.
     */
    @Nested
    inner class TeardownJoinTests {
        /**
         * The regression test. It fails against `cancel()` alone and passes against
         * `cancel()` + a bounded `join()`.
         */
        @Test
        fun `stop waits for a coroutine suspended off the sandbox pool to finish unwinding`() =
            runBlocking {
                sandbox.start()
                val parked = CompletableDeferred<Unit>()
                val unwound = AtomicBoolean(false)

                sandbox.sandboxScope.launch {
                    try {
                        // Off the pool on purpose: this is what awaitTermination cannot see.
                        withContext(Dispatchers.IO) {
                            parked.complete(Unit)
                            awaitCancellation()
                        }
                    } finally {
                        // A real plugin teardown suspends - closing a pty, flushing a session -
                        // and runs after cancellation, so it needs NonCancellable to get there.
                        withContext(NonCancellable + Dispatchers.IO) {
                            delay(UNWIND_MS)
                            unwound.set(true)
                        }
                    }
                }
                parked.await()

                sandbox.stop()

                assertTrue(
                    unwound.get(),
                    "stop() returned while a plugin coroutine was still unwinding - the caller " +
                        "would now close its classloader underneath it",
                )
            }

        /**
         * A plugin can decline to be cancelled, and hanging an unload on one for ever would be
         * worse than the fault it is trying to avoid. The wait gives up and lets the unload run.
         */
        @Test
        fun `stop is bounded when a plugin declines to be cancelled`() =
            runBlocking {
                sandbox.start()
                val stubborn = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                sandbox.sandboxScope.launch {
                    withContext(NonCancellable + Dispatchers.IO) {
                        stubborn.complete(Unit)
                        release.await()
                    }
                }
                stubborn.await()

                try {
                    val elapsed = measureTimeMillis { assertTrue(sandbox.stop().isSuccess) }

                    // Waited at all - a stop that returned instantly would mean the join was
                    // skipped, which is the bug this pins.
                    assertTrue(elapsed >= WAITED_AT_ALL_MS, "stop() did not wait at all: ${elapsed}ms")
                    // ...and gave up. Generous: the join's bound and the pool teardown's own
                    // bound sit end to end, and this only has to fail an UNbounded wait.
                    assertTrue(elapsed < BOUNDED_CEILING_MS, "stop() was not bounded: ${elapsed}ms")
                } finally {
                    release.complete(Unit)
                }
            }

        /** Giving up is never silent: the line has to name the plugin and the bound it spent. */
        @Test
        fun `an expired teardown wait is logged with the plugin and the timeout`() =
            runBlocking {
                val warnings = CopyOnWriteArrayList<LogEntry>()
                val listener = LogListener { entry -> if (entry.level == LogLevel.WARN) warnings += entry }
                val restoreLevel = BossLogger.globalLevel
                BossLogger.setGlobalLevel(LogLevel.WARN)
                BossLogger.addListener(listener)

                val release = CompletableDeferred<Unit>()
                try {
                    sandbox.start()
                    val stubborn = CompletableDeferred<Unit>()
                    sandbox.sandboxScope.launch {
                        withContext(NonCancellable + Dispatchers.IO) {
                            stubborn.complete(Unit)
                            release.await()
                        }
                    }
                    stubborn.await()

                    sandbox.stop()
                } finally {
                    release.complete(Unit)
                    BossLogger.removeListener(listener)
                    BossLogger.setGlobalLevel(restoreLevel)
                }

                val expiry =
                    assertNotNull(
                        warnings.firstOrNull { it.message.contains("still unwinding") },
                        "a teardown wait that expired said nothing: $warnings",
                    )
                assertEquals("test-plugin", expiry.data?.get("pluginId"))
                val reportedTimeout =
                    assertNotNull(
                        expiry.data
                            ?.get("timeoutMs")
                            ?.toString()
                            ?.toLongOrNull(),
                        "the warning did not report the bound it spent",
                    )
                assertTrue(reportedTimeout > 0, "the warning reported a nonsense bound: $reportedTimeout")
            }

        /**
         * The asymmetry is deliberate. A restart swaps the pool underneath the SAME classloader,
         * so a coroutine that resumes late still resolves its own classes and there is nothing for
         * a wait to protect. Waiting here would only make every watchdog restart pay a stubborn
         * plugin's full bound, inside the RESTARTING state the sandbox must not stop in.
         */
        @Test
        fun `restart does not wait on a plugin that declines to be cancelled`() =
            runBlocking {
                sandbox.start()
                val stubborn = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                sandbox.sandboxScope.launch {
                    withContext(NonCancellable + Dispatchers.IO) {
                        stubborn.complete(Unit)
                        release.await()
                    }
                }
                stubborn.await()

                try {
                    val elapsed = measureTimeMillis { assertTrue(sandbox.restart().isSuccess) }

                    assertTrue(
                        elapsed < WAITED_AT_ALL_MS,
                        "restart waited on a cancelled coroutine it had no reason to: ${elapsed}ms",
                    )
                    assertEquals(SandboxState.RUNNING, sandbox.state.value)
                } finally {
                    release.complete(Unit)
                }
            }
    }

    @Nested
    inner class RetiredScopeTeardownTests {
        @Test
        fun `stop waits for cleanup belonging to a previous restart generation`() =
            runBlocking {
                sandbox.start()
                val parked = CompletableDeferred<Unit>()
                val cleanupStarted = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val oldJob =
                    sandbox.sandboxScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                parked.complete(Unit)
                                awaitCancellation()
                            }
                        } finally {
                            withContext(NonCancellable + Dispatchers.IO) {
                                cleanupStarted.complete(Unit)
                                release.await()
                            }
                        }
                    }
                parked.await()
                sandbox.restart().getOrThrow()
                cleanupStarted.await()
                val stopped = CompletableDeferred<Unit>()
                val stopping =
                    launch(Dispatchers.Default) {
                        sandbox.stop().getOrThrow()
                        stopped.complete(Unit)
                    }
                try {
                    assertEquals(null, withTimeoutOrNull(300) { stopped.await() })
                    release.complete(Unit)
                    assertNotNull(withTimeoutOrNull(5_000) { stopped.await() })
                    assertTrue(oldJob.isCompleted)
                } finally {
                    release.complete(Unit)
                    stopping.join()
                    oldJob.join()
                }
            }

        @Test
        fun `cancelled caller still drains sandbox cleanup`() =
            runBlocking {
                sandbox.start()
                val parked = CompletableDeferred<Unit>()
                val unwound = AtomicBoolean(false)
                val worker =
                    sandbox.sandboxScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                parked.complete(Unit)
                                awaitCancellation()
                            }
                        } finally {
                            withContext(NonCancellable + Dispatchers.IO) {
                                delay(300)
                                unwound.set(true)
                            }
                        }
                    }
                parked.await()
                val caller =
                    launch {
                        coroutineContext[kotlinx.coroutines.Job]!!.cancel()
                        sandbox.stop()
                        assertTrue(unwound.get(), "cancelled unload caller skipped the teardown wait")
                    }
                caller.join()
                worker.join()
            }
    }

    @Nested
    inner class CancelledRestartTests {
        /**
         * The Restart buttons run on a Compose `rememberCoroutineScope`, so
         * closing the tab or switching side panels mid-restart cancels this.
         *
         * The window is widened deliberately: a thread blocked in a latch is
         * not interruptible by coroutine cancellation, so the retiring pool
         * cannot terminate and `awaitTermination` waits its full timeout.
         */
        @Test
        fun `a cancelled restart does not strand the sandbox in RESTARTING`() =
            runBlocking {
                sandbox.start()
                val holdThePool = CountDownLatch(1)
                sandbox.sandboxScope.launch { holdThePool.await() }
                // Let it actually occupy a pool thread before the swap.
                withTimeoutOrNull(2_000) {
                    while (sandbox.healthMetrics.value.lastHeartbeat == 0L) delay(10)
                }

                val restart = launch(Dispatchers.Default) { sandbox.restart() }
                // Past the swap: the state has moved off STOPPED either way -
                // to RUNNING with the fix, to RESTARTING without it.
                withTimeoutOrNull(2_000) {
                    while (sandbox.state.value == SandboxState.STOPPED) delay(5)
                }
                restart.cancel()
                restart.join()
                holdThePool.countDown()

                // RESTARTING is the state PluginWatchdog skips outright, so a
                // sandbox left there is never restarted, never marked
                // unhealthy, and shows no fallback UI. It is invisible to every
                // recovery path there is.
                assertEquals(SandboxState.RUNNING, sandbox.state.value)

                val ran = CompletableDeferred<Boolean>()
                sandbox.sandboxScope.launch { ran.complete(true) }
                assertTrue(
                    withTimeoutOrNull(5_000) { ran.await() } == true,
                    "the scope did not survive a cancelled restart",
                )
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

    private companion object {
        /**
         * How long a cancelled coroutine's cleanup takes in
         * [TeardownJoinTests]. Long enough that a `stop()` which did not wait
         * for it returns first, short enough to keep the test quick.
         */
        const val UNWIND_MS = 300L

        /**
         * Above scheduling noise and below the sandbox's own teardown bound, so
         * it reads as "a wait happened" from either side: a `stop()` that
         * waited exceeds it, a `restart()` that correctly did not stays under.
         */
        const val WAITED_AT_ALL_MS = 1_000L

        /**
         * Only has to fail an UNBOUNDED wait, so it sits well clear of the
         * teardown's own bounds rather than measuring them.
         */
        const val BOUNDED_CEILING_MS = 10_000L
    }
}
