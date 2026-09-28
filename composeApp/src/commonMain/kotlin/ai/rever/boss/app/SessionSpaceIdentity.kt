package ai.rever.boss.app

import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.workspaces.LAST_SESSION_ID
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.WorkspaceSerializer
import ai.rever.boss.components.workspaces.WorkspaceSettingsManager
import ai.rever.boss.components.workspaces.sessionSpaceIdentity
import ai.rever.boss.components.workspaces.workspaceManager

/** Preserve legacy recovery data as an ordinary Space when the optional slot is disabled. */
internal fun prepareSessionSpace(workspace: LayoutWorkspace): LayoutWorkspace {
    val enabled = WorkspaceSettingsManager.currentSettings.value.enableLastSessionSpace
    val restored = sessionSpaceIdentity(workspace, enabled)
    if (restored.id == workspace.id) return restored
    return checkNotNull(workspaceManager.importWorkspace(WorkspaceSerializer.serialize(restored)))
}

/** A new window must not adopt the hidden recovery slot as its visible identity. */
internal fun updateSessionSpace(
    current: LayoutWorkspace,
    splitViewState: SplitViewState,
) {
    val space = prepareSessionSpace(current)
    if (splitViewState.currentWorkspaceId == null || splitViewState.currentWorkspaceId == LAST_SESSION_ID) {
        splitViewState.rebindCurrentWorkspace(space.id)
    }
    workspaceManager.updateCurrentWorkspace(space)
}
