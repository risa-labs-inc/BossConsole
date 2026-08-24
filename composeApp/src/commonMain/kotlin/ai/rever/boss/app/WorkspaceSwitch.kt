package ai.rever.boss.app

import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.WorkspaceSettings
import ai.rever.boss.components.workspaces.WorkspaceSettingsManager
import ai.rever.boss.components.workspaces.WorkspaceSwitchAction
import ai.rever.boss.components.workspaces.WorkspaceSwitchDialog
import ai.rever.boss.components.workspaces.applyWorkspace
import ai.rever.boss.components.workspaces.resolveOnWorkspaceSwitch
import ai.rever.boss.components.workspaces.workspaceManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/**
 * Switching this window from one workspace to another.
 *
 * Its own file because a switch is two decisions, not one: what happens to the workspace being
 * left, and whether to ask. Inline in the scaffold that was several branches in a composable
 * that is otherwise a layout.
 */
@Stable
internal class WorkspaceSwitch internal constructor(
    /** Begin a switch. May put the keep-or-close question on screen first. */
    val request: (LayoutWorkspace) -> Unit,
    /** Carry one out, the question having been settled. */
    val resolve: (workspace: LayoutWorkspace, keepLeaving: Boolean) -> Unit,
)

@Composable
internal fun rememberWorkspaceSwitch(
    state: BossAppState,
    splitViewState: SplitViewState,
): WorkspaceSwitch {
    val scope = rememberCoroutineScope()
    val settings by WorkspaceSettingsManager.currentSettings.collectAsState()

    val resolve: (LayoutWorkspace, Boolean) -> Unit = { workspace, keepLeaving ->
        scope.launch {
            val leaving = workspaceManager.currentWorkspace.value
            if (leaving != null && leaving.id.isNotEmpty()) {
                if (keepLeaving) {
                    splitViewState.preserveCurrentState(leaving.id, leaving.name)
                } else {
                    splitViewState.closeCurrentWorkspace()
                }
            }

            // Load first to reset dirty state, then apply - which may restore state preserved
            // for the workspace being entered.
            workspaceManager.loadWorkspace(workspace)
            applyWorkspace(workspace, splitViewState, state.windowProjectState)
        }
    }

    val request: (LayoutWorkspace) -> Unit = { workspace ->
        val leaving = workspaceManager.currentWorkspace.value
        // Nothing to keep or close means nothing to ask about: a first switch, or one back onto
        // the workspace already showing. A dialog whose answer cannot matter is worse than none.
        val hasSomethingToLeave = leaving != null && leaving.id.isNotEmpty() && leaving.id != workspace.id
        val action = settings.resolveOnWorkspaceSwitch()
        when {
            !hasSomethingToLeave -> resolve(workspace, true)
            action == WorkspaceSwitchAction.ASK -> state.pendingWorkspaceSwitch = workspace
            else -> resolve(workspace, action == WorkspaceSwitchAction.KEEP)
        }
    }

    return remember(state, splitViewState, settings) { WorkspaceSwitch(request, resolve) }
}

/** The keep-or-close question, while one is outstanding. */
@Composable
internal fun WorkspaceSwitchPrompt(
    state: BossAppState,
    switch: WorkspaceSwitch,
) {
    val pending = state.pendingWorkspaceSwitch ?: return
    val scope = rememberCoroutineScope()

    WorkspaceSwitchDialog(
        leavingName =
            workspaceManager.currentWorkspace.value
                ?.name
                .orEmpty(),
        enteringName = pending.name,
        onChoose = { keep, dontAskAgain ->
            state.pendingWorkspaceSwitch = null
            if (dontAskAgain) {
                scope.launch {
                    WorkspaceSettingsManager.setOnWorkspaceSwitch(
                        if (keep) WorkspaceSettings.SWITCH_KEEP else WorkspaceSettings.SWITCH_CLOSE,
                    )
                }
            }
            switch.resolve(pending, keep)
        },
        // Dismissing answers nothing, so it cancels the switch rather than guessing. The two
        // buttons throw away different things, and neither is a safe default for a dialog
        // somebody pressed Escape on.
        onDismiss = { state.pendingWorkspaceSwitch = null },
    )
}
