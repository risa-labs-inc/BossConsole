package ai.rever.boss.plugin.sandbox.notification

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State manager for plugin toast notifications.
 *
 * Manages a queue of toast messages with automatic dismissal based on duration.
 * Implements [ToastController] for integration with [BossPluginNotificationService].
 *
 * @param scope CoroutineScope for managing toast timers
 * @param maxToasts Maximum number of toasts to display simultaneously (default: 3)
 *
 * The timers run on the scope's dispatcher (Main in the app), but `show` is also called
 * synchronously off that thread - the plugin sandbox manager's watchdog notifies listeners on
 * its own scope when a plugin restarts. Every read or write of the shared `paused` flag and
 * `dismissJobs` map is therefore guarded by [timerLock], which keeps a background `show` from
 * reading a stale `paused`, iterating a map a pause is mid-clearing, or scheduling a second
 * timer for a toast a resume is already sweeping.
 */
class PluginToastState(
    private val scope: CoroutineScope,
    private val maxToasts: Int = 3,
) : ToastController {
    private val _toasts = MutableStateFlow<List<ToastMessage>>(emptyList())

    /**
     * Currently visible toast messages.
     */
    val toasts: StateFlow<List<ToastMessage>> = _toasts.asStateFlow()

    // Track dismissal jobs to cancel them if toast is manually dismissed
    private val dismissJobs = mutableMapOf<String, Job>()

    // While paused, auto-dismiss timers do not run. Set by [pauseAutoDismiss] when the pointer is
    // over the toast area so a toast does not disappear out from under a user who is reading it or
    // reaching for its action/dismiss button.
    private var paused = false

    // One lock for `paused` and `dismissJobs`: both are read and written from the scope's
    // dispatcher and, for `show`, from whatever thread the plugin notification came in on.
    private val timerLock = Any()

    override fun show(message: ToastMessage) {
        synchronized(timerLock) {
            // Add to queue, respecting max limit. Atomic read-modify-write so a locked `show` and an
            // unlocked `dismiss`/`dismissAll` cannot lose each other's update to `_toasts`.
            _toasts.update { (it + message).takeLast(maxToasts) }

            // Schedule auto-dismiss based on duration - unless paused, in which case
            // [resumeAutoDismiss] will schedule it when the pointer leaves.
            if (!paused) {
                scheduleAutoDismiss(message)
            }
        }
    }

    private fun scheduleAutoDismiss(message: ToastMessage) {
        val delayMs = autoDismissDelayMs(message.duration) ?: return
        dismissJobs[message.id] =
            scope.launch {
                delay(delayMs)
                dismiss(message.id)
            }
    }

    /**
     * Freeze every auto-dismiss timer. Call while the pointer is over the toast area. Manual
     * dismissal and [dismissAll] still work; INDEFINITE toasts are unaffected as they never had a
     * timer. Idempotent.
     */
    fun pauseAutoDismiss() {
        synchronized(timerLock) {
            if (paused) return
            paused = true
            dismissJobs.values.forEach { it.cancel() }
            dismissJobs.clear()
        }
    }

    /**
     * Resume auto-dismissal after a [pauseAutoDismiss]. Each still-visible timed toast is given its
     * full duration afresh, so a toast the pointer just left does not vanish immediately. Idempotent.
     */
    fun resumeAutoDismiss() {
        synchronized(timerLock) {
            if (!paused) return
            paused = false
            _toasts.value.forEach { scheduleAutoDismiss(it) }
        }
    }

    override fun dismiss(id: String) {
        // Cancel any pending dismiss job
        synchronized(timerLock) {
            dismissJobs[id]?.cancel()
            dismissJobs.remove(id)
        }

        // Remove from the list. Atomic so it cannot clobber a concurrent `show` that added under
        // the lock; the two writers use different synchronization, so the CAS is what serializes them.
        _toasts.update { list -> list.filterNot { it.id == id } }
    }

    override fun dismissAll() {
        // Cancel all pending dismiss jobs
        synchronized(timerLock) {
            dismissJobs.values.forEach { it.cancel() }
            dismissJobs.clear()
        }

        // Clear all toasts
        _toasts.value = emptyList()
    }

    /**
     * Check if there are any visible toasts.
     */
    fun hasToasts(): Boolean = _toasts.value.isNotEmpty()

    /**
     * Get the count of visible toasts.
     */
    fun toastCount(): Int = _toasts.value.size
}

/**
 * The auto-dismiss delay for a toast [duration], or null when it never auto-dismisses
 * (INDEFINITE). Pulled out of the scheduler so the timings are pinned by a test.
 */
internal fun autoDismissDelayMs(duration: ToastDuration): Long? =
    when (duration) {
        ToastDuration.SHORT -> 3000L
        ToastDuration.LONG -> 6000L
        ToastDuration.INDEFINITE -> null
    }
