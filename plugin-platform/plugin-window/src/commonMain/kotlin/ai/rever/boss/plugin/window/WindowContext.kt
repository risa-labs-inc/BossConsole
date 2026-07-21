package ai.rever.boss.plugin.window

import androidx.compose.runtime.compositionLocalOf

/**
 * CompositionLocal to provide the window ID to descendant composables.
 * This allows components to identify which window they are in (e.g., for filtering events).
 */
val LocalWindowId = compositionLocalOf<String?> { null }

/**
 * CompositionLocal to provide WindowProjectState to descendant composables.
 * This allows components like BossTopBar to access the window-specific project state.
 */
val LocalWindowProjectState = compositionLocalOf<WindowProjectState?> { null }

/**
 * Helper function to select a project using window-specific state.
 * Window state is required for multi-window support.
 *
 * @param windowProjectState The window project state (should not be null in normal operation)
 * @param project The project to select
 */
fun selectProjectInWindow(windowProjectState: WindowProjectState?, project: Project) {
    if (windowProjectState != null) {
        windowProjectState.selectProject(project)
    }
}
