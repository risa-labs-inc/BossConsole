package ai.rever.boss.window

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf

private val windowRunnerStateRegistryLogger = BossLogger.forComponent("WindowRunnerStateRegistry")

/**
 * CompositionLocal to provide WindowRunnerState to descendant composables.
 * This allows components like BossTopRunBar to access the window-specific runner state.
 */
val LocalWindowRunnerState = compositionLocalOf<WindowRunnerState?> { null }

/**
 * Registry for per-window runner states.
 * Each window has its own independent selected configuration while sharing
 * the global configurations list and detected configurations.
 *
 * Pattern matches WindowProjectStateRegistry for consistency.
 */
object WindowRunnerStateRegistry {
    private val _states = mutableStateMapOf<String, WindowRunnerState>()

    /**
     * Register a new window runner state.
     */
    fun register(windowId: String): WindowRunnerState {
        val state = WindowRunnerState(windowId)
        _states[windowId] = state
        windowRunnerStateRegistryLogger.debug(LogCategory.UI, "Registered state for window", mapOf("windowId" to windowId))
        return state
    }

    /**
     * Get the runner state for a window.
     */
    fun get(windowId: String): WindowRunnerState? = _states[windowId]

    /**
     * Get or create the runner state for a window.
     */
    fun getOrCreate(windowId: String): WindowRunnerState =
        _states.getOrPut(windowId) {
            windowRunnerStateRegistryLogger.debug(LogCategory.UI, "Creating new state for window", mapOf("windowId" to windowId))
            WindowRunnerState(windowId)
        }

    /**
     * Unregister a window runner state when the window is closed.
     */
    fun unregister(windowId: String) {
        _states.remove(windowId)
        windowRunnerStateRegistryLogger.debug(LogCategory.UI, "Unregistered state for window", mapOf("windowId" to windowId))
    }

    /**
     * Get all registered window IDs.
     */
    fun getAllWindowIds(): Set<String> = _states.keys.toSet()
}
