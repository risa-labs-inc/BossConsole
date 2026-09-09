package ai.rever.boss.components.workspaces

import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.components.window_panel.SplitNode
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.tab.codeeditor.EditorTabInfo
import ai.rever.boss.plugin.tab.composer.ComposerTabInfo
import ai.rever.boss.plugin.tab.diff.DiffTabInfo
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
internal fun extractTabConfig(
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

        is DiffTabInfo -> {
            extractDiffConfig(tab)
        }

        is ComposerTabInfo -> {
            extractComposerConfig(tab)
        }

        is JupyterTabInfo -> {
            TabConfig(
                type = "jupyter",
                title = tab.title,
                filePath = tab.filePath,
            )
        }

        else -> {
            // Plugin-constructed composer tabs arrive as the plugin's own
            // TabInfo class (the api jar filters the host's one out), but they
            // carry the session id as the tab id, so they persist the same way.
            //
            // There is deliberately NO diff branch here: DiffTabInfo lives in
            // the same host-only source set, so a plugin cannot construct a
            // host diff tab, and a custom tab claiming the "diff" type carries
            // no scope fields (staged/fromRef/toRef) - persisting it would
            // rebuild it on restore as an unstaged working-tree diff of its
            // filePath, i.e. silently change its meaning, which the branch
            // above refuses to do for host-constructed tabs.
            if (tab.typeId.typeId == "composer") {
                TabConfig(
                    type = "composer",
                    title = tab.title,
                    filePath = tab.id,
                )
            } else {
                TabConfig(
                    type = "unknown",
                    title = tab.title,
                )
            }
        }
    }

/**
 * The saved form of a host diff tab, or null for a scope restore cannot
 * rebuild.
 *
 * Only a plain UNSTAGED WORKING-TREE file diff is persisted, because that is
 * the only scope restore can rebuild: TabConfig has no field for refs or for
 * `staged`, and DiffTabInfo.create() defaults both.
 *
 * The guard used to be `filePath.isBlank()` alone, which let two scopes
 * through and silently changed their meaning on restart: a range diff
 * restricted to one file (fromRef+toRef+filePath all set) and a staged diff
 * both came back as unstaged working-tree diffs of that path - same tab
 * position, same title, different content. Dropping the tab is honest;
 * rebuilding it as something else is not.
 */
private fun extractDiffConfig(tab: DiffTabInfo): TabConfig? {
    if (tab.filePath.isBlank() || tab.staged || tab.fromRef != null || tab.toRef != null) return null
    return TabConfig(
        type = "diff",
        title = tab.title,
        filePath = tab.filePath,
    )
}

/**
 * The saved form of a host composer tab, or null for a blank session id.
 *
 * The session id rides in filePath: TabConfig has no generic extra field,
 * and session ids survive placeholder processing untouched (they contain no
 * project tokens).
 *
 * A blank session id is dropped, agreeing with the restore side
 * (WorkspaceApplier refuses to rebuild one): persisting it would save a tab
 * that can never come back, and the saved layout would disagree with what
 * the user sees.
 */
private fun extractComposerConfig(tab: ComposerTabInfo): TabConfig? {
    if (tab.sessionId.isBlank()) return null
    return TabConfig(
        type = "composer",
        title = tab.title,
        filePath = tab.sessionId,
    )
}
