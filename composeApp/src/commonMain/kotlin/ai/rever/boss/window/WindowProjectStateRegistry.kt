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
     * The one place a [WindowProjectState] is built, so the host wiring below cannot be installed
     * on some windows and not others.
     *
     * There used to be a `register` alongside [getOrCreate] carrying a second copy of this
     * wiring, with no production caller and an overwrite that handed back a fresh state - and,
     * once the announcer existed, a fresh `previousPath` seeded to `""` for a window that already
     * had a project open. It is gone; [getOrCreate] is the only way in.
     */
    private fun newState(windowId: String): WindowProjectState {
        val state = WindowProjectState(windowId)
        // The state holds ONE callback slot, and this is the host's: anything that calls
        // setProjectSelectionCallback on it again drops both halves, the recent-projects update
        // and the event-bus announcement. Nothing outside this object does today.
        state.setProjectSelectionCallback(
            hostProjectCallback(
                updateRecents = ProjectState::updateRecentProjects,
                announcer = ProjectChangeAnnouncer(windowId, state.selectedProject.value.path),
            ),
        )
        return state
    }

    /**
     * The window's selection callback: record the project as recent, then announce it.
     *
     * `try`/`finally`, not two statements. [updateRecents] persists to disk, and if it throws,
     * skipping the announcement would ALSO leave the announcer's `previousPath` stale - every
     * later selection in this window would then report a `previousProjectPath` one step behind,
     * silently, for the rest of the session. The announcement being unconditional is the entire
     * point of [ProjectChangeAnnouncer], so it must not be the half an unrelated failure can
     * skip. The recents update stays FIRST so a plugin reading `recentProjects` off the event
     * sees the new entry, and the original throw still propagates.
     *
     * [updateRecents] is a parameter rather than a direct `ProjectState` call purely so that
     * claim is testable: `ProjectState` is an object, so a hard-coded call could not be made to
     * fail.
     *
     * Written out rather than as `try`/`finally` so neither failure hides the other. A plain
     * `finally` would discard the [updateRecents] exception if [announcer] threw on the way out;
     * [announcer] is a parameter, so the type permits that even though the real one cannot
     * (`tryEmit` does not throw). Whichever fails first is what propagates, with the other
     * attached as suppressed.
     */
    // Catching Throwable IS the contract here: the point is that neither half's failure can hide
    // the other's, and a rule about narrow catches cannot express "whatever this arbitrary
    // callback threw". Both are rethrown - nothing is swallowed.
    @Suppress("TooGenericExceptionCaught")
    internal fun hostProjectCallback(
        updateRecents: (Project) -> Unit,
        announcer: ProjectSelectionCallback,
    ): ProjectSelectionCallback =
        ProjectSelectionCallback { project ->
            var recentsFailure: Throwable? = null
            try {
                updateRecents(project)
            } catch (t: Throwable) {
                recentsFailure = t
            }
            try {
                announcer.onProjectSelected(project)
            } catch (t: Throwable) {
                if (recentsFailure == null) throw t
                recentsFailure.addSuppressed(t)
            }
            recentsFailure?.let { throw it }
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
            newState(windowId)
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
