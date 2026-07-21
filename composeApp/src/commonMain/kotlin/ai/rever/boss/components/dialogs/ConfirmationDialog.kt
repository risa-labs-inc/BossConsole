package ai.rever.boss.components.dialogs

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Generic confirmation dialog for destructive or important actions
 *
 * @param title Dialog title
 * @param message Confirmation message
 * @param icon Optional icon to display
 * @param iconTint Color tint for the icon
 * @param confirmText Text for confirm button (default: "Confirm")
 * @param confirmColor Color for confirm button (default: red for destructive)
 * @param onDismiss Callback when dialog is dismissed
 * @param onConfirm Callback when user confirms action
 */
@Composable
fun ConfirmationDialog(
    title: String,
    message: String,
    icon: ImageVector? = null,
    iconTint: Color = BossTheme.colors.warn,
    confirmText: String = "Confirm",
    confirmColor: Color = BossTheme.colors.alert, // destructive
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val colors = BossTheme.colors
    val radii = BossTheme.radius
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
            shape = RoundedCornerShape(radii.dialog),
            color = colors.panel
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                // Icon and title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = title,
                            tint = iconTint,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    Text(
                        text = title,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Message
                Text(
                    text = message,
                    fontSize = 14.sp,
                    color = colors.textSecondary,
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
                            contentColor = colors.textSecondary
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
                            backgroundColor = confirmColor,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(radii.button)
                    ) {
                        Text(confirmText, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
