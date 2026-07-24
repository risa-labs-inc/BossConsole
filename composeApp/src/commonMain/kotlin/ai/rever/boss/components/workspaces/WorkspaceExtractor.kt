package ai.rever.boss.components.workspaces

import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.components.window_panel.SplitNode
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.plugin.tab.codeeditor.EditorTabInfo
import ai.rever.boss.plugin.tab.jupyter.JupyterTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.plugin.workspace.SplitConfig.HorizontalSplit
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import ai.rever.boss.plugin.workspace.SplitConfig.VerticalSplit
import kotlin.time.Clock

/**
 * Extracts the current layout workspace from the split view state
 *
 * @param splitViewState The split view state to extract from
 * @param projectPath The current project path (per-window)
 * @param name The name of the workspace
 * @param description The description of the workspace
 */
fun extractCurrentWorkspace(
    splitViewState: SplitViewState,
    projectPath: String = "",
    name: String = "Current",
    description: String = "Current layout workspace",
): LayoutWorkspace {
    val layout = extractSplitConfig(splitViewState.rootNode)
    return LayoutWorkspace(
        id = LayoutWorkspace.generateId(),
        name = name,
        description = description,
        layout = layout,
        timestamp = Clock.System.now().toEpochMilliseconds(),
        projectPath = projectPath.ifEmpty { null },
    )
}

private fun extractSplitConfig(node: SplitNode): SplitConfig =
    when (node) {
        is SplitNode.Panel -> {
            val tabs =
                node.tabsComponent.tabsState.value.tabs.mapNotNull { tab ->
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
                                workingDirectory = tab.workingDirectory,
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
                }
            SinglePanel(
                PanelConfig(
                    id = node.id,
                    tabs = tabs,
                ),
            )
        }

        is SplitNode.VerticalSplit -> {
            VerticalSplit(
                left = extractSplitConfig(node.left),
                right = extractSplitConfig(node.right),
            )
        }

        is SplitNode.HorizontalSplit -> {
            HorizontalSplit(
                top = extractSplitConfig(node.top),
                bottom = extractSplitConfig(node.bottom),
            )
        }
    }
