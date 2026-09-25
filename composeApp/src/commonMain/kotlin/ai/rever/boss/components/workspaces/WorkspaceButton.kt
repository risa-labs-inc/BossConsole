package ai.rever.boss.components.workspaces

import ai.rever.boss.components.bars.horizontal.StatusMessageManager
import ai.rever.boss.components.buttons.BossActionButton
import ai.rever.boss.components.icons.SpaceIcon
import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.components.window_panel.SplitViewStateRegistry
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.LocalWindowId
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Palette
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

/**
 * Platform-specific function to open workspace directory
 */
expect fun openWorkspaceDirectory(path: String)

private val workspaceButtonLogger = BossLogger.forComponent("WorkspaceButton")

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
    /**
     * Whether the Space on screen has changes that are not on disk.
     *
     * Marks the GLYPH, not the label. The label is capped at 130dp with an ellipsis in the
     * vertical bar, so a marker appended to the text is the first thing a long Space name
     * truncates away - the mark would be missing exactly on the names most likely to be a
     * project's. `signalText` rather than `signal`, because this is a glyph and `signal` is the
     * fill token, held to no text contrast floor.
     *
     * Off by default, so the top bar's copy of this button is untouched: the save affordance that
     * answers the mark lives in the vertical bar's footer, and a mark with nothing beside it says
     * there is a problem without saying what to do about it.
     */
    unsaved: Boolean = false,
    /**
     * Every Space THIS WINDOW holds unsaved changes to, for the menu rows.
     *
     * A set rather than the [unsaved] boolean because the menu marks every row, not only the one on
     * screen: a window runs several Spaces at once and can have edited more than one of them. Per
     * window for the reason `WorkspaceManager.unsavedWorkspaces` is - the live layout only exists
     * in a window's own `SplitViewState`, so one flat set would mark a Space here because it was
     * edited over there.
     *
     * Read through `spaceIsUnsaved` rather than by containment, so the menu and the bar answer with
     * one rule - which is what puts a mark on Last Session, a slot that is never a document.
     */
    unsavedWorkspaceIds: Set<String> = emptySet(),
) {
    val windowId = LocalWindowId.current
    val saveOwner = windowId?.let(SplitViewStateRegistry::getState)
    val currentWorkspace by workspaceManager.currentWorkspace.collectAsState()
    val workspaces by workspaceManager.workspaces.collectAsState()

    // A BOSS theme belongs to a Space, so this menu is where one is given: both are collected
    // rather than read, because the submenu's tick has to move the moment the theme does - the
    // menu can be open while the app re-skins under it.
    val spaceThemes by workspaceManager.spaceThemes.collectAsState()
    val settingsThemeId by SettingsThemeBaseline.themeId.collectAsState()

    // Every workspace running anywhere - in this window behind the one on screen, or in another
    // window. The menu could previously mark exactly one, so everything else looked equally idle
    // whether it was running or not.
    val windowWorkspaces by workspaceManager.windowWorkspaces.collectAsState()
    val running =
        remember(windowWorkspaces) { windowWorkspaces.values.flatten().toSet() }

    var showSaveDialog by remember { mutableStateOf(false) }
    var showOpenDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val namedSaveLatch = remember(windowId, saveOwner) { SaveInFlightLatch() }

    // Build options submenu items
    val optionsSubMenu =
        buildList {
            // Save workspace
            add(
                ContextMenuItem(
                    text = "Save Space...",
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

            // Delete workspace section. By ID, not by name - see `deletableWorkspaces`, which is
            // also what the dialog below filters with, so the entry and the list cannot disagree.
            val deletable = deletableWorkspaces(workspaces)

            if (deletable.isNotEmpty()) {
                add(
                    ContextMenuItem(
                        text = "Delete Space...",
                        icon = Icons.Outlined.Delete,
                        onClick = { showDeleteDialog = true },
                    ),
                )
            }

            add(ContextMenuItem(isDivider = true))

            // Open workspace directory
            add(
                ContextMenuItem(
                    text = "Open Space Folder",
                    icon = Icons.Outlined.FolderOpen,
                    onClick = {
                        openWorkspaceDirectory(workspaceManager.getWorkspaceDirectory())
                    },
                ),
            )

            // The Space on screen wears a theme of its own, and this is where it is given one.
            // Above the divider, with the things you do TO this Space, rather than beside Open
            // Space Folder and Reset to Default - those two are about the app's idea of Spaces.
            val themeItems =
                spaceThemeMenuItems(
                    workspaceId = currentWorkspace?.id.orEmpty(),
                    overrides = spaceThemes,
                    settingsThemeId = settingsThemeId,
                    onChoose = { themeId ->
                        currentWorkspace?.id?.let { workspaceManager.setSpaceTheme(it, themeId) }
                    },
                )
            if (themeItems.isNotEmpty()) {
                add(
                    ContextMenuItem(
                        text = "Space Theme",
                        icon = Icons.Outlined.Palette,
                        subMenu = themeItems,
                    ),
                )
            }

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
                // Two independent facts per row - where it is running, and whether it holds
                // work that is not on disk. See [SpaceRowMarks] for why they are not one mark.
                val marks =
                    spaceRowMarks(
                        workspaceId = workspace.id,
                        currentWorkspaceId = currentWorkspace?.id,
                        runningWorkspaceIds = running,
                        unsavedWorkspaceIds = unsavedWorkspaceIds,
                    )

                add(
                    ContextMenuItem(
                        text = workspace.name,
                        icon = null,
                        trailingIcon = marks.run.dotIcon(),
                        trailingIconColor =
                            when (marks.run) {
                                SpaceRunState.Current -> BossTheme.colors.ok
                                SpaceRunState.Running -> BossTheme.colors.textSecondary
                                SpaceRunState.Idle -> null
                            },
                        // The unsaved mark, in the second trailing slot so it sits beside the
                        // running dot rather than replacing it. Same glyph and same `signalText`
                        // as the vertical bar's dot, because it is the same fact: a reader should
                        // not have to learn a second vocabulary between the bar and this menu.
                        secondaryTrailingIcon = Icons.Filled.Circle.takeIf { marks.unsaved },
                        secondaryTrailingIconColor = BossTheme.colors.signalText,
                        secondaryTrailingDescription = SPACE_UNSAVED_ROW_DESCRIPTION,
                        // `onOpenWorkspace` is `WorkspaceSwitch.request`, which does the whole
                        // job - it materialises a template, loads what that produced and applies
                        // it. Loading the picked row HERE was redundant and did two kinds of harm.
                        //
                        // A BOSS theme belongs to a Space, so entering the row was entering a
                        // Space: picking a TEMPLATE applied its theme, and the materialised copy
                        // that actually opened a moment later applied its own. That flash is gone
                        // with this line rather than made to land on the same colour, because the
                        // intermediate entry was doing everything else twice as well.
                        //
                        // It also lied to the switch. `request` reads `currentWorkspace` as the
                        // Space being LEFT, so pre-setting it to the one being entered made
                        // `leaving.id == workspace.id` and skipped the keep-or-close question
                        // outright.
                        onClick = { onOpenWorkspace(workspace) },
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
                // The same glyph the Top of Mind footer opens a Space with
                // (`SpaceIcon`), not a briefcase. A briefcase says "work", which is the half of
                // the old word that got dropped when Workspace became Space, where a big pane
                // beside two stacked ones is what a Space actually IS - on a card tipped back a
                // few degrees, so the glyph says "panes, with some depth to them" rather than
                // "a tile". And this button and that footer button do the same
                // job, raising the same picker, so wearing different icons made one control read
                // as two.
                leftIcon = SpaceIcon,
                compact = compact,
                iconColor = if (unsaved) BossTheme.colors.signalText else null,
                text =
                    currentWorkspace?.let { workspace ->
                        if (workspace.name != "Current") workspace.name else "Default"
                    } ?: "Default",
                contextMenuItems = contextMenuItems,
                primaryAction = onOpenWorkspacePicker,
                hintText =
                    buildString {
                        append("Layout Space: ${currentWorkspace?.description ?: "Default layout"}")
                        // Only where the left click has been taken. Told to right-click a button
                        // whose left click already opens the menu, a user right-clicks and gets
                        // nothing.
                        if (onOpenWorkspacePicker != null) append("\nRight-click for space options")
                        // Said in the hint as well as drawn, because a colour is not a sentence.
                        if (unsaved) append("\nUnsaved changes - use the save button beside this one")
                        append("\nSpaces saved to: ${workspaceManager.getWorkspaceDirectory()}")
                    },
            )
        }
    }

    // Save dialog
    if (showSaveDialog) {
        SaveWorkspaceDialog(
            onDismiss = { showSaveDialog = false },
            onSave = { name ->
                // A named save creates a new Space. Ignore an overlapping submission instead
                // of replaying it, because replaying the same name would create another Space.
                if (namedSaveLatch.press()) {
                    getCurrentWorkspace?.invoke()?.let { currentLayout ->
                        workspaceManager.updateCurrentWorkspace(currentLayout)
                        namedSaveLatch.begin()
                        workspaceManager.saveCurrentWorkspace(
                            name = name,
                            onSaved = { savedWorkspace ->
                                try {
                                    // Both a real window id and its originally registered state are
                                    // required. In particular, null === null must never authorize a rebind.
                                    if (
                                        windowId != null &&
                                        saveOwner != null &&
                                        SplitViewStateRegistry.getState(windowId) === saveOwner
                                    ) {
                                        saveOwner.rebindCurrentWorkspace(savedWorkspace.id)
                                    } else {
                                        workspaceButtonLogger.debug(
                                            LogCategory.WORKSPACE,
                                            "Named save finished after its window deregistered;" +
                                                " the rebind is dropped",
                                            mapOf("workspaceId" to savedWorkspace.id),
                                        )
                                    }
                                } finally {
                                    // Do not replay an overlapping named save: it would mint a duplicate.
                                    namedSaveLatch.settle {}
                                }
                            },
                            onFailed = { failedName ->
                                try {
                                    StatusMessageManager.showMessage("Could not save \"$failedName\"")
                                } finally {
                                    namedSaveLatch.settle {}
                                }
                            },
                        )
                    }
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
                    // Let the apply/switch callback claim the Space only after it proves that
                    // the imported layout can build at least one declared tab. Claiming it here
                    // would leave the manager pointing at an unapplied Space on refusal.
                    onOpenWorkspace(workspace)
                }
                showOpenDialog = false
            },
        )
    }

    // Delete dialog
    if (showDeleteDialog) {
        DeleteWorkspaceDialog(
            // The same id-keyed filter as the menu entry that opens this.
            workspaces = deletableWorkspaces(workspaces),
            onDismiss = { showDeleteDialog = false },
            onDelete = { workspaceId ->
                // By ID all the way through, so picking one of two same-named rows deletes the
                // one that was ticked.
                workspaceManager.deleteWorkspaceById(workspaceId)
                showDeleteDialog = false
            },
        )
    }
}
