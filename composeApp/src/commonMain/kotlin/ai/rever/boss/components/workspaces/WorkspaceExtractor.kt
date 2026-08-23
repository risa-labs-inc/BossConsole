package ai.rever.boss.components.workspaces

import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.components.window_panel.SplitNode
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.tab.codeeditor.EditorTabInfo
import ai.rever.boss.plugin.tab.jupyter.JupyterTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.plugin.workspace.SplitConfig.HorizontalSplit
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import ai.rever.boss.plugin.workspace.SplitConfig.VerticalSplit
import ai.rever.boss.project.DefaultWorkingDirectory
import kotlin.time.Clock

/**
 * Extracts the current layout workspace from the split view state
 *
 * @param splitViewState The split view state to extract from
 * @param projectPath The current project path (per-window)
 * @param name The name of the workspace
 * @param description The description of the workspace
 * @param defaultWorkingDirectory The no-project working directory, used to decide which
 *   terminals are persisted with a null one. `nominalPath()`, not `ensureDefaultDirectory()`: nothing here needs
 *   the directory to *exist*, only its name to compare against, and this runs from the
 *   auto-save `snapshotFlow` - whose producer re-runs on the composition thread whenever a tab
 *   title, url or working directory changes - and from the Last Session teardown, which can be
 *   the shutdown-hook thread. A parameter rather than read inside so tests can pass their own.
 */
fun extractCurrentWorkspace(
    splitViewState: SplitViewState,
    projectPath: String = "",
    name: String = "Current",
    description: String = "Current layout workspace",
    defaultWorkingDirectory: String = DefaultWorkingDirectory.nominalPath(),
): LayoutWorkspace {
    val layout = extractSplitConfig(splitViewState.rootNode, defaultWorkingDirectory)
    return LayoutWorkspace(
        id = LayoutWorkspace.generateId(),
        name = name,
        description = description,
        layout = layout,
        timestamp = Clock.System.now().toEpochMilliseconds(),
        projectPath = projectPath.ifEmpty { null },
    )
}

/**
 * @param defaultWorkingDirectory the no-project working directory, from
 *   `DefaultWorkingDirectory.nominalPath()` - see [extractCurrentWorkspace] for why not
 *   `ensureDefaultDirectory()`. A terminal sitting in it is persisted with a null
 *   working directory so restore re-resolves against whatever project is selected then - see
 *   `DefaultWorkingDirectory.persisted`.
 */
private fun extractSplitConfig(
    node: SplitNode,
    defaultWorkingDirectory: String,
): SplitConfig =
    when (node) {
        is SplitNode.Panel -> {
            // Counted over the tabs that SURVIVE extraction, not off the component's own count.
            // extractTabConfig drops tabs that must never be persisted (a sidebar-promoted
            // PanelHostTabInfo), and one of those sitting inside the pinned block would leave a
            // saved count that points past the last pinned tab on restore.
            val tabs = node.tabsComponent.tabsState.value.tabs
            val persisted =
                tabs.mapIndexedNotNull { index, tab ->
                    extractTabConfig(tab, defaultWorkingDirectory)?.let { config ->
                        config to node.tabsComponent.isPinned(index)
                    }
                }
            SinglePanel(
                PanelConfig(
                    id = node.id,
                    tabs = persisted.map { it.first },
                    pinnedCount = persisted.count { it.second },
                ),
            )
        }

        is SplitNode.VerticalSplit -> {
            VerticalSplit(
                left = extractSplitConfig(node.left, defaultWorkingDirectory),
                right = extractSplitConfig(node.right, defaultWorkingDirectory),
            )
        }

        is SplitNode.HorizontalSplit -> {
            HorizontalSplit(
                top = extractSplitConfig(node.top, defaultWorkingDirectory),
                bottom = extractSplitConfig(node.bottom, defaultWorkingDirectory),
            )
        }
    }

/** The saved form of one open tab, or null for a tab that must not be persisted. */
private fun extractTabConfig(
    tab: TabInfo,
    defaultWorkingDirectory: String,
): TabConfig? =
    when (tab) {
        // Transient sidebar-promoted panel — never persist it. It would
        // serialize as an "unknown" tab type and crash WorkspaceApplier on
        // restore; on next launch the plugin simply returns to its sidebar.
        is ai.rever.boss.components.plugin.tab_types.PanelHostTabInfo -> {
            null
        }

        is FluckTabInfo -> {
            TabConfig(
                type = "browser",
                title = tab.title,
                url = tab.currentUrl,
                faviconCacheKey = tab.faviconCacheKey,
            )
        }

        is TerminalTabInfo -> {
            TabConfig(
                type = "terminal",
                title = tab.title,
                initialCommand = tab.initialCommand,
                workingDirectory = DefaultWorkingDirectory.persisted(tab.workingDirectory, defaultWorkingDirectory),
            )
        }

        is EditorTabInfo -> {
            TabConfig(
                type = "editor",
                title = tab.title,
                filePath = tab.filePath,
            )
        }

        is JupyterTabInfo -> {
            TabConfig(
                type = "jupyter",
                title = tab.title,
                filePath = tab.filePath,
            )
        }

        else -> {
            TabConfig(
                type = "unknown",
                title = tab.title,
            )
        }
    }
