package ai.rever.boss.components.workspaces

import ai.rever.boss.components.buttons.BossActionButton
import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import compose.icons.FeatherIcons
import compose.icons.feathericons.Briefcase

/**
 * Platform-specific function to open workspace directory
 */
expect fun openWorkspaceDirectory(path: String)

/**
 * Workspace button with dropdown menu.
 *
 * [workspaceManager] is required, with no fallback instance. It used to default to
 * `remember { WorkspaceManager() }`, which is a silent second source of truth: a private
 * manager's `currentWorkspace` never sees a switch made anywhere else, so the green dot
 * marking the active workspace would point at nothing. The one caller has always passed
 * the shared manager, so the default was only a trap waiting for a second one.
 */
@Composable
fun WorkspaceButton(
    onOpenWorkspace: (LayoutWorkspace) -> Unit,
    workspaceManager: WorkspaceManager,
    getCurrentWorkspace: (() -> LayoutWorkspace)? = null,
    onShowTopOfMind: (() -> Unit)? = null,
    /**
     * What a LEFT click does, when there is something better for it to do than drop this menu.
     *
     * The vertical bar's copy passes `openTopOfMindWorkspacePicker`, which opens the Top of Mind
     * panel and asks it for its workspace picker - a searchable list, where this menu is an
     * unfiltered one. It returns false when Top of Mind is not there to ask, and the click then
     * falls through to the menu, which is also what the top bar's copy does with every click
     * because it passes null.
     *
     * The menu is NOT removed either way. Its Options submenu is the only route to Open Workspace
     * Folder and Reset to Default in the whole app - both need `WorkspaceManager` members that are
     * not on the plugin api, so nothing else can offer them - so when the primary click is taken,
     * the menu moves to the right click rather than going away.
     */
    onOpenWorkspacePicker: (() -> Boolean)? = null,
    /** Sized for the vertical tab bar rather than the top bar. See BossActionButton. */
    compact: Boolean = false,
) {
    val currentWorkspace by workspaceManager.currentWorkspace.collectAsState()
    val workspaces by workspaceManager.workspaces.collectAsState()

    // Every workspace running anywhere - in this window behind the one on screen, or in another
    // window. The menu could previously mark exactly one, so everything else looked equally idle
    // whether it was running or not.
    val windowWorkspaces by workspaceManager.windowWorkspaces.collectAsState()
    val running =
        remember(windowWorkspaces) { windowWorkspaces.values.flatten().toSet() }

    var showSaveDialog by remember { mutableStateOf(false) }
    var showOpenDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Build options submenu items
    val optionsSubMenu =
        buildList {
            // Save workspace
            add(
                ContextMenuItem(
                    text = "Save Workspace...",
                    icon = Icons.Outlined.Save,
                    onClick = { showSaveDialog = true },
                ),
            )

            // Open from file
            add(
                ContextMenuItem(
                    text = "Open from File...",
                    icon = Icons.Outlined.Upload,
                    onClick = { showOpenDialog = true },
                ),
            )

            // Delete workspace section
            val deletableWorkspaces =
                workspaces.filter { workspace ->
                    !PredefinedWorkspaces.allWorkspaces.any { it.name == workspace.name }
                }

            if (deletableWorkspaces.isNotEmpty()) {
                add(
                    ContextMenuItem(
                        text = "Delete Workspace...",
                        icon = Icons.Outlined.Delete,
                        onClick = { showDeleteDialog = true },
                    ),
                )
            }

            add(ContextMenuItem(isDivider = true))

            // Open workspace directory
            add(
                ContextMenuItem(
                    text = "Open Workspace Folder",
                    icon = Icons.Outlined.FolderOpen,
                    onClick = {
                        openWorkspaceDirectory(workspaceManager.getWorkspaceDirectory())
                    },
                ),
            )

            // Top of mind option
            if (onShowTopOfMind != null) {
                add(
                    ContextMenuItem(
                        text = "Show Top of Mind",
                        icon = Icons.Outlined.Tab,
                        onClick = onShowTopOfMind,
                    ),
                )
            }

            add(ContextMenuItem(isDivider = true))

            // Reset to default
            add(
                ContextMenuItem(
                    text = "Reset to Default",
                    icon = Icons.Outlined.RestartAlt,
                    onClick = {
                        workspaceManager.resetToDefault()
                        onOpenWorkspace(
                            LayoutWorkspace(
                                name = "Default",
                                description = "Default layout",
                                layout =
                                    SinglePanel(
                                        PanelConfig(
                                            id = "main",
                                            tabs = emptyList(),
                                        ),
                                    ),
                            ),
                        )
                    },
                ),
            )
        }

    // Build context menu items
    val contextMenuItems =
        buildList {
            // Workspaces at the top
            workspaces.forEach { workspace ->
                // Three states, not two: the workspace on screen here, one that is running
                // somewhere - behind this one, or in another window - and one that is not running
                // at all. The middle state had no mark, so a workspace whose tabs were live
                // looked exactly like one that had never been opened.
                val isCurrentWorkspace = currentWorkspace?.id == workspace.id
                val isRunning = !isCurrentWorkspace && workspace.id in running

                add(
                    ContextMenuItem(
                        text = workspace.name,
                        icon = null,
                        // Filled for this window, outlined for another's: the same mark at two
                        // strengths says "running" once and "yours" only on the one that is.
                        trailingIcon =
                            when {
                                isCurrentWorkspace -> Icons.Filled.Circle
                                isRunning -> Icons.Outlined.Circle
                                else -> null
                            },
                        trailingIconColor =
                            when {
                                isCurrentWorkspace -> BossTheme.colors.ok
                                isRunning -> BossTheme.colors.textSecondary
                                else -> null
                            },
                        onClick = {
                            workspaceManager.loadWorkspace(workspace)
                            onOpenWorkspace(workspace)
                        },
                    ),
                )
            }

            add(ContextMenuItem(isDivider = true))

            // Options submenu
            add(
                ContextMenuItem(
                    text = "Options",
                    icon = Icons.Outlined.Settings,
                    subMenu = optionsSubMenu,
                ),
            )
        }

    Box {
        Box {
            BossActionButton(
                leftIcon = FeatherIcons.Briefcase,
                compact = compact,
                text =
                    currentWorkspace?.let { workspace ->
                        if (workspace.name != "Current") workspace.name else "Default"
                    } ?: "Default",
                contextMenuItems = contextMenuItems,
                primaryAction = onOpenWorkspacePicker,
                hintText =
                    buildString {
                        append("Layout Workspace: ${currentWorkspace?.description ?: "Default layout"}")
                        // Only where the left click has been taken. Told to right-click a button
                        // whose left click already opens the menu, a user right-clicks and gets
                        // nothing.
                        if (onOpenWorkspacePicker != null) append("\nRight-click for workspace options")
                        append("\nWorkspaces saved to: ${workspaceManager.getWorkspaceDirectory()}")
                    },
            )
        }
    }

    // Save dialog
    if (showSaveDialog) {
        SaveWorkspaceDialog(
            onDismiss = { showSaveDialog = false },
            onSave = { name ->
                // Get current layout and save it with the provided name
                getCurrentWorkspace?.invoke()?.let { currentLayout ->
                    workspaceManager.updateCurrentWorkspace(currentLayout)
                    workspaceManager.saveCurrentWorkspace(name)
                }
                showSaveDialog = false
            },
        )
    }

    // Open dialog
    if (showOpenDialog) {
        OpenWorkspaceDialog(
            onDismiss = { showOpenDialog = false },
            onOpen = { jsonString ->
                workspaceManager.importWorkspace(jsonString)?.let { workspace ->
                    workspaceManager.loadWorkspace(workspace)
                    onOpenWorkspace(workspace)
                }
                showOpenDialog = false
            },
        )
    }

    // Delete dialog
    if (showDeleteDialog) {
        DeleteWorkspaceDialog(
            workspaces =
                workspaces.filter { workspace ->
                    !PredefinedWorkspaces.allWorkspaces.any { it.name == workspace.name }
                },
            onDismiss = { showDeleteDialog = false },
            onDelete = { workspaceName ->
                workspaceManager.deleteWorkspace(workspaceName)
                showDeleteDialog = false
            },
        )
    }
}
