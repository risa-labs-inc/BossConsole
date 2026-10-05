package ai.rever.boss.app

import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.workspaces.DefaultSpace
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.WorkspaceSettingsManager
import ai.rever.boss.components.workspaces.applyWorkspace
import ai.rever.boss.components.workspaces.requiresProject
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.window.WindowProjectState

/**
 * Whether a window that restored nothing should apply [workspace] as its starting layout.
 *
 * Two conditions, and both are load-bearing:
 * - **No project selected.** With a project, the reactive apply in
 *   `BossAppStartupEffects` already owns this and would apply the same workspace a
 *   second time.
 * - **The workspace can stand without one.** `{projectPath}` silently falls back to
 *   `~/BossProjects` (see `DefaultWorkingDirectory`), so applying the Claude Code default
 *   here would open a terminal running `claude --dangerously-skip-permissions` in an
 *   empty projects folder on the first launch of a fresh install. Browser-only needs
 *   nothing, which is why it reaches first launch and the terminal-first layouts keep
 *   waiting for a project.
 *
 * A fresh install starts in the empty Planet Berul Space. A configured layout that
 * requires a project still waits for project selection.
 */
internal fun shouldApplyOnFreshStart(
    workspace: LayoutWorkspace?,
    hasProject: Boolean,
): Boolean = workspace != null && !hasProject && !workspace.requiresProject()

/**
 * Apply the configured default workspace, or Planet Berul, to a first window that
 * restored nothing and has no project.
 *
 * Returns the workspace applied, or null if [shouldApplyOnFreshStart] declined. Must be
 * called before `markHandlersReady`: [applyWorkspace] clears all panels, which would
 * destroy tabs a handler had already created.
 */
internal suspend fun applyDefaultWorkspaceOnFreshStart(
    splitViewState: SplitViewState,
    windowProjectState: WindowProjectState,
    workspace: LayoutWorkspace? =
        WorkspaceSettingsManager.getDefaultWorkspace()
            ?: workspaceManager.savedCopyOf(DefaultSpace.ID)
            ?: DefaultSpace.planetBerul,
): LayoutWorkspace? {
    val selectedProject = windowProjectState.selectedProject.value
    val hasProject = selectedProject.path.isNotEmpty()
    if (workspace == null || !shouldApplyOnFreshStart(workspace, hasProject)) return null

    // restoreProject = false: the workspace carries no project and there is none to
    // restore, so nothing should touch the window's project selection here.
    // The caller marks restoration as started before this suspends, so the timeout cannot
    // start another apply. Claim the Space only after its layout has actually landed.
    return if (applyWorkspace(workspace, splitViewState, windowProjectState, restoreProject = false)) {
        workspaceManager.loadWorkspace(workspace)
        workspace
    } else {
        null
    }
}
