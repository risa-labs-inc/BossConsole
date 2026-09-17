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
    private val lock = Any()

    internal data class PublicationToken(
        val state: WindowGitState,
        val projectRevision: Long,
    )

    /**
     * Register a new window git state.
     */
    fun register(windowId: String): WindowGitState {
        val state = WindowGitState(windowId)
        synchronized(lock) { _states[windowId] = state }
        return state
    }

    /**
     * Get the git state for a window.
     */
    fun get(windowId: String): WindowGitState? = synchronized(lock) { _states[windowId] }

    /**
     * Get or create the git state for a window.
     */
    fun getOrCreate(windowId: String): WindowGitState = synchronized(lock) { stateFor(windowId) }

    private fun stateFor(windowId: String): WindowGitState = _states.getOrPut(windowId) { WindowGitState(windowId) }

    /**
     * Unregister a window git state when the window is closed.
     */
    fun unregister(windowId: String) {
        synchronized(lock) { _states.remove(windowId) }
    }

    /**
     * Get all registered window IDs.
     */
    fun getAllWindowIds(): Set<String> = synchronized(lock) { _states.keys.toSet() }

    internal fun publicationToken(
        state: WindowGitState,
        projectPath: String,
    ): PublicationToken? =
        synchronized(lock) {
            if (_states[state.windowId] !== state) return@synchronized null
            state.revisionForProject(projectPath)?.let { PublicationToken(state, it) }
        }

    internal fun publishStashRefresh(
        token: PublicationToken,
        statuses: List<ai.rever.boss.git.GitFileStatus>?,
        stashes: List<ai.rever.boss.git.GitStashInfo>?,
    ): Boolean =
        synchronized(lock) {
            if (_states[token.state.windowId] !== token.state) return@synchronized false
            token.state.publishStashRefreshIfCurrent(token.projectRevision, statuses, stashes)
        }
}
