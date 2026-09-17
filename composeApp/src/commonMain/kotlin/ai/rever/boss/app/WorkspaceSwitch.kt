package ai.rever.boss.app

import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.WorkspaceSettings
import ai.rever.boss.components.workspaces.WorkspaceSettingsManager
import ai.rever.boss.components.workspaces.WorkspaceSwitchAction
import ai.rever.boss.components.workspaces.WorkspaceSwitchDialog
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
    val leaving: () -> LayoutWorkspace?,
    val leavingUnsaved: () -> Boolean,
)

@Composable
internal fun rememberWorkspaceSwitch(
    state: BossAppState,
    splitViewState: SplitViewState,
): WorkspaceSwitch {
    val scope = rememberCoroutineScope()
    val settings by WorkspaceSettingsManager.currentSettings.collectAsState()

    val controller =
        remember(state, splitViewState, scope) {
            WorkspaceSwitchController(state, splitViewState, scope)
        }
    val resolve: (LayoutWorkspace, Boolean) -> Unit = { workspace, keep ->
        controller.resolve(workspace, keep, explicitDiscard = true)
    }
    val request: (LayoutWorkspace) -> Unit = { workspace ->
        controller.invalidate()
        state.pendingWorkspaceSwitch = null
        val leavingId = splitViewState.currentWorkspaceId
        val hasSomethingToLeave = !leavingId.isNullOrEmpty() && leavingId != workspace.id
        val action = settings.resolveOnWorkspaceSwitch()
        when {
            !hasSomethingToLeave -> {
                controller.resolve(workspace, true, explicitDiscard = false)
            }

            shouldPromptForWorkspaceSwitch(action, controller.leavingUnsaved()) -> {
                state.pendingWorkspaceSwitch = workspace
            }

            else -> {
                controller.resolve(workspace, action == WorkspaceSwitchAction.KEEP, explicitDiscard = false)
            }
        }
    }

    return remember(state, splitViewState, settings) {
        WorkspaceSwitch(request, resolve, controller::leaving, controller::leavingUnsaved)
    }
}

/** The keep-or-close question, while one is outstanding. */
@Composable
internal fun WorkspaceSwitchPrompt(
    state: BossAppState,
    switch: WorkspaceSwitch,
) {
    val pending = state.pendingWorkspaceSwitch ?: return
    val scope = rememberCoroutineScope()
    val leaving = switch.leaving()
    // Collected, not read once: the watcher can settle while the question is on screen, and the
    // dialog must not still be saying "no unsaved changes" by the time it is answered.
    val unsavedWorkspaces by workspaceManager.unsavedWorkspaces.collectAsState()

    WorkspaceSwitchDialog(
        leavingName = leaving?.name ?: "this Space",
        enteringName = pending.name,
        // The same rule as the vertical bar and the Space menu, so all three agree about the same
        // Space - including on Last Session, which is a slot rather than a document and so always
        // holds work that has never been saved anywhere.
        leavingUnsaved =
            switch.leavingUnsaved() || spaceIsUnsaved(leaving?.id, unsavedWorkspaces[state.windowId].orEmpty()),
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

/** A saved CLOSE preference is not consent to discard changes made later. */
internal fun shouldPromptForWorkspaceSwitch(
    action: WorkspaceSwitchAction,
    unsaved: Boolean,
): Boolean = action == WorkspaceSwitchAction.ASK || (action == WorkspaceSwitchAction.CLOSE && unsaved)
