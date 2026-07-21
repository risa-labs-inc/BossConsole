package ai.rever.boss.plugin.sandbox.notification

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State manager for plugin toast notifications.
 *
 * Manages a queue of toast messages with automatic dismissal based on duration.
 * Implements [ToastController] for integration with [BossPluginNotificationService].
 *
 * @param scope CoroutineScope for managing toast timers
 * @param maxToasts Maximum number of toasts to display simultaneously (default: 3)
 */
class PluginToastState(
    private val scope: CoroutineScope,
    private val maxToasts: Int = 3
) : ToastController {

    private val _toasts = MutableStateFlow<List<ToastMessage>>(emptyList())

    /**
     * Currently visible toast messages.
     */
    val toasts: StateFlow<List<ToastMessage>> = _toasts.asStateFlow()

    // Track dismissal jobs to cancel them if toast is manually dismissed
    private val dismissJobs = mutableMapOf<String, Job>()

    override fun show(message: ToastMessage) {
        // Add to queue, respecting max limit
        _toasts.value = (_toasts.value + message).takeLast(maxToasts)

        // Schedule auto-dismiss based on duration
        if (message.duration != ToastDuration.INDEFINITE) {
            val delayMs = when (message.duration) {
                ToastDuration.SHORT -> 3000L
                ToastDuration.LONG -> 6000L
                ToastDuration.INDEFINITE -> Long.MAX_VALUE
            }

            dismissJobs[message.id] = scope.launch {
                delay(delayMs)
                dismiss(message.id)
            }
        }
    }

    override fun dismiss(id: String) {
        // Cancel any pending dismiss job
        dismissJobs[id]?.cancel()
        dismissJobs.remove(id)

        // Remove from the list
        _toasts.value = _toasts.value.filterNot { it.id == id }
    }

    override fun dismissAll() {
        // Cancel all pending dismiss jobs
        dismissJobs.values.forEach { it.cancel() }
        dismissJobs.clear()

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
