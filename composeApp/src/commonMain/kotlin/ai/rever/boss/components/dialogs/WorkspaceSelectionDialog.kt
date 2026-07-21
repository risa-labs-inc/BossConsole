package ai.rever.boss.components.dialogs

import BossDarkAccent
import BossDarkBackground
import BossDarkBorder
import BossDarkSurface
import BossDarkTextPrimary
import BossDarkTextSecondary
import ai.rever.boss.components.workspaces.extractPanels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Dialog for selecting workspaces and panels where a bookmark should open
 *
 * @param title Dialog title
 * @param workspaces List of available workspaces
 * @param preselectedWorkspaces Map of workspace name -> panel ID (pre-selected workspaces)
 * @param onDismiss Callback when dialog is dismissed
 * @param onConfirm Callback with workspace-panel map
 */
@Composable
fun WorkspaceSelectionDialog(
    title: String,
    workspaces: List<ai.rever.boss.components.workspaces.LayoutWorkspace>,
    preselectedWorkspaces: Map<String, String?> = emptyMap(),
    onDismiss: () -> Unit,
    onConfirm: (workspacePanelMap: Map<String, String?>) -> Unit
) {
    // Map of workspace name -> panel ID (null = auto/active panel)
    var workspacePanelSelections by remember { mutableStateOf(preselectedWorkspaces) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = true,
            dismissOnBackPress = true
        )
    ) {
        Surface(
            modifier = Modifier
                .width(600.dp)
                .heightIn(max = 600.dp),
            shape = RoundedCornerShape(8.dp),
            color = BossDarkBackground
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                // Title
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = BossDarkTextPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Subtitle
                Text(
                    text = "Select workspaces and panels where this bookmark should open",
                    fontSize = 13.sp,
                    color = BossDarkTextSecondary
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Workspaces list
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (workspaces.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No workspaces available",
                                    fontSize = 13.sp,
                                    color = BossDarkTextSecondary,
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                )
                            }
                        }
                    } else {
                        items(workspaces) { workspace ->
                            WorkspaceSelectionItem(
                                workspace = workspace,
                                isSelected = workspacePanelSelections.containsKey(workspace.name),
                                selectedPanelId = workspacePanelSelections[workspace.name],
                                onToggle = {
                                    workspacePanelSelections = if (workspacePanelSelections.containsKey(workspace.name)) {
                                        workspacePanelSelections - workspace.name
                                    } else {
                                        workspacePanelSelections + (workspace.name to null)
                                    }
                                },
                                onPanelSelected = { panelId ->
                                    workspacePanelSelections = workspacePanelSelections + (workspace.name to panelId)
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Helper text
                Text(
                    text = "Leave empty to open bookmark in current workspace",
                    fontSize = 11.sp,
                    color = BossDarkTextSecondary
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = BossDarkTextSecondary
                        )
                    ) {
                        Text("Cancel")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            onConfirm(workspacePanelSelections)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = BossDarkAccent,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Update", fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/**
 * Individual workspace selection item with panel dropdown
 */
@Composable
private fun WorkspaceSelectionItem(
    workspace: ai.rever.boss.components.workspaces.LayoutWorkspace,
    isSelected: Boolean,
    selectedPanelId: String?,
    onToggle: () -> Unit,
    onPanelSelected: (String?) -> Unit
) {
    var showPanelDropdown by remember { mutableStateOf(false) }
    val panels = workspace.layout.extractPanels()

    // Get panel display name
    val panelDisplayName = if (selectedPanelId == null) {
        "Auto"
    } else {
        panels.find { it.first == selectedPanelId }?.second ?: "Auto"
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle),
            shape = RoundedCornerShape(8.dp),
            color = if (isSelected) BossDarkSurface else Color.Transparent,
            border = if (isSelected) {
                androidx.compose.foundation.BorderStroke(1.dp, BossDarkAccent)
            } else {
                androidx.compose.foundation.BorderStroke(1.dp, BossDarkBorder)
            }
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Selection indicator
                Icon(
                    imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = if (isSelected) "Selected" else "Not selected",
                    tint = if (isSelected) BossDarkAccent else BossDarkTextSecondary,
                    modifier = Modifier.size(20.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Workspace icon
                Icon(
                    imageVector = Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = BossDarkTextSecondary,
                    modifier = Modifier.size(18.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Workspace name and panel info
                Text(
                    text = if (isSelected && selectedPanelId != null) {
                        "${workspace.name}: $panelDisplayName"
                    } else {
                        workspace.name
                    },
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    color = BossDarkTextPrimary,
                    modifier = Modifier.weight(1f)
                )

                // Panel selector dropdown (only when selected)
                if (isSelected) {
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = { showPanelDropdown = !showPanelDropdown },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ArrowDropDown,
                            contentDescription = "Select Panel",
                            tint = BossDarkTextSecondary
                        )
                    }
                }
            }
        }

        // Panel dropdown (shown when selected and dropdown is open)
        if (isSelected && showPanelDropdown) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp, top = 4.dp),
                shape = RoundedCornerShape(8.dp),
                color = BossDarkSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, BossDarkBorder)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    // Auto option
                    PanelOption(
                        displayName = "Auto (active panel)",
                        isSelected = selectedPanelId == null,
                        onClick = {
                            onPanelSelected(null)
                            showPanelDropdown = false
                        }
                    )

                    // Panel options
                    panels.forEach { (panelId, panelLabel) ->
                        PanelOption(
                            displayName = panelLabel,
                            isSelected = selectedPanelId == panelId,
                            onClick = {
                                onPanelSelected(panelId)
                                showPanelDropdown = false
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Individual panel option in dropdown
 */
@Composable
private fun PanelOption(
    displayName: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                color = if (isSelected) BossDarkSurface.copy(alpha = 0.6f) else Color.Transparent,
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = displayName,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
            color = if (isSelected) BossDarkAccent else BossDarkTextPrimary
        )
    }
}
