package ai.rever.boss.app

import ai.rever.boss.components.buttons.BossActionButton
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.WorkspaceButton
import ai.rever.boss.components.workspaces.WorkspaceManager
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.window.Project
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Divider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Folder

/**
 * The project and workspace pickers, at the foot of the vertical tab bar.
 *
 * These live in the top bar. With the top bar switched off there is nowhere else for them, and
 * "which project am I in" stops being answerable from the window at all - so the vertical bar,
 * the one piece of window chrome still on screen in that configuration, takes them.
 *
 * Drawn ONLY when the top bar is off. Both at once would be the same two controls twice, and the
 * top bar is where they belong when it is there.
 *
 * They sit above the split map rather than below it. The map is the bar's last row by design -
 * it is a picture of the window, and a picture of the window belongs at the bottom of the thing
 * that lists what is in it.
 */
@Composable
internal fun VerticalBarWindowControls(
    topBarHidden: Boolean,
    project: Project,
    onOpenProject: () -> Unit,
    workspaceManager: WorkspaceManager,
    onApplyWorkspace: (LayoutWorkspace) -> Unit,
    getCurrentWorkspace: () -> LayoutWorkspace,
    onShowTopOfMind: () -> Unit,
) {
    if (!topBarHidden) return

    Divider(color = BossTheme.colors.line)
    Column(
        // Tight on purpose. These are two rows of a narrow bar, not a toolbar: the padding that
        // reads as breathing room across a 1500dp top bar reads as dead space down a 200dp one,
        // and there is a split map below them competing for the same inches.
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        BossActionButton(
            // A folder rather than the top bar's project LOGO tile. That tile is 28dp of solid
            // colour built to anchor a wide bar; down a 200dp column it is the loudest thing on
            // screen and it is decoration.
            leftIcon = FeatherIcons.Folder,
            // A project with no path is no project: the button then offers the action rather than
            // naming the empty one, which is what the top bar's copy does too.
            text = if (project.path.isEmpty()) "Open Project" else project.name,
            // The top bar's copy hangs a recent-projects menu off this button. Here it opens the
            // project dialog instead - the same one the File menu and the dashboard open - rather
            // than standing up a second recent-projects menu with its own remove and rename
            // dialogs behind it. One control, one window-level dialog.
            //
            // Null, not emptyList: a non-null list makes the button open a menu on click, and an
            // empty one would open an empty menu on top of the dialog.
            contextMenuItems = null,
            hintText = if (project.path.isEmpty()) "Open a project" else project.path,
            maxTextWidth = LABEL_MAX_WIDTH,
            compact = true,
            onClick = onOpenProject,
        )
        WorkspaceButton(
            onOpenWorkspace = onApplyWorkspace,
            workspaceManager = workspaceManager,
            getCurrentWorkspace = getCurrentWorkspace,
            onShowTopOfMind = onShowTopOfMind,
            compact = true,
        )
    }
}

/**
 * How wide a project or workspace name may get before it truncates.
 *
 * The bar is 200dp and these rows carry an icon and a chevron either side of the label, so
 * without a cap a long project name pushes the chevron off the end of its own button.
 */
private val LABEL_MAX_WIDTH = 130.dp
