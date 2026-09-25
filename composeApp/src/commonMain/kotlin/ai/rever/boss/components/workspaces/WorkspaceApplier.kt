@file:Suppress("TooManyFunctions") // The proof-before-clear helpers split per split-shape by design.

package ai.rever.boss.components.workspaces

import ai.rever.boss.cache.loadFaviconFromCache
import ai.rever.boss.components.bars.horizontal.StatusMessageManager
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
 * @return false when the apply was refused: a layout that declares tabs but cannot build all of
 *         them leaves the live tree, the project selection and the workspace-id claim untouched.
 *         Callers that preserve or close the outgoing workspace before calling must use the
 *         result to put that tree back - see `WorkspaceSwitch`.
 */
@Suppress("ReturnCount") // early exits ARE the semantics: fast path, refusal, claim, build
suspend fun applyWorkspace(
    workspace: LayoutWorkspace,
    splitViewState: SplitViewState,
    windowProjectState: WindowProjectState? = null,
    restoreProject: Boolean = true,
    warmEngine: () -> Unit = ::warmBrowserEngineForTabs,
): Boolean {
    // Generate ID if missing. Blank, not just empty, and the collision-safe mint:
    // a timestamp-only id is throwaway the moment two Spaces share a millisecond,
    // and the preserved tree below is keyed under whatever lands here.
    val workspaceId = workspace.id.ifBlank { mintWorkspaceId() }

    // Try to restore preserved state first. Peek rather than claim: a miss still points
    // _currentWorkspaceId at this workspace, and a layout that proves unbuildable below must
    // not leave the live tree filed under an id that was never applied.
    if (splitViewState.hasPreservedState(workspaceId)) {
        // The project restore belongs to entering the Space even on the fast path - switching
        // to it switches to its project whether its tree is rebuilt or just shown again.
        restoreWorkspaceProject(workspace, windowProjectState, restoreProject)
        // State restored successfully
        splitViewState.restorePreservedState(workspaceId)
        return true
    }

    // The project path the build resolves its tabs against - COMPUTED, not performed. The
    // workspace's recorded project is only selected once the layout is proven buildable below:
    // a refused apply must not move the window's project out from under the live tree, or the
    // next terminal opens in a directory the user never chose.
    //
    // The expression is the post-selection answer: the workspace's path when it is about to be
    // selected, otherwise the window's current selection. The `?:` is load-bearing in a way
    // that reads like a bug and is left alone deliberately: the window's path is "" when no
    // project is selected, and "" is not null, so `workspace.projectPath` is unreachable
    // whenever windowProjectState is non-null. Using selectedOrNull here instead would make a
    // saved workspace's recorded project win over the no-project default - a different answer
    // to "which project do these terminals open in". Pre-existing, and left that way.
    //
    // On IO because resolve() stats and may create - every caller launches this from a Compose
    // scope, i.e. Main, and docs/THREADING.md rule 1 is about exactly that. Resolved once for
    // the whole tree rather than per tab, mirroring WorkspaceExtractor on the way out.
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
        withContext(Dispatchers.IO) { warmEngine() }
    }

    splitViewState.tabRegistry.awaitTabTypes(requiredTabTypes)

    // Prove the incoming tree can build BEFORE tearing the live one down. A Space whose tab
    // types are all gone - the plugin that owned them was uninstalled since it was saved, or a
    // hand-edited type string - used to clear first and build nothing, which presents as "my
    // work vanished".
    if (hasUnbuildableTabs(workspace.layout, currentProjectPath, splitViewState)) {
        refuseUnbuildableWorkspace(workspace, workspaceId)
        return false
    }

    // Restore project if workspace has one and restoreProject is true - only now, on the proven
    // path. Performed earlier it would move the window's selection even when nothing builds.
    restoreWorkspaceProject(workspace, windowProjectState, restoreProject)

    // Claimed only now that the build is proven: this is the miss branch of
    // restorePreservedState, pointing _currentWorkspaceId at the incoming workspace. Re-checked
    // rather than folded into the peek above because awaitTabTypes suspends, and a preserved
    // copy may have appeared meanwhile - a hit restores it and skips the rebuild.
    if (splitViewState.restorePreservedState(workspaceId)) {
        return true
    }

    splitViewState.clearAllPanels()

    // Apply the workspace recursively
    applyWorkspaceNode(workspace.layout, splitViewState, "main", currentProjectPath)
    return true
}

/**
 * Select the project [workspace] records, when applying should restore it.
 *
 * Shared by both proven paths in [applyWorkspace] - the preserved-state fast path and the
 * successful build - and deliberately unreachable from a refusal, since selecting moves the
 * window's project.
 */
private fun restoreWorkspaceProject(
    workspace: LayoutWorkspace,
    windowProjectState: WindowProjectState?,
    restoreProject: Boolean,
) {
    if (!restoreProject || windowProjectState == null) return
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

/**
 * Suspend until every [typeIds] entry is registered, or until the plugin
 * registration timeout elapses. On timeout the apply proceeds anyway — tabs of
 * still-missing types are skipped exactly as before, but a warning is logged
 * instead of failing silently.
 *
 * internal because the MCP workspace provider needs the same gate for its direct
 * terminal open: `openTerminalInActivePanelNow` drops a tab whose type has no
 * factory yet, which is exactly the cold start that tool exists for.
 */
internal suspend fun TabRegistry.awaitTabTypes(typeIds: Set<TabTypeId>) {
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
    consumedConfig: TabConfig? = null,
) {
    when (node) {
        is SinglePanel -> {
            // Add tabs to current panel, skipping by identity any config already consumed
            // to seed the panel during splitPanel.
            val tabsComponent = splitViewState.getPanelTabsComponent(currentPanelId)
            node.panel.tabs.forEach { tabConfig ->
                if (tabConfig !== consumedConfig) {
                    createTabFromWorkspaceConfig(tabConfig, projectPath, splitViewState)?.let { tabsComponent?.addTab(it) }
                }
            }
            // One call per panel restores the whole pinning state, and it is clamped inside setPinnedCount:
            // a tab whose type no longer resolves comes back as null above, so fewer tabs can land
            // than were saved with this count.
            tabsComponent?.setPinnedCount(node.panel.pinnedCount)
        }

        is VerticalSplit -> {
            val leftRestorable = firstRestorableTab(node.left, projectPath, splitViewState)
            val rightRestorable = firstRestorableTab(node.right, projectPath, splitViewState)

            when {
                leftRestorable != null && rightRestorable != null -> {
                    // Create parent split before restoring children (#1610).
                    // Restoring the left child first caused any nested split in the left subtree
                    // to displace the anchor panel, leading outer splits to replace the wrong leaf
                    // (restoring ((A, B), C) as ((A, C), B)).
                    //
                    // Gate on the first RESTORABLE tab anywhere in the subtree, not on the first
                    // config resolving: an unrestorable first tab used to silently drop every
                    // restorable tab behind it (#1211). For a SinglePanel side, splitPanel seeds
                    // the new panel with the first restorable tab and the SinglePanel restore
                    // skips it by identity. For a nested split, no tab is pre-created (tabToMove = null)
                    // so the subtree recursion materializes its tabs without duplication (#1210).
                    val rightNode = node.right
                    val consumedRightConfig: TabConfig?
                    val rightPanelId =
                        if (rightNode is SinglePanel) {
                            consumedRightConfig = rightRestorable.first
                            splitViewState.splitPanel(
                                panelId = currentPanelId,
                                orientation = SplitOrientation.VERTICAL,
                                tabToMove = rightRestorable.second,
                            )
                        } else {
                            consumedRightConfig = null
                            splitViewState.splitPanel(
                                panelId = currentPanelId,
                                orientation = SplitOrientation.VERTICAL,
                                tabToMove = null,
                            )
                        }

                    applyWorkspaceNode(node.left, splitViewState, currentPanelId, projectPath)
                    applyWorkspaceNode(node.right, splitViewState, rightPanelId, projectPath, consumedRightConfig)
                }

                leftRestorable != null -> {
                    // Only left side has restorable tabs: restore into current panel without creating
                    // an empty sibling ghost panel (#1211, #1610).
                    applyWorkspaceNode(node.left, splitViewState, currentPanelId, projectPath, consumedConfig)
                }

                rightRestorable != null -> {
                    // Only right side has restorable tabs: fold into current panel without creating
                    // an empty sibling ghost panel (#1211, #1610).
                    applyWorkspaceNode(node.right, splitViewState, currentPanelId, projectPath, consumedConfig)
                }

                else -> {
                    // Neither side has restorable tabs: do not create an empty panel or split.
                }
            }
        }

        is HorizontalSplit -> {
            val topRestorable = firstRestorableTab(node.top, projectPath, splitViewState)
            val bottomRestorable = firstRestorableTab(node.bottom, projectPath, splitViewState)

            when {
                topRestorable != null && bottomRestorable != null -> {
                    // Create parent split before restoring children (#1610).
                    val bottomNode = node.bottom
                    val consumedBottomConfig: TabConfig?
                    val bottomPanelId =
                        if (bottomNode is SinglePanel) {
                            consumedBottomConfig = bottomRestorable.first
                            splitViewState.splitPanel(
                                panelId = currentPanelId,
                                orientation = SplitOrientation.HORIZONTAL,
                                tabToMove = bottomRestorable.second,
                            )
                        } else {
                            consumedBottomConfig = null
                            splitViewState.splitPanel(
                                panelId = currentPanelId,
                                orientation = SplitOrientation.HORIZONTAL,
                                tabToMove = null,
                            )
                        }

                    applyWorkspaceNode(node.top, splitViewState, currentPanelId, projectPath)
                    applyWorkspaceNode(node.bottom, splitViewState, bottomPanelId, projectPath, consumedBottomConfig)
                }

                topRestorable != null -> {
                    applyWorkspaceNode(node.top, splitViewState, currentPanelId, projectPath, consumedConfig)
                }

                bottomRestorable != null -> {
                    applyWorkspaceNode(node.bottom, splitViewState, currentPanelId, projectPath, consumedConfig)
                }

                else -> {
                    // Neither side has restorable tabs: do not create an empty panel or split.
                }
            }
        }
    }
}

/**
 * The first saved tab in the subtree that still restores to a live tab, in restore order
 * (left/top before right/bottom, tabs in panel order), paired with the [TabInfo] built for
 * it. "Restores to a live tab" is judged on the tab that was BUILT, not on
 * `tabTypeIdFor(config)`: the pre-created instance goes through `addTab`, which drops a
 * tab whose type has no registered factory, so a built-but-unregistered tab must not
 * count as restorable either (it would open a panel that then loses its only tab - a
 * ghost panel), and the jupyter-to-editor fallback is weighed as the editor tab it
 * produced, not as the jupyter type it was saved as.
 *
 * The split pre-creation passes that exact instance to `splitPanel`, so a SinglePanel
 * side materializes each tab exactly once; a nested subtree only needs the null check,
 * because the recursion materializes its tabs itself - a pre-created copy there would be
 * materialized twice (#1210).
 *
 * Returns null when nothing in the subtree is restorable, which is the "skip the split
 * entirely" answer that keeps an all-unrestorable side from leaving a ghost panel behind
 * - the same trade the old first-tab gate made, now without the collateral tab loss (#1211).
 */
private suspend fun firstRestorableTab(
    node: SplitConfig,
    resolvedProjectPath: String,
    splitViewState: SplitViewState,
): Pair<TabConfig, TabInfo>? =
    when (node) {
        is SinglePanel -> {
            node.panel.tabs
                .firstNotNullOfOrNull { tabConfig ->
                    createTabFromWorkspaceConfig(tabConfig, resolvedProjectPath, splitViewState)
                        // Judge the BUILT tab, not tabTypeIdFor(config): addTab drops a
                        // tab whose type has no registered factory, and the jupyter
                        // fallback must count as the editor tab it produced.
                        ?.takeIf { splitViewState.tabRegistry.isRegistered(it.typeId) }
                        ?.let { tabConfig to it }
                }
        }

        is VerticalSplit -> {
            val left = firstRestorableTab(node.left, resolvedProjectPath, splitViewState)
            left ?: firstRestorableTab(node.right, resolvedProjectPath, splitViewState)
        }

        is HorizontalSplit -> {
            val top = firstRestorableTab(node.top, resolvedProjectPath, splitViewState)
            top ?: firstRestorableTab(node.bottom, resolvedProjectPath, splitViewState)
        }
    }

/**
 * Whether [layout] declares tabs that cannot all be put on screen - the proof
 * [applyWorkspace] needs before it may clear the live tree.
 *
 * A layout declaring no tabs at all is empty by design and still applies; one that declares
 * tabs that cannot all build is a failure that must leave the live tree alone.
 */
private fun hasUnbuildableTabs(
    layout: SplitConfig,
    projectPath: String,
    splitViewState: SplitViewState,
): Boolean = collectBuildableTabs(layout, projectPath, splitViewState).size < layout.declaredTabCount()

private fun SplitConfig.declaredTabCount(): Int =
    when (this) {
        is SinglePanel -> panel.tabs.size
        is VerticalSplit -> left.declaredTabCount() + right.declaredTabCount()
        is HorizontalSplit -> top.declaredTabCount() + bottom.declaredTabCount()
    }

/**
 * Report a refused apply - a WORKSPACE error for the log and a status message for the user,
 * who otherwise just sees the window refuse to change.
 */
private fun refuseUnbuildableWorkspace(
    workspace: LayoutWorkspace,
    workspaceId: String,
) {
    logger.error(
        LogCategory.WORKSPACE,
        "Workspace contains tabs that cannot be built - keeping the live layout",
        mapOf(
            "workspace" to workspace.name,
            "id" to workspaceId,
            "types" to workspace.layout.declaredTabTypes().joinToString(),
        ),
    )
    StatusMessageManager.showMessage(
        "Could not open \"${workspace.name}\" - some of its tabs cannot be restored. " +
            "Enable or reinstall the plugins for these tab types: " +
            workspace.layout.declaredTabTypes().joinToString() + ". The current layout was kept.",
        durationMs = 6_000,
    )
}

/**
 * The distinct tab-type strings a layout declares, wherever they sit in the tree.
 *
 * A layout with none is empty by design - `applyWorkspace` may clear to it - while one that
 * declares tabs that cannot all build is a failure that must leave the live tree alone.
 * Partial restore must be an explicit recovery action, not silently drop saved work.
 */
private fun SplitConfig.declaredTabTypes(): Set<String> =
    when (this) {
        is SinglePanel -> panel.tabs.mapTo(linkedSetOf()) { it.type }
        is VerticalSplit -> left.declaredTabTypes() + right.declaredTabTypes()
        is HorizontalSplit -> top.declaredTabTypes() + bottom.declaredTabTypes()
    }

/**
 * The tabs [node] would actually put on screen if applied right now.
 *
 * This is the proof [applyWorkspace] needs before it may clear the live tree. The probe uses
 * metadata, without invoking tab constructors or loading their favicon cache. Two checks
 * decide, both of them the same ones the
 * build runs - a type nothing can build (a plugin uninstalled since the Space was saved)
 * resolves to null, and a resolved tab still needs a registered factory or `addTab` drops it.
 *
 * The walk mirrors `applyWorkspaceNode`'s gating and must keep doing so: a split's second side
 * builds only when its FIRST leaf tab resolves - resolves, not lands; an unregistered type
 * still opens the split and is only dropped by `addTab` inside it - so a side whose leading tab
 * is unbuildable is skipped whole and counting its other tabs would promise a build that
 * cannot happen.
 */
private fun collectBuildableTabs(
    node: SplitConfig,
    projectPath: String,
    splitViewState: SplitViewState,
): List<TabConfig> =
    when (node) {
        is SinglePanel -> {
            node.panel.tabs.mapNotNull { buildableTab(it, projectPath, splitViewState) }
        }

        is VerticalSplit -> {
            val right =
                if (firstTabResolves(node.right, projectPath, splitViewState)) {
                    collectBuildableTabs(node.right, projectPath, splitViewState)
                } else {
                    emptyList()
                }
            collectBuildableTabs(node.left, projectPath, splitViewState) + right
        }

        is HorizontalSplit -> {
            val bottom =
                if (firstTabResolves(node.bottom, projectPath, splitViewState)) {
                    collectBuildableTabs(node.bottom, projectPath, splitViewState)
                } else {
                    emptyList()
                }
            collectBuildableTabs(node.top, projectPath, splitViewState) + bottom
        }
    }

/**
 * The split gate `applyWorkspaceNode` applies to a second side: its first leaf resolves to a
 * tab. Resolution only - a resolved tab whose type has no factory still opens the split and is
 * dropped by `addTab` inside it.
 */
private fun firstTabResolves(
    node: SplitConfig,
    projectPath: String,
    splitViewState: SplitViewState,
): Boolean =
    getFirstTab(node)
        ?.let { resolvableTabType(it, projectPath, splitViewState) } != null

/** The tab [tabConfig] would become and actually land, or null when nothing on screen could hold it. */
private fun buildableTab(
    tabConfig: TabConfig,
    projectPath: String,
    splitViewState: SplitViewState,
): TabConfig? =
    resolvableTabType(tabConfig, projectPath, splitViewState)
        ?.takeIf { splitViewState.tabRegistry.isRegistered(it) }
        ?.let { tabConfig }

/** Probe metadata only: never read favicons, allocate tab IDs or invoke constructors twice. */
private fun resolvableTabType(
    config: TabConfig,
    projectPath: String,
    state: SplitViewState,
): TabTypeId? =
    when (val type = tabTypeIdFor(config)) {
        DiffTabType.typeId -> {
            type.takeUnless {
                config.filePath
                    ?.let { path ->
                        WorkspacePlaceholders.processPlaceholders(path, projectPath, null)
                    }.isNullOrBlank()
            }
        }

        ComposerTabType.typeId -> {
            type.takeUnless { config.filePath.isNullOrBlank() }
        }

        JupyterTabInfo.TYPE_ID -> {
            if (state.tabRegistry.isRegistered(type)) type else CodeEditorTabType.typeId
        }

        else -> {
            type
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
