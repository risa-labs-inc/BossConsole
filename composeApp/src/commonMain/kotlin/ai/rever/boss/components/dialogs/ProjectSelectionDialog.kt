package ai.rever.boss.components.dialogs

import BossDarkAccent
import BossDarkBackground
import BossDarkSurface
import BossDarkTextPrimary
import BossDarkTextSecondary
import ai.rever.boss.window.Project
import ai.rever.boss.components.plugin.panels.left_top.ProjectState
import ai.rever.boss.window.WindowProjectState
import ai.rever.boss.window.LocalWindowProjectState
import ai.rever.boss.window.selectProjectInWindow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Project selection dialog that shows recent projects and allows browsing for new ones.
 *
 * If no recent projects exist, this dialog automatically opens the directory picker
 * and dismisses itself, avoiding showing an empty dialog to the user.
 */
@Composable
fun ProjectSelectionDialog(
    onDismiss: () -> Unit,
    onOpenDirectoryPicker: () -> Unit = {}
) {
    // Get window project state from composition local for multi-window support
    val windowProjectState = LocalWindowProjectState.current
    val recentProjects by ProjectState.recentProjects.collectAsState()

    // If no recent projects, skip dialog and open directory picker directly
    if (recentProjects.isEmpty()) {
        LaunchedEffect(Unit) {
            onDismiss()
            onOpenDirectoryPicker()
        }
        return
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .width(500.dp)
                    .heightIn(min = 200.dp, max = 450.dp)
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            onDismiss()
                            true
                        } else {
                            false
                        }
                    },
                shape = RoundedCornerShape(8.dp),
                backgroundColor = BossDarkBackground,
                elevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    // Title
                    Text(
                        text = "Open Project",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = BossDarkTextPrimary,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // Recent projects list
                    Text(
                        text = "Recent Projects",
                        fontSize = 12.sp,
                        color = BossDarkTextSecondary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth()
                    ) {
                        items(recentProjects) { project ->
                            ProjectListItem(
                                project = project,
                                onClick = {
                                    selectProjectInWindow(windowProjectState, project)
                                    onDismiss()
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Browse button and Close button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { onOpenDirectoryPicker() },
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = BossDarkAccent,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Browse...")
                        }

                        TextButton(
                            onClick = onDismiss,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = BossDarkTextSecondary
                            )
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectListItem(
    project: Project,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        backgroundColor = BossDarkSurface,
        shape = RoundedCornerShape(4.dp),
        elevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Folder,
                contentDescription = "Project",
                tint = BossDarkAccent,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = project.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = BossDarkTextPrimary
                )
                Text(
                    text = project.path,
                    fontSize = 12.sp,
                    color = BossDarkTextSecondary
                )
            }
        }
    }
}
