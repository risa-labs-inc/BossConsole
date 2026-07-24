package ai.rever.boss.components.bars.horizontal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages temporary status messages shown in the bottom bar.
 * Messages automatically disappear after a configurable duration.
 *
 * Thread-safe: all methods can be called from any thread (EDT, JxBrowser, Main).
 * Operations are dispatched to the Main thread to ensure thread safety.
 */
object StatusMessageManager {
    private val _currentMessage = MutableStateFlow<String?>(null)
    val currentMessage: StateFlow<String?> = _currentMessage.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentMessageJob: Job? = null

    /**
     * Show a temporary status message.
     * Thread-safe: can be called from any thread.
     *
     * @param message The message to display
     * @param durationMs How long to show the message (default 2 seconds)
     */
    fun showMessage(
        message: String,
        durationMs: Long = 2000,
    ) {
        scope.launch {
            currentMessageJob?.cancel()
            currentMessageJob =
                scope.launch {
                    _currentMessage.value = message
                    delay(durationMs)
                    _currentMessage.value = null
                }
        }
    }

    /**
     * Clear the current message immediately.
     * Thread-safe: can be called from any thread.
     */
    fun clearMessage() {
        scope.launch {
            currentMessageJob?.cancel()
            _currentMessage.value = null
        }
    }
}
