package ai.rever.boss.components.workspaces

import ai.rever.boss.components.dialogs.dialogScrollFence
import ai.rever.boss.plugin.ui.BossAlertDialog
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.RadioButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** How tall the workspace list is allowed to grow before it scrolls. */
private val LIST_MAX_HEIGHT = 260.dp

/** Keeps the dialog from collapsing to the width of the longest workspace name. */
private val DIALOG_MIN_WIDTH = 360.dp

/**
 * Asks which workspace to open for a project the user just selected.
 *
 * Raised when the default workspace setting is [WorkspaceSettings.ASK_WORKSPACE_ID], which
 * is what a fresh install now has: BOSS applies no layout on its own, so this is where a
 * layout gets chosen. Dismissing is a real answer - the window keeps whatever is open -
 * so the dialog offers "Not now" rather than only a way out.
 *
 * [workspaces] comes from the same [WorkspaceManager] the top bar's workspace button and
 * the app menu read, so a workspace saved from the top bar is offered here too. It is not
 * [PredefinedWorkspaces.allWorkspaces]: that list is the built-ins only, and picking from
 * a different set than the rest of the app shows is the divergence this dialog was added
 * alongside fixing.
 */
@Composable
fun SelectWorkspaceDialog(
    projectName: String,
    workspaces: List<LayoutWorkspace>,
    onDismiss: () -> Unit,
    onSelect: (LayoutWorkspace) -> Unit,
) {
    var selectedId by remember(workspaces) { mutableStateOf(workspaces.firstOrNull()?.id) }

    BossAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open a space") },
        text = {
            Column(modifier = Modifier.widthIn(min = DIALOG_MIN_WIDTH)) {
                Text(
                    text = "$projectName is open. Choose a layout to start with, or keep this window as it is.",
                    color = BossTheme.colors.textSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                if (workspaces.isEmpty()) {
                    Text(
                        text = "No spaces are available yet.",
                        color = BossTheme.colors.textSecondary,
                        fontSize = 12.sp,
                    )
                    return@Column
                }

                // dialogScrollFence outside verticalScroll: without it the desktop
                // AlertDialog grows by the scrolled amount (see its kdoc).
                Column(
                    modifier =
                        Modifier
                            .dialogScrollFence(LIST_MAX_HEIGHT)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    workspaces.forEach { workspace ->
                        WorkspaceRow(
                            workspace = workspace,
                            selected = selectedId == workspace.id,
                            onClick = { selectedId = workspace.id },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    workspaces.firstOrNull { it.id == selectedId }?.let(onSelect)
                },
                enabled = selectedId != null,
            ) {
                Text("Open", color = BossTheme.colors.signalText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not now", color = BossTheme.colors.textMuted)
            }
        },
    )
}

@Composable
private fun WorkspaceRow(
    workspace: LayoutWorkspace,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                text = workspace.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = BossTheme.colors.textPrimary,
            )
            if (workspace.description.isNotBlank()) {
                Text(
                    text = workspace.description,
                    fontSize = 11.sp,
                    color = BossTheme.colors.textSecondary,
                )
            }
        }
    }
}
