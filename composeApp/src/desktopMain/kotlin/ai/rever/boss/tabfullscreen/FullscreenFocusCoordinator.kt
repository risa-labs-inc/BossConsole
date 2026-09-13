package ai.rever.boss.tabfullscreen

import java.awt.Component
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Coordinates browser-focus retries on the EDT, bounded per request.
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
        hasFocus: () -> Boolean,
        attemptFocus: () -> Unit,
        onExhausted: () -> Unit,
    ) {
        if (!isCurrent()) return

        generation++
        cancelPendingRetry()
        val requestGeneration = generation

        fun tryFocus(attempt: Int) {
            when {
                requestGeneration != generation || !isCurrent() || hasFocus() -> {
                    return
                }

                attempt > maxAttempts -> {
                    onExhausted()
                }

                else -> {
                    attemptFocus()
                    // Acceptance is advisory. Check ownership on a later EDT tick,
                    // including after the final attempt, before reporting exhaustion.
                    pendingRetryCancellation =
                        scheduleRetry(retryDelayMs) {
                            if (requestGeneration == generation) {
                                pendingRetryCancellation = null
                            }
                            tryFocus(attempt + 1)
                        }
                }
            }
        }

        tryFocus(1)
    }

    fun cancel() {
        generation++
        cancelPendingRetry()
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

/** Stops queued focus recovery when the user switches away from the owning frame. */
internal class FullscreenFocusListener(
    private val isCurrent: () -> Boolean,
    private val requestFocus: () -> Unit,
    private val cancelFocus: () -> Unit,
) : WindowAdapter() {
    override fun windowGainedFocus(event: WindowEvent?) {
        if (isCurrent()) requestFocus()
    }

    override fun windowLostFocus(event: WindowEvent?) {
        // A disposed native frame can lose focus after its replacement overlay is active.
        // That stale event must not cancel the replacement's pending recovery.
        if (isCurrent()) cancelFocus()
    }
}

/** BrowserView delegates focus to its rendering child in JxBrowser 9.5.0. */
internal fun isFocusWithin(
    view: Component,
    focusOwner: Component?,
): Boolean = focusOwner != null && SwingUtilities.isDescendingFrom(focusOwner, view)
