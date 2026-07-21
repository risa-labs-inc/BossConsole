package ai.rever.boss.components.dialogs

import BossDarkAccent
import BossDarkBackground
import BossDarkTextPrimary
import BossDarkTextSecondary
import ai.rever.boss.window.Project
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Tab
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
 * Dialog to ask user whether to open a project in current window or new window
 *
 * @param project The project to open
 * @param onDismiss Callback when dialog is dismissed
 * @param onOpenInCurrentWindow Callback to open in current window
 * @param onOpenInNewWindow Callback to open in new window
 */
@Composable
fun ProjectOpenModeDialog(
    project: Project,
    onDismiss: () -> Unit,
    onOpenInCurrentWindow: (Project) -> Unit,
    onOpenInNewWindow: (Project) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = true,
            dismissOnBackPress = true
        )
    ) {
        Surface(
            modifier = Modifier
                .width(460.dp)
                .wrapContentHeight(),
            shape = RoundedCornerShape(8.dp),
            color = BossDarkBackground
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                // Icon and title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FolderOpen,
                        contentDescription = "Open Project",
                        tint = BossDarkAccent,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Open Project",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = BossDarkTextPrimary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Project name
                Text(
                    text = project.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = BossDarkTextPrimary
                )
                Text(
                    text = project.path,
                    fontSize = 12.sp,
                    color = BossDarkTextSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Question
                Text(
                    text = "Where would you like to open this project?",
                    fontSize = 14.sp,
                    color = BossDarkTextSecondary
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Current Window button
                    OutlinedButton(
                        onClick = {
                            onOpenInCurrentWindow(project)
                            onDismiss()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = BossDarkTextPrimary
                        ),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Tab,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Current Window", fontWeight = FontWeight.Medium, maxLines = 1)
                    }

                    // New Window button
                    Button(
                        onClick = {
                            onOpenInNewWindow(project)
                            onDismiss()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = BossDarkAccent,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("New Window", fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                }
            }
        }
    }
}
