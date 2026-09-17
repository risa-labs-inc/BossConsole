package ai.rever.boss.components.workspaces

import ai.rever.boss.cache.loadFaviconFromCache
import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.components.window_panel.SplitOrientation
import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.dashboard.WorkspacePlaceholders
import ai.rever.boss.icons.FileIcons
import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.tab.codeeditor.CodeEditorTabType
import ai.rever.boss.plugin.tab.codeeditor.EditorTabInfo
import ai.rever.boss.plugin.tab.composer.ComposerTabInfo
import ai.rever.boss.plugin.tab.composer.ComposerTabType
import ai.rever.boss.plugin.tab.diff.DiffTabInfo
import ai.rever.boss.plugin.tab.diff.DiffTabType
import ai.rever.boss.plugin.tab.fluck.FluckTabType
import ai.rever.boss.plugin.tab.jupyter.JupyterTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import ai.rever.boss.plugin.workspace.SplitConfig.HorizontalSplit
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import ai.rever.boss.plugin.workspace.SplitConfig.VerticalSplit
import ai.rever.boss.project.DefaultWorkingDirectory
import ai.rever.boss.window.WindowProjectState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Applies a layout workspace to the split view
 * @param workspace The workspace to apply
 * @param splitViewState The split view state to apply the workspace to
 * @param windowProjectState The window project state for multi-window support (optional)
 * @param restoreProject Whether to restore the project from the workspace. Set to false when
 *                       applying workspace due to project selection change (to avoid overwriting
 *                       the user's project selection).
 * @param warmEngine Starts the browser engine boot. A parameter only so a test can observe that it
 *                   is asked BEFORE the tab-type wait rather than after - move those lines below
 *                   the wait and the whole benefit evaporates with every test still green.
 */
suspend fun applyWorkspace(
    workspace: LayoutWorkspace,
    splitViewState: SplitViewState,
    windowProjectState: WindowProjectState? = null,
    restoreProject: Boolean = true,
    warmEngine: () -> Unit = ::warmBrowserEngineForTabs,
) = applyPreparedWorkspace(
    workspace,
    splitViewState,
    windowProjectState,
    restoreProject,
    WorkspaceApplyHooks(warmEngine),
)

/** One preparation seam and one commit boundary, shared by normal and supersedable applies. */
internal data class WorkspaceApplyHooks(
    val warmEngine: () -> Unit = ::warmBrowserEngineForTabs,
    val beforeApply: () -> Boolean = { true },
)

internal suspend fun applyPreparedWorkspace(
    workspace: LayoutWorkspace,
    splitViewState: SplitViewState,
    windowProjectState: WindowProjectState? = null,
    restoreProject: Boolean = true,
    hooks: WorkspaceApplyHooks = WorkspaceApplyHooks(),
) {
    // Generate ID if missing
    val workspaceId = workspace.id.ifEmpty { LayoutWorkspace.generateId() }

    // Preparing a newer request must not relabel or clear the still-visible Space.
    // Every mutation below is deferred until the caller accepts this commit.

    if (splitViewState.hasPreservedWorkspace(workspaceId)) {
        if (hooks.beforeApply()) {
            restoreWorkspaceProject(workspace, windowProjectState, restoreProject)
            splitViewState.restorePreservedState(workspaceId)
        }
        return
    }

    // Get current project path for tab creation. Below the early return, not above it:
    // switching back to a workspace whose state is still in memory builds no tabs, so
    // resolving there would touch the filesystem for nothing.
    //
    // On IO because resolve() stats and may create - every caller launches this from a Compose
    // scope, i.e. Main, and docs/THREADING.md rule 1 is about exactly that. Resolved once for
    // the whole tree rather than per tab, mirroring WorkspaceExtractor on the way out.
    //
    // Resolve the project this apply WILL select without publishing that selection during IO.
    // If no project is restored, preserve the existing empty-path versus null fallback behavior.
    val currentProjectPath =
        withContext(Dispatchers.IO) {
            DefaultWorkingDirectory.resolve(
                if (restoreProject && windowProjectState != null && !workspace.projectPath.isNullOrEmpty()) {
                    workspace.projectPath
                } else {
                    windowProjectState?.selectedProject?.value?.path ?: workspace.projectPath
                },
            )
        }

    // No preserved state, apply workspace from scratch.
    // Wait (bounded) for the plugin-provided tab types this workspace needs —
    // at startup the workspace flow emits before the dynamic plugins that own
    // browser/terminal/editor have registered their factories, and addTab
    // drops any tab whose type has no factory yet.
    val requiredTabTypes = collectRequiredTabTypeIds(workspace.layout)

    // Ahead of the wait, not after it: this layout is about to build a browser tab, and the wait
    // below is dead time the engine boot can have for free. Without it a first install pays the
    // whole cold Chromium boot inside the tab, because the startup pre-warm's gate is "has this
    // machine ever used the browser" and a first install has not.
    if (needsBrowserEngine(requiredTabTypes)) {
        // On IO, and for the same reason the resolve() above is: every applyWorkspace call site is
        // a Compose scope, i.e. Main, and the gate this reaches stats the engine directories and
        // reads two version.txt files before it spawns anything. Milliseconds, but milliseconds on
        // the UI thread on every window creation and every workspace switch. Still ahead of the
        // wait below, which is the ordering the whole hook is for.
        withContext(Dispatchers.IO) { hooks.warmEngine() }
    }

    splitViewState.tabRegistry.awaitTabTypes(requiredTabTypes)

    if (hooks.beforeApply()) {
        restoreWorkspaceProject(workspace, windowProjectState, restoreProject)
        // The commit can preserve the currently visible target (reselecting the same Space).
        // Recheck before rebuilding, or that newly preserved unsaved tree would be discarded.
        if (!splitViewState.restorePreservedState(workspaceId)) {
            splitViewState.clearAllPanels()
            applyWorkspaceNode(workspace.layout, splitViewState, "main", currentProjectPath)
        }
    }
}

/**
 * Whether applying a layout with these tab types means booting the browser engine.
 *
 * Its own function so the predicate is testable, and named for the consequence rather than for the
 * id it looks for: the browser tab type is the only thing in a workspace that costs a Chromium
 * process tree.
 */
internal fun needsBrowserEngine(typeIds: Set<TabTypeId>): Boolean = FluckTabType.typeId in typeIds

/**
 * Start the browser engine boot in the background, if this platform has one.
 *
 * expect/actual only because [applyWorkspace] is commonMain and the engine is a desktop concern -
 * the same seam `createBrowser()` in `Fluck.kt` uses.
 */
internal expect fun warmBrowserEngineForTabs()

/** Collect the tab type IDs a workspace layout needs, ignoring unsupported/legacy entries. */
private fun collectRequiredTabTypeIds(node: SplitConfig): Set<TabTypeId> =
    when (node) {
        is SinglePanel -> {
            node.panel.tabs
                .mapNotNull { tabTypeIdFor(it) }
                .toSet()
        }

        is VerticalSplit -> {
            collectRequiredTabTypeIds(node.left) + collectRequiredTabTypeIds(node.right)
        }

        is HorizontalSplit -> {
            collectRequiredTabTypeIds(node.top) + collectRequiredTabTypeIds(node.bottom)
        }
    }

/**
 * Single source of truth for which persisted tab types are restorable and
 * which plugin tab type owns each. Both the pre-apply wait
 * ([collectRequiredTabTypeIds]) and the construction dispatch in
 * [createTabFromWorkspaceConfig] key off this mapping, so a new tab type
 * added here is automatically waited for before restore.
 *
 * Returns null for unsupported/legacy/transient types (e.g. a
 * sidebar-promoted "panel-host" tab that should never have been persisted) —
 * those are skipped instead of crashing the whole workspace restore.
 */
private fun tabTypeIdFor(tabConfig: TabConfig): TabTypeId? =
    when (tabConfig.type) {
        "browser" -> FluckTabType.typeId
        "terminal" -> TerminalTabType.typeId
        "editor" -> CodeEditorTabType.typeId
        "diff" -> DiffTabType.typeId
        "jupyter" -> JupyterTabInfo.TYPE_ID
        "composer" -> ComposerTabType.typeId
        else -> null
    }

private suspend fun applyWorkspaceNode(
    node: SplitConfig,
    splitViewState: SplitViewState,
    currentPanelId: String,
    projectPath: String,
) {
    when (node) {
        is SinglePanel -> {
            // Add tabs to current panel
            val tabsComponent = splitViewState.getPanelTabsComponent(currentPanelId)
            node.panel.tabs.forEach { tabConfig ->
                createTabFromWorkspaceConfig(tabConfig, projectPath, splitViewState)?.let { tabsComponent?.addTab(it) }
            }
            // One call per panel restores the whole pinning state, and it is clamped inside setPinnedCount:
            // a tab whose type no longer resolves comes back as null above, so fewer tabs can land
            // than were saved with this count.
            tabsComponent?.setPinnedCount(node.panel.pinnedCount)
        }

        is VerticalSplit -> {
            // First process left side in current panel
            when (val leftNode = node.left) {
                is SinglePanel -> {
                    // Add tabs to current panel
                    val tabsComponent = splitViewState.getPanelTabsComponent(currentPanelId)
                    leftNode.panel.tabs.forEach { tabConfig ->
                        createTabFromWorkspaceConfig(tabConfig, projectPath, splitViewState)?.let { tabsComponent?.addTab(it) }
                    }
                    // One call per panel restores the whole pinning state, and it is clamped inside setPinnedCount:
                    // a tab whose type no longer resolves comes back as null above, so fewer tabs can land
                    // than were saved with this count.
                    tabsComponent?.setPinnedCount(leftNode.panel.pinnedCount)
                }

                else -> {
                    // Recursively apply left workspace config
                    applyWorkspaceNode(leftNode, splitViewState, currentPanelId, projectPath)
                }
            }

            // Then create vertical split for right side
            // Resolve the first tab up front; if it doesn't map to a supported tab type
            // (e.g. a legacy panel-host entry in a recovered workspace), skip the split
            // instead of creating an empty "ghost" panel via splitPanel(tabToMove = null).
            val firstRightTabInfo = getFirstTab(node.right)?.let { createTabFromWorkspaceConfig(it, projectPath, splitViewState) }
            if (firstRightTabInfo != null) {
                val rightPanelId =
                    splitViewState.splitPanel(
                        panelId = currentPanelId,
                        orientation = SplitOrientation.VERTICAL,
                        tabToMove = firstRightTabInfo,
                    )

                // Add remaining tabs or process splits for right side
                when (val rightNode = node.right) {
                    is SinglePanel -> {
                        // Add remaining tabs
                        val tabsComponent = splitViewState.getPanelTabsComponent(rightPanelId)
                        rightNode.panel.tabs.drop(1).forEach { tabConfig ->
                            createTabFromWorkspaceConfig(tabConfig, projectPath, splitViewState)?.let { tabsComponent?.addTab(it) }
                        }
                        // One call per panel restores the whole pinning state, and it is clamped inside setPinnedCount:
                        // a tab whose type no longer resolves comes back as null above, so fewer tabs can land
                        // than were saved with this count.
                        tabsComponent?.setPinnedCount(rightNode.panel.pinnedCount)
                    }

                    else -> {
                        // Recursively apply right workspace config
                        applyWorkspaceNode(rightNode, splitViewState, rightPanelId, projectPath)
                    }
                }
            }
        }

        is HorizontalSplit -> {
            // First process top side in current panel
            when (val topNode = node.top) {
                is SinglePanel -> {
                    // Add tabs to current panel
                    val tabsComponent = splitViewState.getPanelTabsComponent(currentPanelId)
                    topNode.panel.tabs.forEach { tabConfig ->
                        createTabFromWorkspaceConfig(tabConfig, projectPath, splitViewState)?.let { tabsComponent?.addTab(it) }
                    }
                    // One call per panel restores the whole pinning state, and it is clamped inside setPinnedCount:
                    // a tab whose type no longer resolves comes back as null above, so fewer tabs can land
                    // than were saved with this count.
                    tabsComponent?.setPinnedCount(topNode.panel.pinnedCount)
                }

                else -> {
                    // Recursively apply top workspace config
                    applyWorkspaceNode(topNode, splitViewState, currentPanelId, projectPath)
                }
            }

            // Then create horizontal split for bottom side
            // Resolve the first tab up front (see the VerticalSplit note) — never create an
            // empty split panel for an unsupported first tab.
            val firstBottomTabInfo = getFirstTab(node.bottom)?.let { createTabFromWorkspaceConfig(it, projectPath, splitViewState) }
            if (firstBottomTabInfo != null) {
                val bottomPanelId =
                    splitViewState.splitPanel(
                        panelId = currentPanelId,
                        orientation = SplitOrientation.HORIZONTAL,
                        tabToMove = firstBottomTabInfo,
                    )

                // Add remaining tabs or process splits for bottom side
                when (val bottomNode = node.bottom) {
                    is SinglePanel -> {
                        // Add remaining tabs
                        val tabsComponent = splitViewState.getPanelTabsComponent(bottomPanelId)
                        bottomNode.panel.tabs.drop(1).forEach { tabConfig ->
                            createTabFromWorkspaceConfig(tabConfig, projectPath, splitViewState)?.let { tabsComponent?.addTab(it) }
                        }
                        // One call per panel restores the whole pinning state, and it is clamped inside setPinnedCount:
                        // a tab whose type no longer resolves comes back as null above, so fewer tabs can land
                        // than were saved with this count.
                        tabsComponent?.setPinnedCount(bottomNode.panel.pinnedCount)
                    }

                    else -> {
                        // Recursively apply bottom workspace config
                        applyWorkspaceNode(bottomNode, splitViewState, bottomPanelId, projectPath)
                    }
                }
            }
        }
    }
}

private fun getFirstTab(workspaceConfig: SplitConfig): TabConfig? =
    when (workspaceConfig) {
        is SinglePanel -> workspaceConfig.panel.tabs.firstOrNull()
        is VerticalSplit -> getFirstTab(workspaceConfig.left)
        is HorizontalSplit -> getFirstTab(workspaceConfig.top)
    }

/**
 * @param resolvedProjectPath the selected project's path, or the no-project default when there
 *   is none - already through `DefaultWorkingDirectory.resolve`, once, in [applyWorkspace].
 *   Never empty, which is why the terminal branch below has no null case left.
 */
internal fun createTabFromWorkspaceConfig(
    tabConfig: TabConfig,
    resolvedProjectPath: String,
    splitViewState: SplitViewState,
): TabInfo? {
    // Dispatch on the resolved type id (see tabTypeIdFor) so the mapping that
    // decides what restore waits for and the mapping that constructs tabs
    // cannot drift apart.
    return when (tabTypeIdFor(tabConfig)) {
        FluckTabType.typeId -> {
            // Load favicon from cache if available (Issue #160)
            val cachedFavicon = loadFaviconFromCache(tabConfig.faviconCacheKey)

            // Process URL placeholders
            val processedUrl =
                tabConfig.url?.let {
                    WorkspacePlaceholders.processPlaceholders(it, resolvedProjectPath, null)
                } ?: "about:blank"

            FluckTabInfo(
                id = "browser-${Random.nextLong()}",
                typeId = FluckTabType.typeId,
                _title = tabConfig.title,
                _tabIcon = cachedFavicon,
                url = processedUrl,
                faviconCacheKey = tabConfig.faviconCacheKey,
            )
        }

        TerminalTabType.typeId -> {
            // Process working directory placeholder
            // No stored working directory means "wherever the project is", which is what
            // resolvedProjectPath already holds. `restored` also reads a stored home directory
            // that way - layouts written before this change carry a literal `~` for every
            // no-project terminal, and honouring it would restore the problem on every launch.
            val workingDir =
                DefaultWorkingDirectory.restored(tabConfig.workingDirectory)?.let {
                    WorkspacePlaceholders.processPlaceholders(it, resolvedProjectPath, null)
                } ?: resolvedProjectPath

            // Process initial command placeholder (shell command → quote {projectPath})
            val initialCmd =
                tabConfig.initialCommand?.let {
                    WorkspacePlaceholders.processPlaceholders(it, resolvedProjectPath, null, quoteProjectPath = true)
                }

            TerminalTabInfo(
                id = "terminal-${Random.nextLong()}",
                typeId = TerminalTabType.typeId,
                title = tabConfig.title,
                workingDirectory = workingDir,
                initialCommand = initialCmd,
            )
        }

        CodeEditorTabType.typeId -> {
            createEditorTab(tabConfig, resolvedProjectPath)
        }

        DiffTabType.typeId -> {
            // Only file diffs are persisted (see WorkspaceExtractor); restore as
            // a working-tree diff of that file. A blank filePath (corrupt or
            // hand-edited layout) yields no tab: a diff tab with no scope can
            // never show anything, and the composer branch below and the
            // extractor (which refuses to PERSIST a blank path) already agree
            // on "no scope, no tab".
            val processedPath =
                tabConfig.filePath?.let {
                    WorkspacePlaceholders.processPlaceholders(it, resolvedProjectPath, null)
                }
            if (processedPath.isNullOrBlank()) {
                null
            } else {
                DiffTabInfo.create(filePath = processedPath)
            }
        }

        JupyterTabInfo.TYPE_ID -> {
            if (splitViewState.tabRegistry.isRegistered(JupyterTabInfo.TYPE_ID)) {
                val filePath =
                    tabConfig.filePath?.let {
                        WorkspacePlaceholders.processPlaceholders(it, resolvedProjectPath, null)
                    } ?: ""
                JupyterTabInfo.create(filePath, title = tabConfig.title)
            } else {
                // Jupyter plugin unavailable (e.g. uninstalled since this workspace was
                // saved): restore the notebook as an editor tab instead of letting addTab
                // silently drop it — the same isRegistered guard as SplitViewState.openFile.
                createEditorTab(tabConfig, resolvedProjectPath)
            }
        }

        ComposerTabType.typeId -> {
            // The session id came out of filePath (see WorkspaceExtractor); a
            // blank one (corrupt layout) yields no tab. The editor-tab plugin's
            // factory reloads the session from its own storage, or renders an
            // empty composer when the session is gone.
            val sessionId = tabConfig.filePath ?: ""
            if (sessionId.isBlank()) {
                null
            } else {
                ComposerTabInfo.create(sessionId, tabConfig.title)
            }
        }

        else -> {
            null
        }
    }
}

/** Build the editor tab for [tabConfig]; also the notebook fallback when the jupyter plugin is missing. */
private fun createEditorTab(
    tabConfig: TabConfig,
    resolvedProjectPath: String,
): EditorTabInfo {
    // Process file path placeholder
    val filePath =
        tabConfig.filePath?.let {
            WorkspacePlaceholders.processPlaceholders(it, resolvedProjectPath, null)
        } ?: ""
    val fileIconInfo = FileIcons.forFile(tabConfig.title)

    return EditorTabInfo(
        id = "editor-${Random.nextLong()}",
        typeId = CodeEditorTabType.typeId,
        title = tabConfig.title,
        icon = fileIconInfo.icon,
        tabIcon =
            ai.rever.boss.plugin.api.TabIcon
                .Vector(fileIconInfo.icon, fileIconInfo.color),
        filePath = filePath,
    )
}
