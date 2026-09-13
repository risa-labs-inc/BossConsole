package ai.rever.boss.tabfullscreen

import java.util.ArrayDeque
import javax.swing.JButton
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FullscreenFocusCoordinatorTest {
    private class RetryQueue {
        private val callbacks = ArrayDeque<() -> Unit>()
        val delays = mutableListOf<Int>()
        val cancellationCounts = mutableListOf<Int>()

        fun schedule(
            delayMs: Int,
            action: () -> Unit,
        ): () -> Unit {
            delays += delayMs
            callbacks.addLast(action)
            // Deliberately leave the callback queued. This simulates cancellation
            // racing with an already-deliverable Swing timer event.
            val cancellationIndex = cancellationCounts.size
            cancellationCounts += 0

            return {
                cancellationCounts[cancellationIndex]++
            }
        }

        fun runNext() {
            callbacks.removeFirst().invoke()
        }

        fun hasPending(): Boolean = callbacks.isNotEmpty()
    }

    @Test
    fun `failed focus is retried until it succeeds`() {
        val retries = RetryQueue()
        val coordinator =
            FullscreenFocusCoordinator(
                maxAttempts = 4,
                retryDelayMs = 25,
                scheduleRetry = retries::schedule,
            )
        var attempts = 0
        var exhausted = false

        coordinator.requestFocus(
            isCurrent = { true },
            hasFocus = { attempts == 3 },
            attemptFocus = {
                attempts++
            },
            onExhausted = { exhausted = true },
        )

        assertEquals(1, attempts)
        retries.runNext()
        retries.runNext()
        retries.runNext()

        assertEquals(3, attempts)
        assertEquals(listOf(25, 25, 25), retries.delays)
        assertFalse(exhausted)
        assertFalse(retries.hasPending())
    }

    @Test
    fun `focus attempts stop at the configured limit`() {
        val retries = RetryQueue()
        val coordinator =
            FullscreenFocusCoordinator(
                maxAttempts = 3,
                retryDelayMs = 25,
                scheduleRetry = retries::schedule,
            )
        var attempts = 0
        var exhausted = 0

        coordinator.requestFocus(
            isCurrent = { true },
            hasFocus = { false },
            attemptFocus = {
                attempts++
            },
            onExhausted = { exhausted++ },
        )

        retries.runNext()
        retries.runNext()
        retries.runNext()

        assertEquals(3, attempts)
        assertEquals(1, exhausted)
        assertFalse(retries.hasPending())
    }

    @Test
    fun `cancel rejects a retry already queued for delivery`() {
        val retries = RetryQueue()
        val coordinator =
            FullscreenFocusCoordinator(
                scheduleRetry = retries::schedule,
            )
        var attempts = 0

        coordinator.requestFocus(
            isCurrent = { true },
            hasFocus = { false },
            attemptFocus = {
                attempts++
            },
            onExhausted = {},
        )
        assertTrue(retries.hasPending())

        coordinator.cancel()
        retries.runNext()

        assertEquals(1, attempts)
    }

    @Test
    fun `retry is rejected after fullscreen lifecycle becomes stale`() {
        val retries = RetryQueue()
        val coordinator =
            FullscreenFocusCoordinator(
                scheduleRetry = retries::schedule,
            )
        var current = true
        var attempts = 0

        coordinator.requestFocus(
            isCurrent = { current },
            hasFocus = { false },
            attemptFocus = {
                attempts++
            },
            onExhausted = {},
        )

        current = false
        retries.runNext()

        assertEquals(1, attempts)
    }

    @Test
    fun `new focus request invalidates callback from older request`() {
        val retries = RetryQueue()
        val coordinator =
            FullscreenFocusCoordinator(
                scheduleRetry = retries::schedule,
            )
        var attempts = 0

        coordinator.requestFocus(
            isCurrent = { true },
            hasFocus = { attempts == 2 },
            attemptFocus = {
                attempts++
            },
            onExhausted = {},
        )

        coordinator.requestFocus(
            isCurrent = { true },
            hasFocus = { attempts == 2 },
            attemptFocus = {
                attempts++
            },
            onExhausted = {},
        )

        retries.runNext()

        assertEquals(2, attempts)
    }

    @Test
    fun `stale callback does not discard newer retry cancellation`() {
        val retries = RetryQueue()
        val coordinator =
            FullscreenFocusCoordinator(
                scheduleRetry = retries::schedule,
            )
        var attempts = 0

        coordinator.requestFocus(
            isCurrent = { true },
            hasFocus = { false },
            attemptFocus = {
                attempts++
            },
            onExhausted = {},
        )

        coordinator.requestFocus(
            isCurrent = { true },
            hasFocus = { false },
            attemptFocus = {
                attempts++
            },
            onExhausted = {},
        )

        assertEquals(listOf(1, 0), retries.cancellationCounts)

        retries.runNext()
        coordinator.cancel()

        assertEquals(listOf(1, 1), retries.cancellationCounts)

        retries.runNext()
        assertEquals(2, attempts)
    }

    @Test
    fun `stale request does not cancel current retry`() {
        val retries = RetryQueue()
        val coordinator =
            FullscreenFocusCoordinator(
                scheduleRetry = retries::schedule,
            )
        var currentAttempts = 0

        coordinator.requestFocus(
            isCurrent = { true },
            hasFocus = { currentAttempts == 2 },
            attemptFocus = {
                currentAttempts++
            },
            onExhausted = {},
        )

        coordinator.requestFocus(
            isCurrent = { false },
            hasFocus = { currentAttempts == 2 },
            attemptFocus = {
                error("Stale request attempted focus")
            },
            onExhausted = {
                error("Stale request reached exhaustion")
            },
        )

        assertEquals(listOf(0), retries.cancellationCounts)

        retries.runNext()
        retries.runNext()

        assertEquals(2, currentAttempts)
    }

    @Test
    fun `switching away cancels queued focus recovery and returning restarts it`() {
        val retries = RetryQueue()
        val coordinator = FullscreenFocusCoordinator(scheduleRetry = retries::schedule)
        var attempts = 0
        val listener =
            FullscreenFocusListener(
                isCurrent = { true },
                requestFocus = {
                    coordinator.requestFocus(
                        isCurrent = { true },
                        hasFocus = { attempts == 2 },
                        attemptFocus = { attempts++ },
                        onExhausted = {},
                    )
                },
                cancelFocus = coordinator::cancel,
            )

        listener.windowGainedFocus(null)
        listener.windowLostFocus(null)
        retries.runNext()
        assertEquals(1, attempts, "A queued timer must not steal focus after switching away")
        assertEquals(listOf(1), retries.cancellationCounts)

        listener.windowGainedFocus(null)
        assertEquals(2, attempts)
        retries.runNext()
        assertFalse(retries.hasPending())
    }

    @Test
    fun `stale frame focus events cannot cancel replacement recovery`() {
        val retries = RetryQueue()
        val coordinator = FullscreenFocusCoordinator(scheduleRetry = retries::schedule)
        var attempts = 0
        coordinator.requestFocus(
            isCurrent = { true },
            hasFocus = { attempts == 2 },
            attemptFocus = { attempts++ },
            onExhausted = {},
        )
        val staleListener =
            FullscreenFocusListener(
                isCurrent = { false },
                requestFocus = { error("Stale frame must not request focus") },
                cancelFocus = coordinator::cancel,
            )

        staleListener.windowLostFocus(null)
        staleListener.windowGainedFocus(null)
        assertEquals(listOf(0), retries.cancellationCounts)
        retries.runNext()
        assertEquals(2, attempts)
    }

    @Test
    fun `accepted requests without actual focus exhaust the shipped budget`() {
        val retries = RetryQueue()
        val coordinator = FullscreenFocusCoordinator(scheduleRetry = retries::schedule)
        var acceptedRequests = 0
        var exhausted = 0
        val requestFocusInWindow = { true }
        coordinator.requestFocus(
            isCurrent = { true },
            hasFocus = { false },
            attemptFocus = { if (requestFocusInWindow()) acceptedRequests++ },
            onExhausted = { exhausted++ },
        )
        while (retries.hasPending()) retries.runNext()

        assertEquals(4, acceptedRequests)
        assertEquals(listOf(75, 75, 75, 75), retries.delays)
        assertEquals(1, exhausted)
    }

    @Test
    fun `focus delivered after the final request is verified before exhaustion`() {
        val retries = RetryQueue()
        val coordinator = FullscreenFocusCoordinator(maxAttempts = 1, scheduleRetry = retries::schedule)
        var focused = false
        var requests = 0
        var exhausted = false
        coordinator.requestFocus(
            isCurrent = { true },
            hasFocus = { focused },
            attemptFocus = { requests++ },
            onExhausted = { exhausted = true },
        )
        assertFalse(exhausted)
        focused = true
        retries.runNext()

        assertEquals(1, requests)
        assertFalse(exhausted)
        assertFalse(retries.hasPending())
    }

    @Test
    fun `an already focused view needs no request or retry`() {
        val retries = RetryQueue()
        val coordinator = FullscreenFocusCoordinator(scheduleRetry = retries::schedule)
        coordinator.requestFocus(
            isCurrent = { true },
            hasFocus = { true },
            attemptFocus = { error("Already focused") },
            onExhausted = { error("Already focused") },
        )
        assertFalse(retries.hasPending())
    }

    @Test
    fun `focus on the rendering child counts but another view does not`() {
        val view = JPanel()
        val renderingChild = JPanel()
        val nestedChild = JButton()
        view.add(renderingChild)
        renderingChild.add(nestedChild)
        assertTrue(isFocusWithin(view, view))
        assertTrue(isFocusWithin(view, renderingChild))
        assertTrue(isFocusWithin(view, nestedChild))
        assertFalse(isFocusWithin(view, JPanel()))
        assertFalse(isFocusWithin(view, null))
    }
}
