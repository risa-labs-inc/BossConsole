package ai.rever.boss.components.home

import ai.rever.boss.components.dashboard.cards.BrowserPageCard
import ai.rever.boss.components.dashboard.cards.FileCard
import ai.rever.boss.components.dashboard.cards.ProjectCard
import ai.rever.boss.components.dashboard.cards.WorkspaceCard
import ai.rever.boss.components.dashboard.sections.DashboardSection
import ai.rever.boss.components.plugin.panels.left_top.ProjectState
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.dashboard.RecentBrowserPage
import ai.rever.boss.dashboard.RecentBrowserPagesManager
import ai.rever.boss.dashboard.RecentFile
import ai.rever.boss.dashboard.RecentFilesManager
import ai.rever.boss.keymap.KeymapSettingsManager
import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.keymap.model.shortcutLabelFor
import ai.rever.boss.plugin.scrollbar.verticalScrollWithScrollbar
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.project.ProjectRemovalScope
import ai.rever.boss.project.removeProjectAndReport
import ai.rever.boss.window.LocalWindowProjectState
import ai.rever.boss.window.Project
import ai.rever.boss.window.selectProjectInWindow
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/**
 * The BOSS home screen: what you were working on, everything you can do, and what else exists.
 *
 * **It takes no action callbacks, on purpose.** The screen it replaces took twelve, which meant
 * every mount point had to supply all of them - and the second one, the browser's about:blank
 * surface, passed eleven empty lambdas, so most of the screen rendered normally and did nothing.
 * Here the screen builds its own [HomeActions] from `LocalWindowId` and emits on
 * `DashboardEventBus`, a window-scoped bus whose nine handlers already existed in
 * `BossAppEventBusEffects` and had no emitters at all. One code path, both mounts, and kernel mode
 * carried by the bus's IPC bridge. `HomeActionRoutingTest` pins it.
 *
 * The tool grid is derived from what plugins actually registered, not listed. See
 * [HomeToolCatalog].
 */
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val actions = rememberHomeActions()
    val windowProjectState = LocalWindowProjectState.current
    val space = BossTheme.space

    val selectedProject by windowProjectState?.selectedProject?.collectAsState()
        ?: remember { mutableStateOf(NO_PROJECT) }
    val recentProjects by ProjectState.recentProjects.collectAsState()
    val recentFiles by RecentFilesManager.recentFiles.collectAsState()
    val recentPages by RecentBrowserPagesManager.recentPages.collectAsState()
    // Both flows are collected even though only `recentPages` is read directly: getSuggestions
    // reads its two sources non-reactively, so without collecting the dismissed set here,
    // dismissing a suggested site would change nothing on screen until some unrelated
    // recomposition - which is the bug being fixed, not a smaller version of it.
    val dismissed by RecentBrowserPagesManager.dismissedSuggestions.collectAsState()
    val suggestions =
        remember(recentPages, dismissed) { RecentBrowserPagesManager.getSuggestions(SUGGESTION_LIMIT) }
    // The same WorkspaceManager the top bar's workspace button, the app menu and the
    // default-workspace setting read. The home screen used to list SplitTemplatesManager
    // instead, a second hand-maintained copy of the same layouts (now deleted).
    val workspaces by workspaceManager.workspaces.collectAsState()

    var projectToOpen by remember { mutableStateOf<Project?>(null) }
    var projectToRemove by remember { mutableStateOf<Project?>(null) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    Box(modifier = modifier.fillMaxSize().background(BossTheme.colors.panel)) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScrollWithScrollbar(scrollState)
                    .padding(space.xxl),
            verticalArrangement = Arrangement.spacedBy(space.xl),
        ) {
            HomeHeader(
                projectName = selectedProject.name.ifBlank { "No project" },
                onSearch = actions::openSearch,
            )

            JumpBackInSection(
                recentProjects = recentProjects,
                windowHoldsProject = selectedProject.path.isNotEmpty(),
                actions = actions,
                onAskWhichWindow = { projectToOpen = it },
                onOpenHere = { selectProjectInWindow(windowProjectState, it) },
                onAskToRemove = { projectToRemove = it },
            )

            RecentPagesSection(suggestions = suggestions, actions = actions)

            ToolsSection(actions = actions)

            WorkspaceLayoutsSection(workspaces = workspaces, actions = actions)

            RecentFilesSection(files = recentFiles, actions = actions)
        }
    }

    HomeProjectDialogs(
        projectToOpen = projectToOpen,
        projectToRemove = projectToRemove,
        openProjectPath = selectedProject.path,
        onOpenHere = { selectProjectInWindow(windowProjectState, it) },
        onOpenDone = { projectToOpen = null },
        onRemoveDone = { projectToRemove = null },
        onRemove = { project, removalScope -> scope.launch { removeProjectAndReport(project, removalScope) } },
    )
}

private val NO_PROJECT = Project("No Project", "", 0L)

/** Recent pages shown, matching what the suggestion ranking is tuned to return. */
private const val SUGGESTION_LIMIT = 20

/** Recent files shown before the strip gets longer than it is useful. */
private const val RECENT_FILE_LIMIT = 8

@Composable
private fun JumpBackInSection(
    recentProjects: List<Project>,
    windowHoldsProject: Boolean,
    actions: HomeActions,
    onAskWhichWindow: (Project) -> Unit,
    onOpenHere: (Project) -> Unit,
    onAskToRemove: (Project) -> Unit,
) {
    if (recentProjects.isEmpty()) return
    DashboardSection(
        title = "Jump back in",
        actionText = "Open Project",
        onAction = actions::openProjectDialog,
    ) {
        CardStrip {
            recentProjects.forEach { project ->
                ProjectCard(
                    project = project,
                    // Only ask which window when this one already holds a project.
                    onClick = { if (windowHoldsProject) onAskWhichWindow(project) else onOpenHere(project) },
                    // Asks rather than removing. The cross used to forget the project on
                    // the click, with no undo and no way to get rid of the folder.
                    onRemove = { onAskToRemove(project) },
                )
            }
        }
    }
}

@Composable
private fun ToolsSection(actions: HomeActions) {
    // The retired-plugin floor reads installed.json, which only desktopMain can
    // reach: the lookup is an expect/actual so commonMain stays platform-agnostic
    // (the same seam `warmBrowserEngineForTabs` uses).
    val tools = rememberHomeTools(installedVersionOf = ::installedPluginVersionOf)
    val keymap by KeymapSettingsManager.currentSettings.collectAsState()
    var filter by remember { mutableStateOf(HomeToolFilter.ALL) }
    // Ids currently installing, so a tile shows progress rather than looking unresponsive for the
    // length of a download. A snapshot map because installs run concurrently and each tile reads
    // only its own entry.
    val installing = remember { SnapshotStateMap<String, Unit>() }

    DashboardSection(title = "Tools", subtitle = toolsSubtitle(tools)) {
        HomeToolGrid(
            tools = tools,
            installing = installing.keys,
            filter = filter,
            onSelectFilter = { filter = it },
            shortcutFor = { tool -> tool.shortcutLabel(keymap.shortcuts) },
            onToolClick = { tool -> actions.launch(tool, installing) },
        )
    }
}

@Composable
private fun WorkspaceLayoutsSection(
    workspaces: List<LayoutWorkspace>,
    actions: HomeActions,
) {
    if (workspaces.isEmpty()) return
    DashboardSection(title = "Workspace layouts", subtitle = "Open a whole arrangement at once") {
        CardStrip {
            workspaces.forEach { workspace ->
                WorkspaceCard(
                    workspace = workspace,
                    // By id, not by value: the event crosses a process boundary in kernel
                    // mode, and the handler re-reads the manager so a workspace edited
                    // between render and click applies as it is now.
                    onClick = { actions.applyWorkspace(workspace.id) },
                )
            }
        }
    }
}

@Composable
private fun RecentFilesSection(
    files: List<RecentFile>,
    actions: HomeActions,
) {
    if (files.isEmpty()) return
    DashboardSection(
        title = "Recent files",
        actionText = "Clear",
        onAction = { RecentFilesManager.clearAll() },
    ) {
        CardStrip {
            files.take(RECENT_FILE_LIMIT).forEach { file ->
                FileCard(
                    file = file,
                    onClick = { actions.openFile(file.path) },
                    onRemove = { RecentFilesManager.removeFile(file.path) },
                )
            }
        }
    }
}

@Composable
private fun RecentPagesSection(
    suggestions: List<RecentBrowserPage>,
    actions: HomeActions,
) {
    if (suggestions.isEmpty()) return
    DashboardSection(
        title = "Recent pages",
        // Unconditional, because the case that needs it most is the one a
        // `if (recentPages.isNotEmpty())` gate excluded: a fresh install has no recorded pages
        // and seventeen suggested sites on screen, so gating on recorded pages hid Clear exactly
        // when the strip was entirely padding. The section already returns early when there is
        // nothing to show, so the label never appears over an empty strip.
        actionText = "Clear",
        onAction = { RecentBrowserPagesManager.clearAll() },
    ) {
        CardStrip {
            suggestions.forEach { page ->
                BrowserPageCard(
                    page = page,
                    onClick = { actions.openUrl(page.url) },
                    onRemove = { RecentBrowserPagesManager.removePage(page.url) },
                )
            }
        }
    }
}

/**
 * A horizontally scrolling strip, for the lists that are genuinely ordered by recency.
 *
 * Kept for recents, unlike the tool grid: "most recent first" is a line, and a wrapping grid of
 * twenty recent pages would dominate a screen whose point is the tools. The tools themselves are
 * an unordered set, which is why they wrap instead.
 */
@Composable
private fun CardStrip(content: @Composable () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(BossTheme.space.md),
    ) {
        content()
    }
}

private fun toolsSubtitle(tools: List<HomeTool>): String {
    val ready = tools.count { it.isReady }
    val discoverable = tools.size - ready
    return if (discoverable > 0) "$ready ready, $discoverable more available" else "$ready ready"
}

/**
 * The keymap label for a tool, or null when it has no binding.
 *
 * Only the four host actions that genuinely map onto a [KeymapActions] id get one. The old screen
 * printed "Cmd+O", "Cmd+P" and "Cmd+`" for actions that have no binding at all - there is no
 * `FILE_OPEN`, `PROJECT_OPEN` or `TERMINAL_NEW` action - on every platform, and wrong again for
 * anyone who had rebound something.
 */
private fun HomeTool.shortcutLabel(bindings: Map<String, KeyBinding>): String? {
    val action = (launch as? HomeToolLaunch.HostAction)?.action ?: return null
    val actionId =
        when (action) {
            HomeHostAction.NEW_TAB -> KeymapActions.TAB_NEW

            HomeHostAction.NEW_WINDOW -> KeymapActions.WINDOW_NEW

            HomeHostAction.SETTINGS -> KeymapActions.SETTINGS_OPEN

            HomeHostAction.SEARCH -> KeymapActions.GLOBAL_SEARCH_OPEN

            // No binding exists for these, so nothing is shown.
            HomeHostAction.NEW_TERMINAL,
            HomeHostAction.OPEN_FILE,
            HomeHostAction.OPEN_PROJECT,
            HomeHostAction.NEW_PROJECT,
            -> null
        }
    return actionId?.let { shortcutLabelFor(it, bindings) }
}
