package ai.rever.boss.plugin.sandbox.notification

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins hover-pause of the toast auto-dismiss timers: while [PluginToastState.pauseAutoDismiss] is in
 * effect a timed toast does not disappear, and [PluginToastState.resumeAutoDismiss] gives each
 * still-visible toast its full duration afresh rather than letting a just-left toast vanish at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginToastAutoDismissPauseTest {
    private lateinit var testScope: TestScope
    private lateinit var toastState: PluginToastState

    @BeforeEach
    fun setUp() {
        testScope = TestScope(StandardTestDispatcher())
        toastState = PluginToastState(testScope, maxToasts = 3)
    }

    private fun short(id: String) =
        ToastMessage(
            id = id,
            type = ToastType.INFO,
            title = id,
            message = id,
            duration = ToastDuration.SHORT,
        )

    private fun indefinite(id: String) =
        ToastMessage(
            id = id,
            type = ToastType.INFO,
            title = id,
            message = id,
            duration = ToastDuration.INDEFINITE,
        )

    @Test
    fun `a dismiss of an absent id never drops a concurrently shown toast`() {
        // Regression for the lost-update race: `show` writes `_toasts` under the lock, while
        // `dismiss` rewrites `_toasts` too. If either does a plain `_toasts.value = _toasts.value...`
        // read-modify-write, a `dismiss` for an unrelated id can overwrite a concurrent `show` with a
        // stale snapshot and silently drop a toast that was never dismissed. Routing both through
        // `_toasts.update { }` makes each mutation an atomic CAS, so every shown toast survives.
        // INDEFINITE toasts schedule no timers, so this exercises only the `_toasts` contention.
        val shows = 200
        val pool = Executors.newFixedThreadPool(8)
        try {
            repeat(20) {
                val state = PluginToastState(testScope, maxToasts = Int.MAX_VALUE)
                val startGate = CountDownLatch(1)
                val done = CountDownLatch(shows * 2)
                repeat(shows) { i ->
                    pool.execute {
                        startGate.await()
                        state.show(indefinite("toast-$i"))
                        done.countDown()
                    }
                    pool.execute {
                        startGate.await()
                        state.dismiss("absent-$i")
                        done.countDown()
                    }
                }
                startGate.countDown()
                assertTrue(done.await(30, TimeUnit.SECONDS), "workers did not finish in time")
                assertEquals(
                    shows,
                    state.toastCount(),
                    "a dismiss of an absent id must not clobber a concurrent show",
                )
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `INDEFINITE has no auto-dismiss delay`() {
        assertEquals(3000L, autoDismissDelayMs(ToastDuration.SHORT))
        assertEquals(6000L, autoDismissDelayMs(ToastDuration.LONG))
        assertNull(autoDismissDelayMs(ToastDuration.INDEFINITE))
    }

    @Test
    fun `a paused toast does not auto-dismiss`() =
        testScope.runTest {
            toastState.show(short("t1"))
            advanceTimeBy(1000)
            toastState.pauseAutoDismiss()

            // Well past the 3s SHORT duration while paused.
            advanceTimeBy(10_000)
            assertEquals(1, toastState.toastCount(), "A paused toast must stay up.")
        }

    @Test
    fun `resume restarts the full duration`() =
        testScope.runTest {
            toastState.show(short("t1"))
            advanceTimeBy(2000)
            toastState.pauseAutoDismiss()
            advanceTimeBy(10_000)
            toastState.resumeAutoDismiss()

            // Only part of a fresh full duration has elapsed: still up.
            advanceTimeBy(2999)
            assertEquals(1, toastState.toastCount(), "Resume should grant the full duration afresh.")

            advanceTimeBy(2)
            assertEquals(0, toastState.toastCount(), "After the fresh full duration it auto-dismisses.")
        }

    @Test
    fun `a toast shown while paused is scheduled only on resume`() =
        testScope.runTest {
            toastState.pauseAutoDismiss()
            toastState.show(short("t1"))

            advanceTimeBy(10_000)
            assertEquals(1, toastState.toastCount(), "No timer runs while paused.")

            toastState.resumeAutoDismiss()
            advanceTimeBy(3001)
            assertEquals(0, toastState.toastCount(), "Resume schedules the deferred toast.")
        }

    @Test
    fun `a background show racing a resume is scheduled exactly once`() =
        testScope.runTest {
            // `show` can arrive from the plugin sandbox manager's scope (e.g. a plugin-restart
            // notification) while the pointer has just left, i.e. while `resumeAutoDismiss` is
            // sweeping. The lock must keep that from producing a toast with no timer, a
            // ConcurrentModificationException, or two timers for one toast.
            repeat(25) {
                val state = PluginToastState(testScope, maxToasts = 3)
                state.show(short("foreground"))
                advanceTimeBy(1000)
                state.pauseAutoDismiss()

                val errors = Collections.synchronizedList(mutableListOf<Throwable>())
                val thread =
                    Thread {
                        try {
                            state.show(short("background"))
                        } catch (
                            @Suppress("TooGenericExceptionCaught") t: Throwable,
                        ) {
                            errors.add(t)
                        }
                    }
                thread.start()
                state.resumeAutoDismiss()
                thread.join()

                assertTrue(errors.isEmpty(), "concurrent show must not throw: ${errors.firstOrNull()}")
                advanceTimeBy(2999)
                assertEquals(2, state.toastCount(), "both toasts keep their fresh full duration")
                advanceTimeBy(2)
                assertEquals(0, state.toastCount(), "each toast is dismissed exactly once")
            }
        }
}
