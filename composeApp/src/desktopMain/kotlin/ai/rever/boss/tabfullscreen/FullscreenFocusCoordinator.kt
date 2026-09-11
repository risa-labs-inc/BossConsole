package ai.rever.boss.tabfullscreen

import javax.swing.Timer

/**
 * Coordinates bounded browser-focus retries for one fullscreen lifecycle.
 *
 * Each new current request invalidates callbacks from an older request. [cancel] also
 * invalidates already-queued callbacks, even when cancelling their timer races
 * with delivery on the EDT.
 */
internal class FullscreenFocusCoordinator(
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val retryDelayMs: Int = DEFAULT_RETRY_DELAY_MS,
    private val scheduleRetry: (Int, () -> Unit) -> (() -> Unit) = ::scheduleSwingRetry,
) {
    private var generation = 0L
    private var pendingRetryCancellation: (() -> Unit)? = null

    init {
        require(maxAttempts > 0)
        require(retryDelayMs >= 0)
    }

    fun requestFocus(
        isCurrent: () -> Boolean,
        attemptFocus: () -> Boolean,
        onExhausted: () -> Unit,
    ) {
        if (!isCurrent()) return

        generation++
        cancelPendingRetry()
        attemptFocus(
            requestGeneration = generation,
            attempt = 1,
            isCurrent = isCurrent,
            focus = attemptFocus,
            onExhausted = onExhausted,
        )
    }

    fun cancel() {
        generation++
        cancelPendingRetry()
    }

    private fun attemptFocus(
        requestGeneration: Long,
        attempt: Int,
        isCurrent: () -> Boolean,
        focus: () -> Boolean,
        onExhausted: () -> Unit,
    ) {
        when {
            requestGeneration != generation || !isCurrent() -> {
                return
            }

            focus() -> {
                return
            }

            attempt >= maxAttempts -> {
                onExhausted()
            }

            else -> {
                pendingRetryCancellation =
                    scheduleRetry(retryDelayMs) {
                        if (requestGeneration == generation) {
                            pendingRetryCancellation = null
                        }
                        attemptFocus(
                            requestGeneration = requestGeneration,
                            attempt = attempt + 1,
                            isCurrent = isCurrent,
                            focus = focus,
                            onExhausted = onExhausted,
                        )
                    }
            }
        }
    }

    private fun cancelPendingRetry() {
        pendingRetryCancellation?.invoke()
        pendingRetryCancellation = null
    }

    private companion object {
        const val DEFAULT_MAX_ATTEMPTS = 4
        const val DEFAULT_RETRY_DELAY_MS = 75
    }
}

private fun scheduleSwingRetry(
    delayMs: Int,
    action: () -> Unit,
): () -> Unit {
    val timer =
        Timer(delayMs) {
            action()
        }.apply {
            isRepeats = false
            start()
        }
    return timer::stop
}
