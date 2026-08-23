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
import ai.rever.boss.plugin.tab.fluck.FluckTabType
import ai.rever.boss.plugin.tab.jupyter.JupyterTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabInfo
import ai.rever.boss.plugin.tab.terminal.TerminalTabType
import ai.rever.boss.plugin.workspace.SplitConfig.HorizontalSplit
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import ai.rever.boss.plugin.workspace.SplitConfig.VerticalSplit
import ai.rever.boss.project.DefaultWorkingDirectory
import ai.rever.boss.utils.awaitRegistryCondition
import ai.rever.boss.utils.extractFileName
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.Project
import ai.rever.boss.window.WindowProjectState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.time.Clock

private val logger = BossLogger.forComponent("WorkspaceApplier")

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
) {
    // Generate ID if missing
    val workspaceId = workspace.id.ifEmpty { LayoutWorkspace.generateId() }

    // Restore project if workspace has one and restoreProject is true
    if (restoreProject && windowProjectState != null) {
        workspace.projectPath?.let { path ->
            if (path.isNotEmpty()) {
                val projectName =
                    path
                        .trimEnd('/')
                        .trimEnd('\\')
                        .extractFileName()
                        .ifEmpty { "Project" }
                windowProjectState.selectProject(
                    Project(
                        name = projectName,
                        path = path,
                        lastOpened = Clock.System.now().toEpochMilliseconds(),
                    ),
                )
            }
        }
    }

    // Try to restore preserved state first
    if (splitViewState.restorePreservedState(workspaceId)) {
        // State restored successfully
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
    // The `?:` is load-bearing in a way that reads like a bug and is left alone deliberately:
    // the window's path is "" when no project is selected, and "" is not null, so
    // `workspace.projectPath` is unreachable whenever windowProjectState is non-null. Using
    // selectedOrNull here instead would make a saved workspace's recorded project win over the
    // no-project default - a different answer to "which project do these terminals open in",
    // which is not what this change is about. Pre-existing, and left that way.
    val currentProjectPath =
        withContext(Dispatchers.IO) {
            DefaultWorkingDirectory.resolve(
                windowProjectState?.selectedProject?.value?.path ?: workspace.projectPath,
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
        withContext(Dispatchers.IO) { warmEngine() }
    }

    splitViewState.tabRegistry.awaitTabTypes(requiredTabTypes)

    splitViewState.clearAllPanels()

    // Apply the workspace recursively
    applyWorkspaceNode(workspace.layout, splitViewState, "main", currentProjectPath)
}

/**
 * Suspend until every [typeIds] entry is registered, or until the plugin
 * registration timeout elapses. On timeout the apply proceeds anyway — tabs of
 * still-missing types are skipped exactly as before, but a warning is logged
 * instead of failing silently.
 */
private suspend fun TabRegistry.awaitTabTypes(typeIds: Set<TabTypeId>) {
    fun missing() = typeIds.filterNot { isRegistered(it) }
    if (missing().isEmpty()) return

    logger.info(
        LogCategory.WORKSPACE,
        "Waiting for plugin tab types before applying workspace",
        mapOf(
            "missing" to missing().joinToString { it.typeId },
        ),
    )
    val registered =
        awaitRegistryCondition(::addChangeListener, ::removeChangeListener) {
            missing().isEmpty()
        }
    if (!registered) {
        logger.warn(
            LogCategory.WORKSPACE,
            "Tab types still unregistered after wait - their tabs will be skipped",
            mapOf(
                "missing" to missing().joinToString { it.typeId },
            ),
        )
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
        "jupyter" -> JupyterTabInfo.TYPE_ID
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
