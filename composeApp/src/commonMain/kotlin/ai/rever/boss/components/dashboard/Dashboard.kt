package ai.rever.boss.components.dashboard

import BossDarkBackground
import BossDarkSurface
import BossDarkTextSecondary
import ai.rever.boss.components.dashboard.cards.ActionCard
import ai.rever.boss.components.dashboard.cards.BrowserPageCard
import ai.rever.boss.components.dashboard.cards.FileCard
import ai.rever.boss.components.dashboard.cards.ProjectCard
import ai.rever.boss.components.dashboard.cards.SplitTemplateCard
import ai.rever.boss.components.dashboard.sections.DashboardSection
import ai.rever.boss.components.dialogs.ProjectOpenModeDialog
import ai.rever.boss.components.events.PanelEventBus
import ai.rever.boss.components.events.TerminalEventBus
import ai.rever.boss.components.events.URLEventBus
import ai.rever.boss.components.plugin.PanelIds
import ai.rever.boss.window.Project
import ai.rever.boss.components.plugin.panels.left_top.ProjectState
import ai.rever.boss.dashboard.DashboardStatsManager
import ai.rever.boss.dashboard.RecentBrowserPagesManager
import ai.rever.boss.dashboard.RecentFilesManager
import ai.rever.boss.dashboard.SplitTemplate
import ai.rever.boss.dashboard.SplitTemplatesManager
import ai.rever.boss.window.LocalWindowId
import ai.rever.boss.window.WindowOperations
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Main Dashboard component that displays when no tabs are open.
 * Shows recent projects, files, browser pages, split templates, and quick actions.
 *
 * @param onOpenFile Callback to open a file by path
 * @param onOpenUrl Callback to open a URL in browser
 * @param onOpenProject Callback to select a project
 * @param onNewTab Callback to show new tab dialog
 * @param onNewTerminal Callback to create a new terminal
 * @param onNewWindow Callback to create a new window
 * @param onOpenProjectDialog Callback to show project selection dialog
 * @param onOpenFileDialog Callback to show file open dialog
 * @param onApplySplitTemplate Callback to apply a split template
 * @param onActivatePlugin Callback to activate a plugin panel
 */
@Composable
fun Dashboard(
    onOpenFile: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenProject: (Project) -> Unit,
    selectedProject: Project = Project("No Project", "", 0L),
    onNewTab: () -> Unit,
    onNewTerminal: () -> Unit,
    onNewWindow: () -> Unit,
    onOpenProjectDialog: () -> Unit,
    onOpenFileDialog: () -> Unit,
    onNewProject: () -> Unit,
    onApplySplitTemplate: (SplitTemplate) -> Unit,
    onActivatePlugin: (String) -> Unit,
    onShowSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val dialogScope = rememberCoroutineScope()
    val windowId = LocalWindowId.current

    // State for project open mode dialog
    var projectToOpen by remember { mutableStateOf<Project?>(null) }

    // Collect state from managers
    val recentProjects by ProjectState.recentProjects.collectAsState()
    val recentFiles by RecentFilesManager.recentFiles.collectAsState()
    val recentPages by RecentBrowserPagesManager.recentPages.collectAsState()
    val suggestions = RecentBrowserPagesManager.getSuggestions(20)
    val splitTemplates by SplitTemplatesManager.allTemplates.collectAsState()
    val stats by DashboardStatsManager.stats.collectAsState()

    // Animation state for staggered section appearance
    var showSections by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(100)
        showSections = true
    }

    // Session duration update (with isActive check to prevent memory leak on disposal)
    var sessionDuration by remember { mutableStateOf(DashboardStatsManager.formatSessionDuration()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1000)
            sessionDuration = DashboardStatsManager.formatSessionDuration()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BossDarkBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Header with welcome message and stats
            DashboardHeader(
                sessionDuration = sessionDuration,
                todayFiles = stats.todayActivity.filesOpened,
                todayPages = stats.todayActivity.pagesVisited,
                showSections = showSections
            )

            // Recent Projects Section (or Open Project button for new users)
            AnimatedVisibility(
                visible = showSections,
                enter = fadeIn(tween(300, delayMillis = 100)) + slideInVertically(
                    tween(300, delayMillis = 100)
                ) { it / 2 }
            ) {
                if (recentProjects.isNotEmpty()) {
                    DashboardSection(
                        title = "Recent Projects",
                        actionText = "Open Project",
                        onAction = onOpenProjectDialog
                    ) {
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 4.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            recentProjects.take(20).forEach { project ->
                                ProjectCard(
                                    project = project,
                                    onClick = {
                                        // Only show dialog if a project is already selected in this window
                                        if (selectedProject.path.isNotEmpty()) {
                                            projectToOpen = project
                                        } else {
                                            // No project selected, open directly in current window
                                            // Panels are opened automatically by BossApp's LaunchedEffect
                                            onOpenProject(project)
                                        }
                                    },
                                    onRemove = { ProjectState.removeRecentProject(project.path) }
                                )
                            }
                        }
                    }
                } else {
                    // New user: Show centered "Open Project" button
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material.Button(
                            onClick = onOpenProjectDialog,
                            colors = androidx.compose.material.ButtonDefaults.buttonColors(
                                backgroundColor = Color(0xFF4A9EFF),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FolderOpen,
                                contentDescription = "Open Project Folder",
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(
                                text = "Open Project",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Workspace Suggestions Section
            AnimatedVisibility(
                visible = showSections,
                enter = fadeIn(tween(300, delayMillis = 200)) + slideInVertically(
                    tween(300, delayMillis = 200)
                ) { it / 2 }
            ) {
                DashboardSection(
                    title = "Workspace Suggestions",
                    subtitle = "Quick workspace layouts"
                ) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        splitTemplates.forEach { template ->
                            SplitTemplateCard(
                                template = template,
                                onClick = { onApplySplitTemplate(template) }
                            )
                        }
                    }
                }
            }

            // Recent Files Section
            AnimatedVisibility(
                visible = showSections && recentFiles.isNotEmpty(),
                enter = fadeIn(tween(300, delayMillis = 300)) + slideInVertically(
                    tween(300, delayMillis = 300)
                ) { it / 2 }
            ) {
                DashboardSection(
                    title = "Recent Files",
                    actionText = "Clear",
                    onAction = { RecentFilesManager.clearAll() }
                ) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        recentFiles.take(8).forEach { file ->
                            FileCard(
                                file = file,
                                onClick = { onOpenFile(file.path) },
                                onRemove = { RecentFilesManager.removeFile(file.path) }
                            )
                        }
                    }
                }
            }

            // Browse Suggestions Section (Recent Browser Pages + Popular Dev Sites)
            AnimatedVisibility(
                visible = showSections && suggestions.isNotEmpty(),
                enter = fadeIn(tween(300, delayMillis = 400)) + slideInVertically(
                    tween(300, delayMillis = 400)
                ) { it / 2 }
            ) {
                DashboardSection(
                    title = "Browse Suggestions",
                    actionText = if (recentPages.isNotEmpty()) "Clear" else null,
                    onAction = { RecentBrowserPagesManager.clearAll() }
                ) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        suggestions.forEach { page ->
                            BrowserPageCard(
                                page = page,
                                onClick = { onOpenUrl(page.url) },
                                onRemove = { RecentBrowserPagesManager.removePage(page.url) }
                            )
                        }
                    }
                }
            }

            // Quick Actions Section
            AnimatedVisibility(
                visible = showSections,
                enter = fadeIn(tween(300, delayMillis = 500)) + slideInVertically(
                    tween(300, delayMillis = 500)
                ) { it / 2 }
            ) {
                val scope = rememberCoroutineScope()

                DashboardSection(
                    title = "Quick Actions"
                ) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ActionCard(
                            icon = Icons.Outlined.FolderOpen,
                            title = "Open File",
                            shortcut = "Cmd+O",
                            onClick = onOpenFileDialog
                        )
                        ActionCard(
                            icon = Icons.Outlined.CreateNewFolder,
                            title = "New Project",
                            onClick = onNewProject
                        )
                        ActionCard(
                            icon = Icons.Outlined.Add,
                            title = "New Tab",
                            shortcut = "Cmd+T",
                            onClick = onNewTab
                        )
                        ActionCard(
                            icon = Icons.Outlined.Terminal,
                            title = "New Terminal",
                            shortcut = "Cmd+`",
                            onClick = {
                                windowId?.let { wid ->
                                    scope.launch {
                                        TerminalEventBus.openTerminal(sourceWindowId = wid)
                                    }
                                }
                            }
                        )
                        ActionCard(
                            icon = Icons.Outlined.OpenInBrowser,
                            title = "New Window",
                            shortcut = "Cmd+N",
                            onClick = onNewWindow
                        )
                        ActionCard(
                            icon = Icons.Outlined.Folder,
                            title = "Open Project",
                            shortcut = "Cmd+P",
                            onClick = onOpenProjectDialog
                        )
                        ActionCard(
                            icon = Icons.Outlined.Settings,
                            title = "Settings",
                            shortcut = "Cmd+,",
                            onClick = { onShowSettings?.invoke() }
                        )
                        ActionCard(
                            icon = Icons.Outlined.Code,
                            title = "CodeBase",
                            onClick = {
                                windowId?.let { wid ->
                                    scope.launch {
                                        PanelEventBus.togglePanel(PanelIds.CODEBASE, sourceWindowId = wid)
                                    }
                                }
                            }
                        )
                        ActionCard(
                            icon = Icons.Outlined.PlayArrow,
                            title = "Run Config",
                            onClick = {
                                windowId?.let { wid ->
                                    scope.launch {
                                        PanelEventBus.togglePanel(PanelIds.RUN_CONFIGURATIONS, sourceWindowId = wid)
                                    }
                                }
                            }
                        )
                        ActionCard(
                            icon = Icons.Outlined.Star,
                            title = "Bookmarks",
                            onClick = {
                                windowId?.let { wid ->
                                    scope.launch {
                                        PanelEventBus.togglePanel(PanelIds.BOOKMARKS, sourceWindowId = wid)
                                    }
                                }
                            }
                        )
                        ActionCard(
                            icon = Icons.Outlined.Download,
                            title = "Downloads",
                            onClick = {
                                windowId?.let { wid ->
                                    scope.launch {
                                        PanelEventBus.togglePanel(PanelIds.DOWNLOADS, sourceWindowId = wid)
                                    }
                                }
                            }
                        )
                        ActionCard(
                            icon = Icons.Outlined.Language,
                            title = "Browse Web",
                            onClick = {
                                windowId?.let { wid ->
                                    scope.launch {
                                        URLEventBus.openURL("https://google.com", "Google", sourceWindowId = wid)
                                    }
                                }
                            }
                        )
                    }
                }
            }

            // Developer Tools Section
            AnimatedVisibility(
                visible = showSections,
                enter = fadeIn(tween(300, delayMillis = 550)) + slideInVertically(
                    tween(300, delayMillis = 550)
                ) { it / 2 }
            ) {
                val scope = rememberCoroutineScope()

                DashboardSection(
                    title = "Developer Tools"
                ) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ActionCard(
                            icon = Icons.Outlined.Terminal,
                            title = "Terminal",
                            onClick = {
                                windowId?.let { wid ->
                                    scope.launch {
                                        PanelEventBus.togglePanel(PanelIds.TERMINAL, sourceWindowId = wid)
                                    }
                                }
                            }
                        )
                        ActionCard(
                            icon = Icons.Outlined.Language,
                            title = "Browser",
                            onClick = {
                                windowId?.let { wid ->
                                    scope.launch {
                                        URLEventBus.openURL("https://github.com", "GitHub", sourceWindowId = wid)
                                    }
                                }
                            }
                        )
                        ActionCard(
                            icon = Icons.Outlined.Code,
                            title = "CodeBase",
                            onClick = {
                                windowId?.let { wid ->
                                    scope.launch {
                                        PanelEventBus.togglePanel(PanelIds.CODEBASE, sourceWindowId = wid)
                                    }
                                }
                            }
                        )
                        ActionCard(
                            icon = Icons.Outlined.PlayArrow,
                            title = "Runner",
                            onClick = {
                                windowId?.let { wid ->
                                    scope.launch {
                                        PanelEventBus.togglePanel(PanelIds.RUN_CONFIGURATIONS, sourceWindowId = wid)
                                    }
                                }
                            }
                        )
                        ActionCard(
                            icon = Icons.Outlined.Star,
                            title = "Bookmarks",
                            onClick = {
                                windowId?.let { wid ->
                                    scope.launch {
                                        PanelEventBus.togglePanel(PanelIds.BOOKMARKS, sourceWindowId = wid)
                                    }
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Project open mode dialog
    projectToOpen?.let { project ->
        ProjectOpenModeDialog(
            project = project,
            onDismiss = { projectToOpen = null },
            onOpenInCurrentWindow = { selectedProject ->
                // Panels are opened automatically by BossApp's LaunchedEffect
                onOpenProject(selectedProject)
                projectToOpen = null
            },
            onOpenInNewWindow = { selectedProject ->
                // Create new window first, then select project (project state is global)
                // Panels are opened automatically by BossApp's LaunchedEffect in the new window
                WindowOperations.createNewWindow()
                onOpenProject(selectedProject)
                projectToOpen = null
            }
        )
    }
}

/** Below this width the stats card stacks under the welcome text instead of sitting beside it. */
private val HeaderCompactWidth = 700.dp

/**
 * Dashboard header with welcome message and session stats.
 * Side-by-side on wide panels, stacked vertically on narrow ones.
 */
@Composable
private fun DashboardHeader(
    sessionDuration: String,
    todayFiles: Int,
    todayPages: Int,
    showSections: Boolean
) {
    val alpha by animateFloatAsState(
        targetValue = if (showSections) 1f else 0f,
        animationSpec = tween(500)
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha)
    ) {
        if (maxWidth < HeaderCompactWidth) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                WelcomeMessage()
                StatsSummary(
                    sessionDuration = sessionDuration,
                    todayFiles = todayFiles,
                    todayPages = todayPages
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                WelcomeMessage(modifier = Modifier.weight(1f).padding(end = 24.dp))
                StatsSummary(
                    sessionDuration = sessionDuration,
                    todayFiles = todayFiles,
                    todayPages = todayPages
                )
            }
        }
    }
}

@Composable
private fun WelcomeMessage(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = "Welcome To Boss Console",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "What would you like to work on today?",
            color = BossDarkTextSecondary,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun StatsSummary(
    sessionDuration: String,
    todayFiles: Int,
    todayPages: Int
) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .background(
                color = BossDarkSurface,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatItem(label = "Session", value = sessionDuration)
        StatDivider()
        StatItem(label = "Files Today", value = todayFiles.toString())
        StatDivider()
        StatItem(label = "Pages Today", value = todayPages.toString())
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = Color(0xFF4A9EFF),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false
        )
        Text(
            text = label,
            color = BossDarkTextSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun StatDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(24.dp)
            .background(BossDarkTextSecondary.copy(alpha = 0.3f))
    )
}
