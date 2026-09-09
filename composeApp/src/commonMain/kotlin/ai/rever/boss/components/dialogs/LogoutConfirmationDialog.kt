package ai.rever.boss.components.dialogs

import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.services.supabase.AuthService
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Width of the logout card.
 *
 * The house confirmation width, matching [ConfirmationDialog] and `BossAlertDialog`'s `ALERT_WIDTH`.
 * It has to be a FIXED width rather than `fillMaxWidth()`: `BossDialog`'s contract is that its
 * content is an intrinsically-sized card, and on the heavyweight path the card is measured inside a
 * `fillMaxSize()` scrim spanning the whole window, so a filling card became a band across the entire
 * screen. The lightweight path hid that - Compose's dialog measure policy caps content at the
 * platform default width - which is why it shipped.
 */
private val LogoutCardWidth = 400.dp

@Composable
fun LogoutConfirmationDialog(onDismiss: () -> Unit) {
    var isLoading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val currentUser by AuthService.currentUser.collectAsState()
    val colors = BossTheme.colors
    val space = BossTheme.space

    BossDialog(onDismissRequest = { if (!isLoading) onDismiss() }) {
        Surface(
            modifier =
                Modifier
                    .width(LogoutCardWidth)
                    .wrapContentHeight(),
            shape = BossTheme.radius.dialogShape,
            color = colors.panel,
            elevation = BossTheme.elevation.popover,
        ) {
            Column(
                modifier = Modifier.padding(space.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.AutoMirrored.Default.Logout,
                    contentDescription = "Logout",
                    modifier = Modifier.size(48.dp),
                    tint = colors.signal,
                )

                Spacer(modifier = Modifier.height(space.lg))

                Text(
                    text = "Confirm Logout",
                    style = MaterialTheme.typography.h6,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )

                Spacer(modifier = Modifier.height(space.sm))

                Text(
                    text = "Are you sure you want to sign out?",
                    style = MaterialTheme.typography.body2,
                    color = colors.textSecondary,
                )

                currentUser?.let { user ->
                    Spacer(modifier = Modifier.height(space.xs))
                    Text(
                        text = user.email,
                        style = MaterialTheme.typography.caption,
                        color = colors.textMuted,
                    )
                }

                Spacer(modifier = Modifier.height(space.xl))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(space.sm, Alignment.End),
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isLoading,
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = colors.textSecondary,
                            ),
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isLoading = true
                                AuthService.signOut()
                                isLoading = false
                                onDismiss()
                            }
                        },
                        enabled = !isLoading,
                        shape = BossTheme.radius.buttonShape,
                        colors =
                            ButtonDefaults.buttonColors(
                                backgroundColor = colors.alert,
                                // White, not textPrimary: this is a destructive fill, and every other
                                // one in the app is white-on-red (ConfirmationDialog). textPrimary is
                                // near-black in the Daylight scheme, so it would put dark text on a red
                                // button in that theme only.
                                contentColor = Color.White,
                            ),
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Sign Out", fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}
