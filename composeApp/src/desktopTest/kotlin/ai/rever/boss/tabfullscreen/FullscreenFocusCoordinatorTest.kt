package ai.rever.boss.tabfullscreen

import java.util.ArrayDeque
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
            attemptFocus = {
                attempts++
                attempts == 3
            },
            onExhausted = { exhausted = true },
        )

        assertEquals(1, attempts)
        retries.runNext()
        retries.runNext()

        assertEquals(3, attempts)
        assertEquals(listOf(25, 25), retries.delays)
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
            attemptFocus = {
                attempts++
                false
            },
            onExhausted = { exhausted++ },
        )

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
            attemptFocus = {
                attempts++
                false
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
            attemptFocus = {
                attempts++
                false
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
            attemptFocus = {
                attempts++
                false
            },
            onExhausted = {},
        )

        coordinator.requestFocus(
            isCurrent = { true },
            attemptFocus = {
                attempts++
                true
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
            attemptFocus = {
                attempts++
                false
            },
            onExhausted = {},
        )

        coordinator.requestFocus(
            isCurrent = { true },
            attemptFocus = {
                attempts++
                false
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
            attemptFocus = {
                currentAttempts++
                currentAttempts == 2
            },
            onExhausted = {},
        )

        coordinator.requestFocus(
            isCurrent = { false },
            attemptFocus = {
                error("Stale request attempted focus")
            },
            onExhausted = {
                error("Stale request reached exhaustion")
            },
        )

        assertEquals(listOf(0), retries.cancellationCounts)

        retries.runNext()

        assertEquals(2, currentAttempts)
    }
}
