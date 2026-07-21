package ai.rever.boss.components.dialogs

import BossDarkAccent
import BossDarkBackground
import BossDarkBorder
import BossDarkTextPrimary
import BossDarkTextSecondary
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
 * Dialog for renaming collections or workspaces
 *
 * @param title Dialog title (e.g., "Rename Collection", "Rename Workspace")
 * @param currentName Current name to pre-fill
 * @param label Input field label (e.g., "Collection Name", "Workspace Name")
 * @param onDismiss Callback when dialog is dismissed
 * @param onRename Callback with new name
 */
@Composable
fun RenameDialog(
    title: String,
    currentName: String,
    label: String,
    onDismiss: () -> Unit,
    onRename: (newName: String) -> Unit
) {
    var newName by remember { mutableStateOf(currentName) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = true,
            dismissOnBackPress = true
        )
    ) {
        Surface(
            modifier = Modifier.width(400.dp),
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

                Spacer(modifier = Modifier.height(16.dp))

                // Name input
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(label, color = BossDarkTextSecondary) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        textColor = BossDarkTextPrimary,
                        cursorColor = BossDarkAccent,
                        focusedBorderColor = BossDarkAccent,
                        unfocusedBorderColor = BossDarkBorder
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(20.dp))

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
                            val trimmed = newName.trim()
                            if (trimmed.isNotEmpty() && trimmed != currentName) {
                                onRename(trimmed)
                            }
                            onDismiss()
                        },
                        enabled = newName.trim().isNotEmpty() && newName.trim() != currentName,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = BossDarkAccent,
                            contentColor = Color.Black,
                            disabledBackgroundColor = BossDarkBorder,
                            disabledContentColor = BossDarkTextSecondary
                        ),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Rename", fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
