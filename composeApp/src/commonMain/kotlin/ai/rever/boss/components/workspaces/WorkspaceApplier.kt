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
    // Wait (bounded) for the plugin-provided tab types this workspace needs -
    // at startup the workspace flow emits before the dynamic plugins that own
    // browser/terminal/editor have registered their factories, and addTab
    // drops any tab whose type has no factory yet.
    val requiredTabTypes =
        WorkspaceTabTypes
            .collectRequired(workspace.layout)
            .filterNot {
                // The jupyter notebook is the only shipped tab type with a restore-side fallback
                // (createTabFromWorkspaceConfig rebuilds it as an editor tab when the plugin is
                // absent). Including it in the wait made every apply block for the full
                // PLUGIN_REGISTRATION_TIMEOUT_MS on a machine without the optional jupyter-notebook
                // plugin - 15s per apply, every cold start, with nothing to show for it. Drop it
                // here so the wait only fires for types that will actually be added.
                it == JupyterTabInfo.TYPE_ID && !splitViewState.tabRegistry.isRegistered(it)
            }.toSet()

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
    applyWorkspaceNode(
        ctx = ApplyCtx(splitViewState, "main", currentProjectPath),
        node = workspace.layout,
    )
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

/**
 * Shared state for the recursive apply: the split view we're mutating, the pane the current
 * recursion level lives in, and the resolved project path that every terminal tab should land in.
 *
 * Bundled so the recursive helpers stay under detekt's parameter threshold - the three values
 * travel together on every step of the walk, and un-bundling them would multiply the parameter
 * count of every function in the chain.
 */
private data class ApplyCtx(
    val splitViewState: SplitViewState,
    val panelId: String,
    val projectPath: String,
)

/**
 * Tab-type-id mapping and tab-type-id collection. Lives in a private object so its functions
 * count against that object's function budget, not this file's, which keeps the file under the
 * `TooManyFunctions` threshold.
 */
private object WorkspaceTabTypes {
    /** Collect the tab type IDs a workspace layout needs, ignoring unsupported/legacy entries. */
    fun collectRequired(node: SplitConfig): Set<TabTypeId> =
        when (node) {
            is SinglePanel -> {
                node.panel.tabs
                    .mapNotNull { typeIdFor(it) }
                    .toSet()
            }

            is VerticalSplit -> {
                collectRequired(node.left) + collectRequired(node.right)
            }

            is HorizontalSplit -> {
                collectRequired(node.top) + collectRequired(node.bottom)
            }
        }

    /**
     * Single source of truth for which persisted tab types are restorable and
     * which plugin tab type owns each. Both the pre-apply wait
     * ([collectRequired]) and the construction dispatch in
     * [createTabFromWorkspaceConfig] key off this mapping, so a new tab type
     * added here is automatically waited for before restore.
     *
     * Returns null for unsupported/legacy/transient types (e.g. a
     * sidebar-promoted "panel-host" tab that should never have been persisted) —
     * those are skipped instead of crashing the whole workspace restore.
     */
    fun typeIdFor(tabConfig: TabConfig): TabTypeId? =
        when (tabConfig.type) {
            "browser" -> FluckTabType.typeId
            "terminal" -> TerminalTabType.typeId
            "editor" -> CodeEditorTabType.typeId
            "diff" -> DiffTabType.typeId
            "jupyter" -> JupyterTabInfo.TYPE_ID
            "composer" -> ComposerTabType.typeId
            else -> null
        }
}

private suspend fun applyWorkspaceNode(
    ctx: ApplyCtx,
    node: SplitConfig,
    skipFirstTab: Boolean = false,
) {
    when (node) {
        is SinglePanel -> {
            applySinglePanel(
                panel = node,
                ctx = ctx,
                skipFirstTab = skipFirstTab,
            )
        }

        is VerticalSplit -> {
            applySplit(
                firstSide = node.left,
                secondSide = node.right,
                ctx = ctx,
                orientation = SplitOrientation.VERTICAL,
                skipFirstTab = skipFirstTab,
            )
        }

        is HorizontalSplit -> {
            applySplit(
                firstSide = node.top,
                secondSide = node.bottom,
                ctx = ctx,
                orientation = SplitOrientation.HORIZONTAL,
                skipFirstTab = skipFirstTab,
            )
        }
    }
}

/**
 * Add a [SinglePanel]'s tabs to the pane identified by [ctx]'s `panelId`, then restore the
 * pinned count.
 *
 * `skipFirstTab = true` drops the first tab of `panel.tabs` because the caller has already
 * placed it in this pane via `splitPanel` - either this split's own secondSide copy, or the
 * outer split's copy that propagated down through `applyWorkspaceNode`'s split branches.
 * Without it, any template whose right/bottom side is itself a split duplicated the moved tab
 * in the inner firstSide - which is what made Build show up twice in Project Studio's bottom-left.
 *
 * `setPinnedCount` is the only place pinning is restored, and it is clamped internally: a tab
 * whose type no longer resolves comes back as null above, so fewer tabs can land than were
 * saved with this count.
 */
private suspend fun applySinglePanel(
    panel: SinglePanel,
    ctx: ApplyCtx,
    skipFirstTab: Boolean,
) {
    val tabsComponent = ctx.splitViewState.getPanelTabsComponent(ctx.panelId)
    val tabsToAdd = if (skipFirstTab) panel.panel.tabs.drop(1) else panel.panel.tabs
    tabsToAdd.forEach { tabConfig ->
        createTabFromWorkspaceConfig(tabConfig, ctx.projectPath, ctx.splitViewState)
            ?.let { tabsComponent?.addTab(it) }
    }
    tabsComponent?.setPinnedCount(panel.panel.pinnedCount)
}

/**
 * Apply a split: split the current pane into the requested orientation first, so the OUTER
 * split's orientation is the one that ends up at the root of the resulting tree, then populate
 * the original (first) side and the new (second) side in turn.
 *
 * Splitting first is what makes a template like Project Studio - `HS(top = VS, bottom = VS)` -
 * render as four panes arranged in a 2x2 instead of as a single vertical split with the
 * PLAN/NOTES editor spanning the full height of the right column. Applying `firstSide` first
 * would build the inner top VS at the root, and the subsequent split would be nested inside
 * that VS's left panel, so the rendered tree looked nothing like the template the user picked.
 *
 * `skipFirstTab` propagates from an outer secondSide whose splitPanel already moved the first
 * tab into this pane: `applyWorkspaceNode` forwards it through both split branches so an inner
 * split sees the pane as already occupied and does not duplicate the moved tab when it lands
 * its own first side. The second side of THIS split always passes `skipFirstTab = true` -
 * splitPanel just copied its leading tab into the new pane.
 *
 * Resolves the second side's first tab up front so a legacy/unsupported tab type refuses the
 * split cleanly - `splitPanel(tabToMove = null)` would otherwise create an empty "ghost" pane.
 * The split orientation is the only difference between vertical and horizontal; the recursion
 * shape is identical.
 */
private suspend fun applySplit(
    firstSide: SplitConfig,
    secondSide: SplitConfig,
    ctx: ApplyCtx,
    orientation: SplitOrientation,
    skipFirstTab: Boolean = false,
) {
    // Local so this file stays under detekt's TooManyFunctions budget - the helper has exactly
    // one caller, and `applySplit` is the only place that needs the second side's leading tab to
    // refuse the split cleanly when it would be a "ghost" pane.
    fun firstTab(node: SplitConfig): TabConfig? =
        when (node) {
            is SinglePanel -> node.panel.tabs.firstOrNull()
            is VerticalSplit -> firstTab(node.left)
            is HorizontalSplit -> firstTab(node.top)
        }

    val firstSecondTab =
        firstTab(secondSide)
            ?.let { createTabFromWorkspaceConfig(it, ctx.projectPath, ctx.splitViewState) }

    if (firstSecondTab == null) {
        // No second side to split off - land firstSide directly in the current pane. Splitting
        // here would create an empty "ghost" pane that nothing else would fill.
        applySplitSide(
            node = firstSide,
            ctx = ctx,
            skipFirstTab = skipFirstTab,
        )
        return
    }

    // Split off the new pane first so the OUTER split's orientation ends up at the root of the
    // resulting tree; applying firstSide first would put the inner firstSide at the root.
    val newPanelId =
        ctx.splitViewState.splitPanel(
            panelId = ctx.panelId,
            orientation = orientation,
            tabToMove = firstSecondTab,
        )

    // The original pane now lives on the firstSide half of the outer split.
    applySplitSide(
        node = firstSide,
        ctx = ctx,
        skipFirstTab = skipFirstTab,
    )

    // The new pane already holds the second side's leading tab.
    applySplitSide(
        node = secondSide,
        ctx = ctx.copy(panelId = newPanelId),
        skipFirstTab = true,
    )
}

/**
 * Populate one side of a split: add its tabs (or recurse if it is itself a split). The two call
 * sites differ only in `skipFirstTab` - false for the side that lives in the original pane,
 * true for the side split off into a new pane whose first tab is already in place.
 */
private suspend fun applySplitSide(
    node: SplitConfig,
    ctx: ApplyCtx,
    skipFirstTab: Boolean,
) {
    when (node) {
        is SinglePanel -> {
            applySinglePanel(
                panel = node,
                ctx = ctx,
                skipFirstTab = skipFirstTab,
            )
        }

        else -> {
            applyWorkspaceNode(
                ctx = ctx,
                node = node,
                skipFirstTab = skipFirstTab,
            )
        }
    }
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
    // Dispatch on the resolved type id (see WorkspaceTabTypes.typeIdFor) so the mapping that
    // decides what restore waits for and the mapping that constructs tabs cannot drift apart.
    return when (WorkspaceTabTypes.typeIdFor(tabConfig)) {
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
