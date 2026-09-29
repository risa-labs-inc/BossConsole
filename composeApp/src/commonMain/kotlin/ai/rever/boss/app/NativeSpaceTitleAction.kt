package ai.rever.boss.app

import ai.rever.boss.components.dialogs.RenameDialog
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.isUserOwnedSpace
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import ai.rever.boss.window.NativeTitleBarAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.arkivanov.decompose.extensions.compose.subscribeAsState

/** The standard macOS window title follows this window's Space and project. */
@Composable
internal fun nativeSpaceTitleAction(
    splitViewState: SplitViewState,
    projectName: String,
    onOpen: (LayoutWorkspace) -> Unit,
): NativeTitleBarAction {
    val spaces by workspaceManager.visibleWorkspaces.collectAsState()
    val currentId = splitViewState.currentWorkspaceId
    val current = spaces.find { it.id == currentId }
    var renameTarget by remember { mutableStateOf<LayoutWorkspace?>(null) }
    renameTarget?.let { target ->
        RenameDialog(
            title = "Rename Space",
            currentName = target.name,
            label = "Space name",
            onDismiss = { renameTarget = null },
            onRename = { name -> workspaceManager.renameWorkspaceById(target.id, name) },
        )
    }
    return NativeTitleBarAction(
        id = "space",
        label = current?.name ?: "Default",
        subtitle = projectName.ifBlank { "No project" },
        contextMenu =
            if (current != null && isUserOwnedSpace(current.id)) {
                listOf(NativeTitleBarAction("rename-space", "Rename Space…") { renameTarget = current })
            } else {
                emptyList()
            },
        menu =
            spaces.map { space ->
                NativeTitleBarAction("space:${space.id}", space.name, active = space.id == currentId) { onOpen(space) }
            },
    ) {}
}

/** Separate live terminal label; the Space picker always retains its own identity. */
@Composable
internal fun nativeTerminalTitleLabel(splitViewState: SplitViewState): List<NativeTitleBarAction> {
    val tabs =
        splitViewState
            .getActiveTabsComponent()
            ?.tabsState
            ?.subscribeAsState()
            ?.value
    val activeTab = tabs?.tabs?.getOrNull(tabs.activeIndex)
    val terminalTitle = activeTab?.takeIf { it.typeId == TerminalTabType.typeId }?.title?.takeIf { it.isNotBlank() }
    return terminalTitle?.let { listOf(NativeTitleBarAction("terminal_title", it) {}) }.orEmpty()
}
