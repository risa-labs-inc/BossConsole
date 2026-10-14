package ai.rever.boss.components.workspaces

import ai.rever.boss.platform.rememberFilePicker
import ai.rever.boss.plugin.ui.BossAlertDialog
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Save workspace dialog
 */
@Composable
fun SaveWorkspaceDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }

    BossAlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material.Text("Save Space") },
        text = {
            androidx.compose.material.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { androidx.compose.material.Text("Space Name") },
                singleLine = true,
            )
        },
        confirmButton = {
            androidx.compose.material.TextButton(
                onClick = { onSave(name) },
                enabled = name.isNotBlank(),
            ) {
                androidx.compose.material.Text("Save")
            }
        },
        dismissButton = {
            androidx.compose.material.TextButton(onClick = onDismiss) {
                androidx.compose.material.Text("Cancel")
            }
        },
    )
}

/**
 * Open workspace dialog with file picker
 */
@Composable
fun OpenWorkspaceDialog(
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val filePicker =
        rememberFilePicker(
            onFileSelected = { _, content, _ ->
                if (content != null) {
                    onOpen(content)
                }
                onDismiss()
            },
            fileExtensions = listOf("json"),
        )

    // Immediately trigger file picker
    LaunchedEffect(Unit) {
        filePicker.pickFile()
    }
}

/**
 * Delete workspace dialog.
 *
 * **Selection is by ID, and [onDelete] reports an id.** It selected by NAME, so two Spaces sharing
 * a name ticked together and the delete resolved to whichever the list found first - a way to
 * destroy the wrong Space by pointing at the right one. Names are identity to a reader and are not
 * unique; ids are.
 */
@Composable
fun DeleteWorkspaceDialog(
    workspaces: List<LayoutWorkspace>,
    onDismiss: () -> Unit,
    onDelete: (workspaceId: String) -> Unit,
) {
    var selectedWorkspace by remember { mutableStateOf<String?>(null) }

    BossAlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material.Text("Delete Space") },
        text = {
            Column {
                androidx.compose.material.Text(
                    "Select a space to delete:",
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                workspaces.forEach { workspace ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { selectedWorkspace = workspace.id }
                                .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedWorkspace == workspace.id,
                            onClick = { selectedWorkspace = workspace.id },
                        )
                        androidx.compose.material.Text(
                            text = workspace.name,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }

                if (workspaces.isEmpty()) {
                    androidx.compose.material.Text(
                        "No custom spaces to delete.",
                        color = BossTheme.colors.textSecondary,
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material.TextButton(
                onClick = {
                    selectedWorkspace?.let { onDelete(it) }
                },
                enabled = selectedWorkspace != null,
            ) {
                androidx.compose.material.Text("Delete", color = BossTheme.colors.alert)
            }
        },
        dismissButton = {
            androidx.compose.material.TextButton(onClick = onDismiss) {
                androidx.compose.material.Text("Cancel")
            }
        },
    )
}
