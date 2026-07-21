package ai.rever.boss.components.workspaces

import BossDarkError
import BossDarkTextSecondary
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.material.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.rever.boss.platform.rememberFilePicker

/**
 * Save workspace dialog
 */
@Composable
fun SaveWorkspaceDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }

    androidx.compose.material.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material.Text("Save Workspace") },
        text = {
            androidx.compose.material.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { androidx.compose.material.Text("Workspace Name") },
                singleLine = true
            )
        },
        confirmButton = {
            androidx.compose.material.TextButton(
                onClick = { onSave(name) },
                enabled = name.isNotBlank()
            ) {
                androidx.compose.material.Text("Save")
            }
        },
        dismissButton = {
            androidx.compose.material.TextButton(onClick = onDismiss) {
                androidx.compose.material.Text("Cancel")
            }
        }
    )
}

/**
 * Open workspace dialog with file picker
 */
@Composable
fun OpenWorkspaceDialog(
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit
) {
    val filePicker = rememberFilePicker(
        onFileSelected = { path, content ->
            if (content != null) {
                onOpen(content)
            }
            onDismiss()
        },
        fileExtensions = listOf("json")
    )

    // Immediately trigger file picker
    LaunchedEffect(Unit) {
        filePicker.pickFile()
    }
}

/**
 * Delete workspace dialog
 */
@Composable
fun DeleteWorkspaceDialog(
    workspaces: List<LayoutWorkspace>,
    onDismiss: () -> Unit,
    onDelete: (String) -> Unit
) {
    var selectedWorkspace by remember { mutableStateOf<String?>(null) }

    androidx.compose.material.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material.Text("Delete Workspace") },
        text = {
            Column {
                androidx.compose.material.Text(
                    "Select a workspace to delete:",
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                workspaces.forEach { workspace ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedWorkspace = workspace.name }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedWorkspace == workspace.name,
                            onClick = { selectedWorkspace = workspace.name }
                        )
                        androidx.compose.material.Text(
                            text = workspace.name,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }

                if (workspaces.isEmpty()) {
                    androidx.compose.material.Text(
                        "No custom workspaces to delete.",
                        color = BossDarkTextSecondary
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material.TextButton(
                onClick = {
                    selectedWorkspace?.let { onDelete(it) }
                },
                enabled = selectedWorkspace != null
            ) {
                androidx.compose.material.Text("Delete", color = BossDarkError)
            }
        },
        dismissButton = {
            androidx.compose.material.TextButton(onClick = onDismiss) {
                androidx.compose.material.Text("Cancel")
            }
        }
    )
}
