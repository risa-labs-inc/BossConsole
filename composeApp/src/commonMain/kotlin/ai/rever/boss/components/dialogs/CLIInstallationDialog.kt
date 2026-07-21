package ai.rever.boss.components.dialogs

import BossDarkAccent
import BossDarkBackground
import BossDarkError
import BossDarkSuccess
import BossDarkSurface
import BossDarkTextPrimary
import BossDarkTextSecondary
import ai.rever.boss.utils.CLIInstallResult
import ai.rever.boss.utils.CLIInstaller
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

/**
 * Dialog for installing BOSS CLI scripts
 *
 * Shows installation progress, success, or error states
 */
@Composable
fun CLIInstallationDialog(
    onDismiss: () -> Unit
) {
    var installState by remember { mutableStateOf<InstallState>(InstallState.Installing) }
    val scope = rememberCoroutineScope()

    // Trigger installation on first composition
    LaunchedEffect(Unit) {
        scope.launch {
            val result = CLIInstaller.installCLI()
            installState = if (result.success) {
                InstallState.Success(result)
            } else {
                InstallState.Error(result.message)
            }
        }
    }

    Dialog(onDismissRequest = {
        // Only allow dismiss if not installing
        if (installState !is InstallState.Installing) {
            onDismiss()
        }
    }) {
        Card(
            modifier = Modifier
                .width(500.dp)
                .padding(16.dp),
            elevation = 8.dp,
            backgroundColor = BossDarkSurface
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (val state = installState) {
                    is InstallState.Installing -> {
                        InstallingContent()
                    }
                    is InstallState.Success -> {
                        SuccessContent(
                            result = state.result,
                            onClose = onDismiss
                        )
                    }
                    is InstallState.Error -> {
                        ErrorContent(
                            message = state.message,
                            onRetry = {
                                installState = InstallState.Installing
                                scope.launch {
                                    val result = CLIInstaller.installCLI()
                                    installState = if (result.success) {
                                        InstallState.Success(result)
                                    } else {
                                        InstallState.Error(result.message)
                                    }
                                }
                            },
                            onClose = onDismiss
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InstallingContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(64.dp),
            color = BossDarkAccent
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Installing BOSS CLI",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = BossDarkTextPrimary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Please wait...",
            fontSize = 14.sp,
            color = BossDarkTextSecondary
        )
    }
}

@Composable
private fun SuccessContent(
    result: CLIInstallResult,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = "Success",
            modifier = Modifier.size(64.dp),
            tint = BossDarkSuccess
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "CLI Installed Successfully",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = BossDarkTextPrimary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = BossDarkBackground,
            elevation = 0.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = result.message,
                    fontSize = 13.sp,
                    color = BossDarkTextSecondary,
                    lineHeight = 20.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onClose,
            colors = ButtonDefaults.buttonColors(
                backgroundColor = BossDarkAccent,
                contentColor = BossDarkTextPrimary
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("OK")
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = "Error",
            modifier = Modifier.size(64.dp),
            tint = BossDarkError
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Installation Failed",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = BossDarkTextPrimary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = BossDarkBackground,
            elevation = 0.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = message,
                    fontSize = 13.sp,
                    color = BossDarkTextSecondary,
                    lineHeight = 20.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onClose,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = BossDarkTextPrimary
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("Cancel")
            }

            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = BossDarkAccent,
                    contentColor = BossDarkTextPrimary
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text("Retry")
            }
        }
    }
}

private sealed class InstallState {
    object Installing : InstallState()
    data class Success(val result: CLIInstallResult) : InstallState()
    data class Error(val message: String) : InstallState()
}
