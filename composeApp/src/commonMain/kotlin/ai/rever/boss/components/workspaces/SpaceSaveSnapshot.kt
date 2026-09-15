package ai.rever.boss.components.workspaces

/**
 * Bind a window's live layout to that same window's active Space identity.
 *
 * [processGlobalCurrent] is only a fallback when it confirms [activeWorkspaceId]. A different
 * window updates that process-global value from its own layout watcher, so using it without the
 * id check can save this window's layout over the other window's Space file.
 */
internal fun spaceSnapshotForSave(
    activeWorkspaceId: String?,
    liveLayout: LayoutWorkspace,
    knownSpaces: List<LayoutWorkspace>,
    processGlobalCurrent: LayoutWorkspace?,
): LayoutWorkspace {
    val identity =
        activeWorkspaceId?.let { activeId ->
            knownSpaces.firstOrNull { it.id == activeId }
                ?: processGlobalCurrent?.takeIf { it.id == activeId }
        }

    return identity?.copy(
        layout = liveLayout.layout,
        timestamp = liveLayout.timestamp,
        projectPath = liveLayout.projectPath,
    ) ?: liveLayout.copy(
        name = "Workspace ${liveLayout.timestamp / 1000}",
        description = "Saved workspace",
    )
}
