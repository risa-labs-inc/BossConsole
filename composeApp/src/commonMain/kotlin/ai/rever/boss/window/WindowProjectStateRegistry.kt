@file:Suppress("UNUSED")

package ai.rever.boss.window

import ai.rever.boss.components.plugin.panels.left_top.ProjectState
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.runtime.mutableStateMapOf

/**
 * Re-exports from plugin-window module for backward compatibility.
 * New code should import directly from ai.rever.boss.plugin.window
 */

// Re-export types from plugin-window
typealias Project = ai.rever.boss.plugin.window.Project
typealias WindowProjectState = ai.rever.boss.plugin.window.WindowProjectState
typealias ProjectSelectionCallback = ai.rever.boss.plugin.window.ProjectSelectionCallback

// Re-export composition locals
val LocalWindowId = ai.rever.boss.plugin.window.LocalWindowId
val LocalWindowProjectState = ai.rever.boss.plugin.window.LocalWindowProjectState

// Re-export helper function
fun selectProjectInWindow(
    windowProjectState: WindowProjectState?,
    project: Project,
) = ai.rever.boss.plugin.window
    .selectProjectInWindow(windowProjectState, project)

private val windowProjectStateLogger = BossLogger.forComponent("WindowProjectStateRegistry")

/**
 * Registry for per-window project states.
 * Each window has its own independent project state while sharing the global recent projects list.
 *
 * Pattern matches SplitViewStateRegistry for consistency.
 */
object WindowProjectStateRegistry {
    private val _states = mutableStateMapOf<String, WindowProjectState>()

    /**
     * Register a new window project state.
     * Sets up callback to update recent projects when project is selected.
     */
    fun register(windowId: String): WindowProjectState {
        val state = WindowProjectState(windowId)
        // Register callback to update recent projects list
        state.setProjectSelectionCallback { project ->
            ProjectState.updateRecentProjects(project)
        }
        _states[windowId] = state
        windowProjectStateLogger.debug(LogCategory.UI, "Registered state for window", mapOf("windowId" to windowId))
        return state
    }

    /**
     * Get the project state for a window.
     */
    fun get(windowId: String): WindowProjectState? = _states[windowId]

    /**
     * Get or create the project state for a window.
     */
    fun getOrCreate(windowId: String): WindowProjectState =
        _states.getOrPut(windowId) {
            windowProjectStateLogger.debug(LogCategory.UI, "Creating new state for window", mapOf("windowId" to windowId))
            val state = WindowProjectState(windowId)
            // Register callback to update recent projects list
            state.setProjectSelectionCallback { project ->
                ProjectState.updateRecentProjects(project)
            }
            state
        }

    /**
     * Unregister a window project state when the window is closed.
     */
    fun unregister(windowId: String) {
        _states.remove(windowId)
        windowProjectStateLogger.debug(LogCategory.UI, "Unregistered state for window", mapOf("windowId" to windowId))
    }

    /**
     * Get all registered window IDs.
     */
    fun getAllWindowIds(): Set<String> = _states.keys.toSet()
}
