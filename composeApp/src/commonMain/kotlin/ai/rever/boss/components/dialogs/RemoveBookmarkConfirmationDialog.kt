package ai.rever.boss.components.dialogs

import BossDarkAccent
import BossDarkBackground
import BossDarkBorder
import BossDarkError
import BossDarkTextPrimary
import BossDarkTextSecondary
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun RemoveBookmarkConfirmationDialog(
    bookmarkTitle: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
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
                .width(400.dp)
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
                        imageVector = Icons.Outlined.Star,
                        contentDescription = "Remove Bookmark",
                        tint = BossDarkAccent,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Remove Bookmark?",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = BossDarkTextPrimary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Message
                Text(
                    text = "'$bookmarkTitle' will be removed from your bookmarks.",
                    fontSize = 14.sp,
                    color = BossDarkTextSecondary,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))

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
                            onConfirm()
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = BossDarkError, // Red color for destructive action
                            contentColor = BossDarkTextPrimary
                        ),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Remove", fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
