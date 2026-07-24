package ai.rever.boss.components.bars.horizontal

import ai.rever.boss.components.buttons.BossActionButton
import ai.rever.boss.components.dialogs.CommitDialog
import ai.rever.boss.components.dialogs.LogoutConfirmationDialog
import ai.rever.boss.components.dialogs.ProjectOpenModeDialog
import ai.rever.boss.components.dialogs.ProjectSelectionDialog
import ai.rever.boss.components.events.PanelEventBus
import ai.rever.boss.components.events.TerminalLinkEventBus
import ai.rever.boss.components.model.BossDraggableComponent
import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.components.overlays.contextMenu
import ai.rever.boss.components.plugin.panels.left_top.ProjectState
import ai.rever.boss.components.windows.SettingsWindow
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.WorkspaceButton
import ai.rever.boss.components.workspaces.WorkspaceManager
import ai.rever.boss.git.GitBranchInfo
import ai.rever.boss.git.GitOperationResult
import ai.rever.boss.git.GitService
import ai.rever.boss.git.GitStashInfo
import ai.rever.boss.platform.rememberDirectoryPicker
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.services.supabase.AuthService
import ai.rever.boss.utils.extractFileName
import ai.rever.boss.window.LocalWindowGitState
import ai.rever.boss.window.LocalWindowId
import ai.rever.boss.window.LocalWindowProjectState
import ai.rever.boss.window.Project
import ai.rever.boss.window.WindowGitState
import ai.rever.boss.window.WindowOperations
import ai.rever.boss.window.selectProjectInWindow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.Divider
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.MergeType
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.GitBranch
import kotlinx.coroutines.launch
import java.io.File
import ai.rever.boss.plugin.git.GitOperationResult.Error as GitError
import ai.rever.boss.plugin.git.GitOperationResult.Success as GitSuccess

@Composable
fun BossDraggableComponent.BossTopBar(
    workspaceManager: WorkspaceManager? = null,
    onApplyWorkspace: ((LayoutWorkspace) -> Unit)? = null,
    getCurrentWorkspace: (() -> LayoutWorkspace)? = null,
    onShowTopOfMind: (() -> Unit)? = null,
    onShowSettings: (() -> Unit)? = null,
    onShowSearch: (() -> Unit)? = null,
    onNewProject: (() -> Unit)? = null,
    onCloneProject: (() -> Unit)? = null,
) {
    val items =
        listOf(
            ContextMenuItem(
                text = "Edit",
                icon = Icons.Outlined.Edit,
                onClick = { /* Handle edit action */ },
            ),
            ContextMenuItem(isDivider = true),
            ContextMenuItem(
                text = "Save",
                icon = Icons.Outlined.Save,
                onClick = { /* Handle save action */ },
            ),
        )

    HorizontalBar(modifier = Modifier.contextMenu(items = items), height = 40.dp) {
        HorizontalBarRow(modifier = Modifier.fillMaxHeight().padding(start = 36.dp)) {
            BossTopLeftBar(workspaceManager, onApplyWorkspace, getCurrentWorkspace, onShowTopOfMind, onNewProject, onCloneProject)
            Spacer(modifier = Modifier.weight(1f))
            // Run/debug controls (Issue #91 / #321)
            BossTopRunBar()
            Spacer(modifier = Modifier.weight(0.1f))
            BossTopRightBar(onShowSettings = onShowSettings, onShowSearch = onShowSearch)
        }
    }
    Divider(color = BossTheme.colors.line)
}

@Composable
fun Logo(name: String) {
    Surface(
        modifier =
            Modifier
                .padding(2.dp)
                .height(22.dp)
                .width(22.dp),
        shape = RoundedCornerShape(4.dp),
        color = BossTheme.colors.signal,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Handle names with < 2 characters gracefully
            val initials =
                when {
                    name.length >= 2 -> name.substring(0, 2)
                    name.isNotEmpty() -> name[0].toString()
                    else -> "?" // Fallback for empty names
                }
            Text(
                text = initials.uppercase(),
                fontSize = 11.sp,
                modifier =
                    Modifier
                        .align(Alignment.Center),
            )
        }
    }
}

@Composable
fun BossActionButtonWithLogo(
    text: String,
    contextMenuItems: List<ContextMenuItem>,
    hintText: String? = null,
    onClick: () -> Unit = {},
) {
    BossActionButton(
        leftLogo = { Logo(text) },
        text = text,
        contextMenuItems = contextMenuItems,
        hintText = hintText,
        onClick = onClick,
    )
}

@Composable
fun BossDraggableComponent.getProjectSelectContextMenuItems(
    showProjectDialog: () -> Unit,
    showNewProjectDialog: () -> Unit,
    showCloneProjectDialog: () -> Unit,
    onProjectSelected: (Project) -> Unit,
): List<ContextMenuItem> {
    val recentProjects by ProjectState.recentProjects.collectAsState()

    return buildList {
        // Recent projects with remove button
        addAll(
            recentProjects.map { project ->
                ContextMenuItem(
                    text = project.name,
                    icon = Icons.Outlined.Folder,
                    trailingIcon = Icons.Outlined.Close,
                    trailingIconColor = androidx.compose.ui.graphics.Color.Gray,
                    onTrailingClick = { ProjectState.removeRecentProject(project.path) },
                    onClick = { onProjectSelected(project) },
                )
            },
        )

        if (recentProjects.isNotEmpty()) {
            add(ContextMenuItem(isDivider = true))
        }

        // Add option to create a new project
        add(
            ContextMenuItem(
                text = "New Project...",
                icon = Icons.Outlined.CreateNewFolder,
                onClick = showNewProjectDialog,
            ),
        )

        // Add option to open an existing project
        add(
            ContextMenuItem(
                text = "Open Project...",
                icon = Icons.Filled.Add,
                onClick = showProjectDialog,
            ),
        )

        // Add option to clone a project from Git
        add(
            ContextMenuItem(
                text = "Clone Project...",
                icon = Icons.Outlined.CloudDownload,
                onClick = showCloneProjectDialog,
            ),
        )
    }
}

/**
 * Build context menu items for Git branch operations.
 * Issue #90: Git Integration for Top Bar
 *
 * @param currentBranch The current branch name (or null if detached/error)
 * @param localBranches List of local branches
 * @param remoteBranches List of remote tracking branches
 * @param stashList List of stash entries
 * @param isLoading Whether git data is currently being fetched
 * @param onCheckout Callback when a branch is selected for checkout
 * @param onCreateBranch Callback to show create branch dialog
 * @param onCommit Callback to show commit dialog
 * @param onPullInTerminal Callback to run git pull in terminal
 * @param onPushInTerminal Callback to run git push in terminal
 * @param onStash Callback to stash changes
 * @param onStashPop Callback to pop a stash
 * @param onRefresh Callback to refresh Git state
 */
@Composable
private fun getGitContextMenuItems(
    currentBranch: String?,
    localBranches: List<GitBranchInfo>,
    remoteBranches: List<GitBranchInfo>,
    stashList: List<GitStashInfo>,
    isLoading: Boolean,
    onCheckout: (String) -> Unit,
    onMerge: (String) -> Unit,
    onRebase: (String) -> Unit,
    onCreateBranch: () -> Unit,
    onCommit: () -> Unit,
    onPull: () -> Unit,
    onPush: () -> Unit,
    onCreatePR: (() -> Unit)?,
    onStash: () -> Unit,
    onStashPop: (Int) -> Unit,
    onRefresh: () -> Unit,
): List<ContextMenuItem> {
    // Helper to create branch action submenu
    fun createBranchActions(branch: GitBranchInfo): List<ContextMenuItem> =
        listOf(
            ContextMenuItem(
                text = "Checkout",
                icon = Icons.Outlined.Check,
                onClick = { onCheckout(branch.name) },
            ),
            ContextMenuItem(
                text = "Merge into current",
                icon = Icons.AutoMirrored.Outlined.MergeType,
                onClick = { onMerge(branch.name) },
            ),
            ContextMenuItem(
                text = "Rebase onto this",
                icon = Icons.Outlined.Replay,
                onClick = { onRebase(branch.name) },
            ),
        )

    return buildList {
        // Current branch header (info only)
        add(
            ContextMenuItem(
                text = "On branch: ${currentBranch ?: "unknown"}",
                icon = FeatherIcons.GitBranch,
                onClick = {}, // Info only, no action
            ),
        )

        add(ContextMenuItem(isDivider = true))

        // Commit
        add(
            ContextMenuItem(
                text = "Commit...",
                icon = Icons.Outlined.Check,
                onClick = onCommit,
            ),
        )

        add(ContextMenuItem(isDivider = true))

        // Pull and Push actions (run in terminal)
        add(
            ContextMenuItem(
                text = "Pull",
                icon = Icons.Outlined.ArrowDownward,
                onClick = onPull,
            ),
        )

        add(
            ContextMenuItem(
                text = "Push",
                icon = Icons.Outlined.ArrowUpward,
                onClick = onPush,
            ),
        )

        // Create PR (only if supported remote detected)
        if (onCreatePR != null) {
            add(
                ContextMenuItem(
                    text = "Create Pull Request...",
                    icon = Icons.AutoMirrored.Outlined.OpenInNew,
                    onClick = onCreatePR,
                ),
            )
        }

        add(ContextMenuItem(isDivider = true))

        // Stash submenu
        add(
            ContextMenuItem(
                text = "Stash",
                icon = Icons.Outlined.Archive,
                subMenu =
                    buildList {
                        add(
                            ContextMenuItem(
                                text = "Stash Changes",
                                icon = Icons.Outlined.Archive,
                                onClick = onStash,
                            ),
                        )
                        if (stashList.isNotEmpty()) {
                            add(ContextMenuItem(isDivider = true))
                            stashList.forEach { stash ->
                                add(
                                    ContextMenuItem(
                                        text = "stash@{${stash.index}}: ${stash.message.take(
                                            40,
                                        )}${if (stash.message.length > 40) "..." else ""}",
                                        onClick = { onStashPop(stash.index) },
                                    ),
                                )
                            }
                        }
                    },
            ),
        )

        add(ContextMenuItem(isDivider = true))

        if (isLoading) {
            // Show loading state
            add(
                ContextMenuItem(
                    text = "Loading branches...",
                    icon = Icons.Outlined.Refresh,
                    onClick = {}, // No action while loading
                ),
            )
        } else {
            // Local branches submenu - each branch has checkout/merge/rebase options
            add(
                ContextMenuItem(
                    text = "Local Branches${if (localBranches.isEmpty()) " (none)" else ""}",
                    icon = Icons.Outlined.AccountTree,
                    subMenu =
                        if (localBranches.isEmpty()) {
                            null
                        } else {
                            localBranches.map { branch ->
                                ContextMenuItem(
                                    text = branch.name,
                                    icon = if (branch.isCurrent) Icons.Outlined.Check else null,
                                    subMenu = if (branch.isCurrent) null else createBranchActions(branch),
                                )
                            }
                        },
                ),
            )

            // Remote branches submenu - each branch has checkout/merge/rebase options
            add(
                ContextMenuItem(
                    text = "Remote Branches${if (remoteBranches.isEmpty()) " (none)" else ""}",
                    icon = Icons.Outlined.Cloud,
                    subMenu =
                        if (remoteBranches.isEmpty()) {
                            null
                        } else {
                            remoteBranches.map { branch ->
                                ContextMenuItem(
                                    text = branch.name,
                                    subMenu = createBranchActions(branch),
                                )
                            }
                        },
                ),
            )
        }

        add(ContextMenuItem(isDivider = true))

        // Create new branch
        add(
            ContextMenuItem(
                text = "Create Branch...",
                icon = Icons.Filled.Add,
                onClick = onCreateBranch,
            ),
        )

        // Refresh
        add(
            ContextMenuItem(
                text = "Refresh",
                icon = Icons.Outlined.Refresh,
                onClick = onRefresh,
            ),
        )
    }
}

@Composable
fun BossDraggableComponent.BossTopLeftBar(
    workspaceManager: WorkspaceManager? = null,
    onApplyWorkspace: ((LayoutWorkspace) -> Unit)? = null,
    getCurrentWorkspace: (() -> LayoutWorkspace)? = null,
    onShowTopOfMind: (() -> Unit)? = null,
    onNewProject: (() -> Unit)? = null,
    onCloneProject: (() -> Unit)? = null,
) {
    // Get window ID for per-window terminal isolation (Issue #498)
    val windowId = LocalWindowId.current ?: return
    // Use per-window project state for independent project per window (required for multi-window support)
    val windowProjectState = LocalWindowProjectState.current
    val selectedProject by windowProjectState?.selectedProject?.collectAsState()
        ?: remember { mutableStateOf(Project("No Project", "", 0L)) }
    var showProjectDialog by remember { mutableStateOf(false) }
    var projectToOpen by remember { mutableStateOf<Project?>(null) }
    var deletedProjectName by remember { mutableStateOf<String?>(null) }

    // Window-specific git state - each window maintains independent git state
    // This fixes the issue where opening a new window with no project would hide git UI in all windows
    val windowGitState = LocalWindowGitState.current

    // Git state collection from window-specific state (Issue #90)
    // Use window-specific state for UI to prevent cross-window interference
    val isGitRepo by windowGitState?.isGitRepository?.collectAsState() ?: remember { mutableStateOf(false) }
    val currentBranch by windowGitState?.currentBranch?.collectAsState() ?: remember { mutableStateOf(null) }
    val localBranches by windowGitState?.localBranches?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
    val remoteBranches by windowGitState?.remoteBranches?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
    val stashList by windowGitState?.stashList?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
    val isGitLoading by windowGitState?.isLoading?.collectAsState() ?: remember { mutableStateOf(false) }
    // Git availability is global (system-level) so we still use GitService for this
    val isGitAvailable by GitService.isGitAvailable.collectAsState()
    var showCreateBranchDialog by remember { mutableStateOf(false) }
    var showCommitDialog by remember { mutableStateOf(false) }
    var gitErrorMessage by remember { mutableStateOf<String?>(null) }
    var gitSuccessMessage by remember { mutableStateOf<String?>(null) }
    var createPRUrl by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Refresh Git state when project changes - uses window-specific state
    // This ensures each window's git UI is independent
    LaunchedEffect(selectedProject, windowGitState) {
        if (selectedProject.path.isNotEmpty() && windowGitState != null) {
            // Refresh git for THIS window only
            GitService.refreshForWindow(selectedProject.path, windowGitState)
            // Also fetch the PR URL and stash list for this window
            createPRUrl = GitService.getCreatePRUrl()
            GitService.refreshStashListForWindow(windowGitState)
        } else {
            // Only clear THIS window's git state, not other windows
            windowGitState?.clear()
            createPRUrl = null
        }
    }

    // Watch .git/HEAD for external mutations (CLI / filesystem checkouts)
    // and refresh window git state on change. Cancelled automatically when
    // the project changes (LaunchedEffect re-keys) or the composition
    // leaves the tree.
    LaunchedEffect(selectedProject.path, windowGitState) {
        if (selectedProject.path.isNotEmpty() && windowGitState != null) {
            GitService.watchGitHeadForWindow(selectedProject.path, windowGitState)
        }
    }

    // Update PR URL when branch changes
    LaunchedEffect(currentBranch) {
        if (isGitRepo && currentBranch != null) {
            createPRUrl = GitService.getCreatePRUrl()
        }
    }

    // Helper function to open project in current window
    fun openProjectInCurrentWindow(project: Project) {
        // Use window-specific project state if available, otherwise fallback to global
        // Panels are opened automatically by BossApp's LaunchedEffect
        selectProjectInWindow(windowProjectState, project)
    }

    // Helper function to validate and handle project selection
    fun handleProjectSelection(project: Project) {
        val projectDir = File(project.path)
        if (!projectDir.exists() || !projectDir.isDirectory) {
            // Project folder was deleted - show message and remove from list
            deletedProjectName = project.name
            ProjectState.removeRecentProject(project.path)
        } else if (selectedProject.path.isNotEmpty()) {
            // Project exists and another project is already open - show dialog
            projectToOpen = project
        } else {
            // Project exists and no project selected - open directly
            openProjectInCurrentWindow(project)
        }
    }

    BossActionButtonWithLogo(
        text = if (selectedProject.path.isEmpty()) "Open Project" else selectedProject.name,
        contextMenuItems =
            getProjectSelectContextMenuItems(
                showProjectDialog = { showProjectDialog = true },
                showNewProjectDialog = { onNewProject?.invoke() },
                showCloneProjectDialog = { onCloneProject?.invoke() },
                onProjectSelected = { project -> handleProjectSelection(project) },
            ),
        hintText = if (selectedProject.path.isEmpty()) "Click to open a project" else "Current Project: ${selectedProject.path}",
    )

    // Deleted project dialog
    deletedProjectName?.let { projectName ->
        AlertDialog(
            onDismissRequest = { deletedProjectName = null },
            title = { Text("Project Not Found") },
            text = { Text("The project \"$projectName\" no longer exists. It has been removed from the recent projects list.") },
            confirmButton = {
                TextButton(onClick = { deletedProjectName = null }) {
                    Text("OK")
                }
            },
        )
    }
    // Git branch button (Issue #90)
    // Only show when git is available and project is a git repository
    if (isGitAvailable && isGitRepo) {
        BossActionButton(
            leftIcon = FeatherIcons.GitBranch,
            text = if (isGitLoading) "..." else (currentBranch ?: "detached"),
            maxTextWidth = 120.dp, // Truncate long branch names with ellipsis
            contextMenuItems =
                getGitContextMenuItems(
                    currentBranch = currentBranch,
                    localBranches = localBranches,
                    remoteBranches = remoteBranches,
                    stashList = stashList,
                    isLoading = isGitLoading,
                    onCheckout = { branchName ->
                        scope.launch {
                            val result = GitService.checkout(branchName, windowId = windowId)
                            when (result) {
                                is GitSuccess -> gitSuccessMessage = "Switched to '$branchName'"
                                is GitError -> gitErrorMessage = result.message
                            }
                        }
                    },
                    onMerge = { branchName ->
                        scope.launch { GitService.mergeInTerminal(windowId, branchName) }
                    },
                    onRebase = { branchName ->
                        scope.launch { GitService.rebaseInTerminal(windowId, branchName) }
                    },
                    onCreateBranch = { showCreateBranchDialog = true },
                    onCommit = { showCommitDialog = true },
                    onPull = {
                        scope.launch { GitService.pullInTerminal(windowId) }
                    },
                    onPush = {
                        scope.launch { GitService.pushInTerminal(windowId) }
                    },
                    onCreatePR =
                        createPRUrl?.let { url ->
                            {
                                // Show terminal link dialog for opening PR URL (window-scoped)
                                scope.launch {
                                    TerminalLinkEventBus.emitLinkClick(url, sourceTerminalId = null, sourceWindowId = windowId)
                                }
                            }
                        },
                    onStash = {
                        scope.launch {
                            val result = GitService.stash()
                            when (result) {
                                is GitSuccess -> gitSuccessMessage = result.message
                                is GitError -> gitErrorMessage = result.message
                            }
                        }
                    },
                    onStashPop = { index ->
                        scope.launch {
                            val result = GitService.stashPop(index)
                            when (result) {
                                is GitSuccess -> gitSuccessMessage = result.message
                                is GitError -> gitErrorMessage = result.message
                            }
                        }
                    },
                    onRefresh = {
                        scope.launch {
                            GitService.refreshForWindow(selectedProject.path, windowGitState)
                            createPRUrl = GitService.getCreatePRUrl()
                            GitService.refreshStashListForWindow(windowGitState)
                        }
                    },
                ),
            hintText = "Git Branch: ${currentBranch ?: "unknown"}",
        )
    }

    // Git error dialog
    gitErrorMessage?.let { errorMsg ->
        AlertDialog(
            onDismissRequest = { gitErrorMessage = null },
            title = { Text("Git Error") },
            text = { Text(errorMsg) },
            confirmButton = {
                TextButton(onClick = { gitErrorMessage = null }) {
                    Text("OK")
                }
            },
        )
    }

    // Git success dialog
    gitSuccessMessage?.let { successMsg ->
        AlertDialog(
            onDismissRequest = { gitSuccessMessage = null },
            title = { Text("Git Operation Successful") },
            text = { Text(successMsg) },
            confirmButton = {
                TextButton(onClick = { gitSuccessMessage = null }) {
                    Text("OK")
                }
            },
        )
    }

    // Create branch dialog
    if (showCreateBranchDialog) {
        CreateBranchDialog(
            onDismiss = { showCreateBranchDialog = false },
            onCreate = { branchName ->
                scope.launch {
                    val result = GitService.createBranch(branchName, checkout = true, windowId = windowId)
                    if (result is GitError) {
                        gitErrorMessage = result.message
                    }
                }
                showCreateBranchDialog = false
            },
        )
    }

    // Commit dialog
    if (showCommitDialog) {
        CommitDialog(
            onDismiss = { showCommitDialog = false },
            onCommitSuccess = { message ->
                gitSuccessMessage = "Committed: ${message.lines().first().take(50)}${if (message.length > 50) "..." else ""}"
            },
        )
    }

    // Workspace button
    if (workspaceManager != null && onApplyWorkspace != null) {
        WorkspaceButton(
            onOpenWorkspace = onApplyWorkspace,
            workspaceManager = workspaceManager,
            getCurrentWorkspace = getCurrentWorkspace,
            onShowTopOfMind = onShowTopOfMind,
        )
    }

    // Directory picker for native file selection
    val directoryPicker =
        rememberDirectoryPicker { path ->
            path?.let {
                val projectName = it.extractFileName().ifEmpty { "Unknown" }
                val project = Project(name = projectName, path = it)
                // Close the selection dialog
                showProjectDialog = false
                // Only show dialog if a project is already selected
                if (selectedProject.path.isNotEmpty()) {
                    projectToOpen = project
                } else {
                    // No project selected, open directly in current window
                    openProjectInCurrentWindow(project)
                }
            }
        }

    // Project selection dialog
    // Note: Dialog handles empty recentProjects case internally by opening directory picker directly
    if (showProjectDialog) {
        ProjectSelectionDialog(
            onDismiss = { showProjectDialog = false },
            onOpenDirectoryPicker = {
                showProjectDialog = false
                directoryPicker.pickDirectory()
            },
        )
    }

    // Project open mode dialog
    projectToOpen?.let { project ->
        ProjectOpenModeDialog(
            project = project,
            onDismiss = { projectToOpen = null },
            onOpenInCurrentWindow = { selectedProj ->
                openProjectInCurrentWindow(selectedProj)
                projectToOpen = null
            },
            onOpenInNewWindow = { selectedProj ->
                // Create new window with the project - each window has independent project state
                WindowOperations.createNewWindowWithProject(selectedProj)
                projectToOpen = null
            },
        )
    }
}

// TODO: #93 - Lanager functionality (currently disabled - plugin removed)
// See https://github.com/risa-labs-inc/BOSS-Kotlin/issues/93
// val lanagerContextMenuItems get() = listOf(
//     ContextMenuItem(
//         text = "Start Lanager",
//         icon = Icons.Outlined.PlayArrow,
//         onClick = { /* Handle start lanager action */ }
//     ),
//     ContextMenuItem(
//         text = "View Agents",
//         icon = Icons.Outlined.People,
//         onClick = { /* Handle view agents action */ }
//     ),
//     ContextMenuItem(isDivider = true),
//     ContextMenuItem(
//         text = "Configure Lanager",
//         icon = Icons.Outlined.Settings,
//         onClick = { /* Handle configure action */ }
//     ),
//     ContextMenuItem(isDivider = true),
//     ContextMenuItem(
//         text = "Restart Lanager",
//         icon = Icons.Outlined.Refresh,
//         onClick = { /* Handle restart action */ }
//     ),
//     ContextMenuItem(
//         text = "Stop Lanager",
//         icon = Icons.Outlined.Stop,
//         onClick = { /* Handle stop action */ }
//     )
// )

// TODO: #91 - BossTopRunBar function (currently disabled)
// See https://github.com/risa-labs-inc/BOSS-Kotlin/issues/91
// @Composable
// fun BossTopRunBar() {
//     BossActionButton(
//         leftIcon = Icons.Outlined.Diversity2,
//         text = "lanager [boss]",
//         contextMenuItems = lanagerContextMenuItems,
//         hintText = "Lanager: Manage AI agent swarm for collaborative tasks"
//     )
//
//     BossActionButton(
//         imageVector = Icons.Outlined.PlayArrow,
//         text = "Run",
//         hintText = "Run the current workspace"
//     ) {}
//
//     BossActionButton(
//         imageVector = Icons.Outlined.BugReport,
//         text = "Bug",
//         hintText = "Debug the current execution"
//     ) {}
//
//     BossActionButton(
//         imageVector = Icons.Outlined.Stop,
//         text = "Stop",
//         hintText = "Stop all running processes"
//     ) {}
//
//     BossActionButton(
//         imageVector = Icons.Outlined.MoreVert,
//         text = "More",
//         hintText = "Additional actions and settings"
//     ) {}
// }

@Composable
fun BossTopRightBar(
    onShowSettings: (() -> Unit)? = null,
    onShowSearch: (() -> Unit)? = null,
) {
    var showLogoutDialog by remember { mutableStateOf(false) }
    val currentUser by AuthService.currentUser.collectAsState()

    // Show user email if logged in
    currentUser?.let { user ->
        Text(
            text = user.email,
            style = androidx.compose.material.MaterialTheme.typography.caption,
            modifier = Modifier.padding(end = 8.dp),
            color = androidx.compose.ui.graphics.Color.Gray,
        )
    }

    BossActionButton(
        imageVector = Icons.AutoMirrored.Outlined.Logout,
        text = "Sign Out",
        hintText = "Sign out of your account",
    ) {
        showLogoutDialog = true
    }

    // Global search button (Issue #92)
    BossActionButton(
        imageVector = Icons.Outlined.Search,
        text = "Search",
        hintText = "Search files, tabs, bookmarks (⇧⇧)",
    ) {
        onShowSearch?.invoke()
    }

    BossActionButton(
        imageVector = Icons.Outlined.Settings,
        text = "Settings",
        hintText = "Configure application settings",
    ) {
        onShowSettings?.invoke()
    }

    // Logout confirmation dialog
    if (showLogoutDialog) {
        LogoutConfirmationDialog(
            onDismiss = { showLogoutDialog = false },
        )
    }
}

/**
 * Dialog for creating a new Git branch.
 * Issue #90: Git Integration for Top Bar
 *
 * @param onDismiss Called when dialog is dismissed
 * @param onCreate Called with branch name when user clicks Create
 */
@Composable
private fun CreateBranchDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var branchName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New Branch") },
        text = {
            Column {
                androidx.compose.material.OutlinedTextField(
                    value = branchName,
                    onValueChange = {
                        branchName = it
                        // Basic validation - no spaces or special chars except - and _
                        error =
                            if (it.contains(Regex("[\\s~^:?*\\[\\\\]"))) {
                                "Branch name contains invalid characters"
                            } else {
                                null
                            }
                    },
                    label = { Text("Branch name") },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(
                        text = it,
                        color = androidx.compose.material.MaterialTheme.colors.error,
                        style = androidx.compose.material.MaterialTheme.typography.caption,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (branchName.isNotBlank() && error == null) {
                        onCreate(branchName.trim())
                    }
                },
                enabled = branchName.isNotBlank() && error == null,
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
