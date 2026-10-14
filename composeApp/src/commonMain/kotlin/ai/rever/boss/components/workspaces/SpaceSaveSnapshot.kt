package ai.rever.boss.components.workspaces

/**
 * Bind a window's live layout to that same window's active Space identity.
 *
 * [processGlobalCurrent] is only a fallback when it confirms [activeWorkspaceId]. A different
 * window updates that process-global value from its own layout watcher, so using it without the
 * id check can save this window's layout over the other window's Space file.
 *
 * `projectPath` is copied from [liveLayout] as well, which is both halves at once: the unsaved
 * mark compares it, so an existing Space whose recorded path differed from the invoking window's
 * could never go clean without this. The cost is the other direction - a window with no project
 * selected carries a null, and saving an existing Space from such a window erases its recorded
 * path. That exposure is small (entering a Space selects its project), and the alternative
 * re-creates the permanently-stuck mark, so the live value wins.
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
