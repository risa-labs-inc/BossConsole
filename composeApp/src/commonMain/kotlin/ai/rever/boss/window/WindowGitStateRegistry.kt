package ai.rever.boss.window

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf

/**
 * CompositionLocal to provide WindowGitState to descendant composables.
 * This allows components like BossTopBar and Git panels to access the window-specific git state.
 */
val LocalWindowGitState = compositionLocalOf<WindowGitState?> { null }

/**
 * Registry for per-window git states.
 * Each window has its own independent git state while sharing the global GitService
 * for actual git operations.
 *
 * This fixes the issue where opening a new window with no project or a non-git project
 * would hide git UI across ALL windows. With this registry, each window maintains
 * its own git state independently.
 *
 * Pattern matches WindowRunnerStateRegistry for consistency.
 */
object WindowGitStateRegistry {
    private val _states = mutableStateMapOf<String, WindowGitState>()

    /**
     * Register a new window git state.
     */
    fun register(windowId: String): WindowGitState {
        val state = WindowGitState(windowId)
        _states[windowId] = state
        return state
    }

    /**
     * Get the git state for a window.
     */
    fun get(windowId: String): WindowGitState? = _states[windowId]

    /**
     * Get or create the git state for a window.
     */
    fun getOrCreate(windowId: String): WindowGitState = _states.getOrPut(windowId) { WindowGitState(windowId) }

    /**
     * Unregister a window git state when the window is closed.
     */
    fun unregister(windowId: String) {
        _states.remove(windowId)
    }

    /**
     * Get all registered window IDs.
     */
    fun getAllWindowIds(): Set<String> = _states.keys.toSet()
}
