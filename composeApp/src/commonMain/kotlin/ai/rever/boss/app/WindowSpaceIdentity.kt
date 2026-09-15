package ai.rever.boss.app

import ai.rever.boss.components.workspaces.LAST_SESSION_ID
import ai.rever.boss.components.workspaces.LAST_SESSION_NAME
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.plugin.workspace.PanelConfig
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel

/** A process-wide selection can supply metadata only when it names this window's Space. */
internal fun windowSpaceIdentity(
    workspaceId: String?,
    current: LayoutWorkspace?,
    saved: List<LayoutWorkspace>,
): LayoutWorkspace? =
    workspaceId?.let { id ->
        current?.takeIf { it.id == id } ?: saved.firstOrNull { it.id == id }
            ?: unsavedSessionIdentity.takeIf { id == LAST_SESSION_ID }
    }

// Last Session need not have reached disk yet. This supplies slot identity only; dirty comparisons
// still use savedCopyOf and every writer combines the identity with this window's extracted layout.
private val unsavedSessionIdentity =
    LayoutWorkspace(
        id = LAST_SESSION_ID,
        name = LAST_SESSION_NAME,
        description = "Unsaved session",
        layout = SinglePanel(PanelConfig(id = "main", tabs = emptyList())),
    )

/** Window-local request ownership; a superseded preparation never reaches the destructive commit. */
internal class WorkspaceSwitchGeneration {
    private var generation = 0L

    fun next(): Long = ++generation

    fun isCurrent(request: Long): Boolean = request == generation
}
