package ai.rever.boss.components.dialogs

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Dialog for creating a new bookmark collection
 */
@Composable
fun NewCollectionDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String) -> Unit,
) {
    var collectionName by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                dismissOnClickOutside = true,
                dismissOnBackPress = true,
            ),
    ) {
        Surface(
            modifier = Modifier.width(400.dp),
            shape = RoundedCornerShape(8.dp),
            color = BossTheme.colors.panel,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
            ) {
                // Title
                Text(
                    text = "New Collection",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = BossTheme.colors.textPrimary,
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Name input
                OutlinedTextField(
                    value = collectionName,
                    onValueChange = { collectionName = it },
                    label = { Text("Collection Name", color = BossTheme.colors.textSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        TextFieldDefaults.outlinedTextFieldColors(
                            textColor = BossTheme.colors.textPrimary,
                            cursorColor = BossTheme.colors.signal,
                            focusedBorderColor = BossTheme.colors.signal,
                            unfocusedBorderColor = BossTheme.colors.line,
                        ),
                    singleLine = true,
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = BossTheme.colors.textSecondary,
                            ),
                    ) {
                        Text("Cancel")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = { onCreate(collectionName.trim()) },
                        enabled = collectionName.trim().isNotEmpty(),
                        colors =
                            ButtonDefaults.buttonColors(
                                backgroundColor = BossTheme.colors.signal,
                                contentColor = Color.Black,
                                disabledBackgroundColor = BossTheme.colors.line,
                                disabledContentColor = BossTheme.colors.textSecondary,
                            ),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text("Create", fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/**
 * Dialog for creating a new workspace
 */
@Composable
fun NewWorkspaceDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String) -> Unit,
) {
    var workspaceName by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                dismissOnClickOutside = true,
                dismissOnBackPress = true,
            ),
    ) {
        Surface(
            modifier = Modifier.width(400.dp),
            shape = RoundedCornerShape(8.dp),
            color = BossTheme.colors.panel,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
            ) {
                // Title
                Text(
                    text = "New Workspace",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = BossTheme.colors.textPrimary,
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Name input
                OutlinedTextField(
                    value = workspaceName,
                    onValueChange = { workspaceName = it },
                    label = { Text("Workspace Name", color = BossTheme.colors.textSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        TextFieldDefaults.outlinedTextFieldColors(
                            textColor = BossTheme.colors.textPrimary,
                            cursorColor = BossTheme.colors.signal,
                            focusedBorderColor = BossTheme.colors.signal,
                            unfocusedBorderColor = BossTheme.colors.line,
                        ),
                    singleLine = true,
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = BossTheme.colors.textSecondary,
                            ),
                    ) {
                        Text("Cancel")
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = { onCreate(workspaceName.trim()) },
                        enabled = workspaceName.trim().isNotEmpty(),
                        colors =
                            ButtonDefaults.buttonColors(
                                backgroundColor = BossTheme.colors.signal,
                                contentColor = Color.Black,
                                disabledBackgroundColor = BossTheme.colors.line,
                                disabledContentColor = BossTheme.colors.textSecondary,
                            ),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text("Create", fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
