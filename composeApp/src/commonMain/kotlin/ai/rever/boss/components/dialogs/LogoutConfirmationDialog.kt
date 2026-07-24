package ai.rever.boss.components.dialogs

import ai.rever.boss.services.supabase.AuthService
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

@Composable
fun LogoutConfirmationDialog(onDismiss: () -> Unit) {
    var isLoading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val currentUser by AuthService.currentUser.collectAsState()

    Dialog(onDismissRequest = { if (!isLoading) onDismiss() }) {
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            elevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.AutoMirrored.Default.Logout,
                    contentDescription = "Logout",
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colors.primary,
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Confirm Logout",
                    style = MaterialTheme.typography.h6,
                    fontWeight = FontWeight.Bold,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Are you sure you want to sign out?",
                    style = MaterialTheme.typography.body2,
                )

                currentUser?.let { user ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = user.email,
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isLoading,
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
                        colors =
                            ButtonDefaults.buttonColors(
                                backgroundColor = MaterialTheme.colors.error,
                            ),
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = MaterialTheme.colors.onError,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Sign Out", color = MaterialTheme.colors.onError)
                        }
                    }
                }
            }
        }
    }
}
