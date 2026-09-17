package ai.rever.boss.plugin.sandbox.notification

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
