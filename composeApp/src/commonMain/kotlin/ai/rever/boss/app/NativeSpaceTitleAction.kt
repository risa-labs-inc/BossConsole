package ai.rever.boss.app

import ai.rever.boss.components.plugin.DynamicPluginManager
import ai.rever.boss.components.plugin.MissingPluginOffer
import ai.rever.boss.components.plugin.openTopOfMindQuickSwitcher
import ai.rever.boss.components.dialogs.RenameDialog
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.isUserOwnedSpace
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.components.workspaces.WorkspaceSettingsManager
import ai.rever.boss.components.workspaces.spaceMenuGroups
import ai.rever.boss.window.MenuActionsHandler
import ai.rever.boss.window.LocalWindowId
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import ai.rever.boss.window.NativeTitleBarAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
    val allSpaces by workspaceManager.workspaces.collectAsState()
    val settings by WorkspaceSettingsManager.currentSettings.collectAsState()
    val windowId = LocalWindowId.current
    val scope = rememberCoroutineScope()
    // Observe installs/enables so the primary action changes without restarting the window.
    val pluginStates = DynamicPluginManager.anyActiveManager()?.pluginStates?.collectAsState()?.value
    val hasTopOfMind = pluginStates?.containsKey("ai.rever.boss.plugin.dynamic.topofmind") == true ||
        MissingPluginOffer.isInstalled("ai.rever.boss.plugin.dynamic.topofmind") == true
    val spaceMenu = spaceTitleMenu(spaces, settings.recentSpaceIds, splitViewState.currentWorkspaceId, onOpen) {
        windowId?.let(MenuActionsHandler::triggerCreateSpace)
    }
    val currentId = splitViewState.currentWorkspaceId
    val current = allSpaces.find { it.id == currentId }
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
        label = current?.name ?: "Planet Berul",
        subtitle = projectName.ifBlank { "No project" },
        contextMenu = (if (current != null && isUserOwnedSpace(current.id)) {
                listOf(NativeTitleBarAction("rename-space", "Rename Space…") { renameTarget = current })
            } else {
                emptyList()
            }) + if (hasTopOfMind) spaceMenu else emptyList(),
        menu = if (hasTopOfMind) null else spaceMenu,
    ) {
        windowId?.let { openTopOfMindQuickSwitcher(it, scope) }
    }
}

internal fun spaceTitleMenu(
    spaces: List<LayoutWorkspace>,
    recentIds: List<String>,
    currentId: String?,
    onOpen: (LayoutWorkspace) -> Unit,
    onCreate: () -> Unit,
): List<NativeTitleBarAction> {
    val groups = spaceMenuGroups(spaces, recentIds)
    fun row(space: LayoutWorkspace) =
        NativeTitleBarAction("space:${space.id}", space.name, active = space.id == currentId) { onOpen(space) }
    return buildList {
        add(NativeTitleBarAction("create-space", "Create New Space…", onClick = onCreate))
        add(NativeTitleBarAction("recent-spaces", "Recent Spaces", enabled = false) {})
        addAll(groups.recent.map(::row))
        if (groups.more.isNotEmpty()) {
            add(NativeTitleBarAction("more-spaces", "More", menu = groups.more.map(::row)) {})
        }
        add(NativeTitleBarAction("template-spaces", "Template Spaces", menu = groups.templates.map(::row)) {})
    }
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
