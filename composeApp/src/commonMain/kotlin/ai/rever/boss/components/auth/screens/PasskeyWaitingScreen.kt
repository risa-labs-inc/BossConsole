package ai.rever.boss.components.auth.screens

import ai.rever.boss.components.auth.forms.*
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.viewmodels.LoginViewModel
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Screen displayed during passkey authentication, providing waiting instructions,
 * progress feedback, and error handling.
 *
 * This provides a consistent UX with MagicLinkWaitingScreen - both authentication
 * methods now have dedicated progress/instruction screens.
 *
 * Note: This screen collects passkey errors directly from PasskeyAuthViewModel
 * to ensure proper error isolation from magic link authentication flow.
 */
@Composable
fun PasskeyWaitingScreen(
    email: String,
    viewModel: LoginViewModel,
    onBack: () -> Unit,
    onSuccess: () -> Unit,
) {
    // Collect loading state and errors from PasskeyAuthViewModel (not magic link errors)
    val isLoading by viewModel.passkeyAuthViewModel.isLoading.collectAsState()
    val passkeyError by viewModel.passkeyAuthViewModel.errorMessage.collectAsState()

    // Determine current state for UI display
    val authenticationState =
        when {
            passkeyError != null -> AuthenticationState.ERROR
            isLoading -> AuthenticationState.AUTHENTICATING
            else -> AuthenticationState.READY
        }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .heightIn(min = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BossLogo()

            Spacer(modifier = Modifier.height(24.dp))

            AuthCard {
                AuthCardTitle("Passkey Authentication")

                // Email confirmation
                Text(
                    text = "Authenticating as:",
                    fontSize = 14.sp,
                    color = BossTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = email,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = BossTheme.colors.signal,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Status Card with icon and instructions
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    backgroundColor = BossTheme.colors.panel,
                    elevation = 0.dp,
                    border =
                        BorderStroke(
                            1.dp,
                            when (authenticationState) {
                                AuthenticationState.ERROR -> BossTheme.colors.alert
                                AuthenticationState.AUTHENTICATING -> BossTheme.colors.signal
                                else -> BossTheme.colors.line
                            },
                        ),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Status Icon
                        when (authenticationState) {
                            AuthenticationState.AUTHENTICATING -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(48.dp),
                                    color = BossTheme.colors.signal,
                                    strokeWidth = 4.dp,
                                )
                            }

                            AuthenticationState.ERROR -> {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = "Error",
                                    tint = BossTheme.colors.alert,
                                    modifier = Modifier.size(48.dp),
                                )
                            }

                            AuthenticationState.READY -> {
                                Icon(
                                    imageVector = Icons.Default.Fingerprint,
                                    contentDescription = "Passkey",
                                    tint = BossTheme.colors.signal,
                                    modifier = Modifier.size(48.dp),
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Status Text
                        Text(
                            text =
                                when (authenticationState) {
                                    AuthenticationState.AUTHENTICATING -> {
                                        "Complete the biometric authentication"
                                    }

                                    AuthenticationState.ERROR -> {
                                        "Authentication Failed"
                                    }

                                    AuthenticationState.READY -> {
                                        "Ready to authenticate"
                                    }
                                },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color =
                                when (authenticationState) {
                                    AuthenticationState.ERROR -> BossTheme.colors.alert
                                    else -> BossTheme.colors.textPrimary
                                },
                            textAlign = TextAlign.Center,
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Instructions
                        Text(
                            text =
                                when (authenticationState) {
                                    AuthenticationState.AUTHENTICATING -> {
                                        "Use Touch ID, Face ID, Windows Hello, or your device's biometric authentication to sign in."
                                    }

                                    AuthenticationState.ERROR -> {
                                        passkeyError ?: "Authentication failed. Please try again."
                                    }

                                    AuthenticationState.READY -> {
                                        "Click \"Try Again\" to initiate biometric authentication."
                                    }
                                },
                            fontSize = 13.sp,
                            color = BossTheme.colors.textSecondary,
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action Buttons
                if (authenticationState == AuthenticationState.ERROR) {
                    // Try Again button on error
                    Button(
                        onClick = {
                            viewModel.authenticateWithEmailAndPasskey(email) {
                                onSuccess()
                            }
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                        shape = RoundedCornerShape(4.dp),
                        colors =
                            ButtonDefaults.buttonColors(
                                backgroundColor = BossTheme.colors.signal,
                                contentColor = Color.White,
                            ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = "Retry",
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Try Again",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Back button (always enabled to allow canceling authentication)
                TextButton(
                    onClick = onBack,
                ) {
                    Text(
                        text = "Back to Sign In",
                        fontSize = 14.sp,
                        color = BossTheme.colors.signal,
                        textDecoration = TextDecoration.Underline,
                    )
                }

                // Additional help text
                if (authenticationState == AuthenticationState.ERROR) {
                    Spacer(modifier = Modifier.height(16.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(6.dp),
                        backgroundColor = BossTheme.colors.panel,
                        elevation = 0.dp,
                        border = BorderStroke(1.dp, BossTheme.colors.line),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                        ) {
                            Text(
                                text = "Troubleshooting:",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = BossTheme.colors.textPrimary,
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text =
                                    "• Ensure your device's biometric authentication is enabled\n" +
                                        "• Check that you have a passkey registered for this account\n" +
                                        "• Try using a magic link instead if the issue persists",
                                fontSize = 11.sp,
                                color = BossTheme.colors.textSecondary,
                                lineHeight = 16.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Internal state for passkey authentication progress
 */
private enum class AuthenticationState {
    READY, // Initial state, ready to start
    AUTHENTICATING, // Currently authenticating with biometrics
    ERROR, // Authentication failed
}
