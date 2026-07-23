package ai.rever.boss.components.auth.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.rever.boss.components.auth.forms.*
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.viewmodels.LoginViewModel
import ai.rever.boss.viewmodels.auth.AuthOptions
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LoginFormScreen(
    viewModel: LoginViewModel,
    onLoginSuccess: () -> Unit,
    isLoading: Boolean,
    errorMessage: String?,
    onMagicLinkSent: (String) -> Unit = {},
    onPasskeyAuthInitiated: (String) -> Unit = {},
    onPasskeySelectionRequired: (String) -> Unit = {}
) {
    var email by remember { mutableStateOf("") }
    var authOptions by remember { mutableStateOf<AuthOptions?>(null) }
    var showAuthOptions by remember { mutableStateOf(false) }

    // Collect loading states from ViewModels
    val checkingUserExists by viewModel.authOptionsManager.isLoading.collectAsState()
    val passkeyAuthLoading by viewModel.passkeyAuthViewModel.isLoading.collectAsState()

    // Collect available credentials from AuthOptionsManager
    val availableCredentials by viewModel.authOptionsManager.availableCredentials.collectAsState()

    LocalSoftwareKeyboardController.current
    
    // Reset auth options when email changes
    LaunchedEffect(email) {
        if (showAuthOptions && email.isNotBlank() && email.contains("@")) {
            // Email changed, reset auth options to force re-check
            authOptions = null
            showAuthOptions = false
        }
    }
    
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .heightIn(min = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            BossLogo()
        
        Spacer(modifier = Modifier.height(24.dp))
        
        AuthCard {
            AuthCardTitle("Sign In")
            
            // Email Field
            EmailField(
                value = email,
                onValueChange = { 
                    email = it
                    // Reset to email step when email changes
                    if (showAuthOptions) {
                        showAuthOptions = false
                        authOptions = null
                    }
                },
                enabled = !isLoading && !checkingUserExists,
                keyboardActions = KeyboardActions(
                    onGo = {
                        if (email.isNotBlank() && email.contains("@") && !showAuthOptions) {
                            viewModel.checkUserExists(email) { options ->
                                authOptions = options
                                showAuthOptions = true
                            }
                        }
                    }
                )
            )
            
            // Error Message
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                ErrorMessage(errorMessage)
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            // Continue Button (when email not yet validated)
            // Show button when email is valid AND auth options not yet shown
            // Keep button visible during loading to show spinner feedback
            val shouldShowContinueButton = !showAuthOptions &&
                email.isNotBlank() && email.contains("@")

            if (shouldShowContinueButton) {
                PrimaryActionButton(
                    text = "Continue",
                    onClick = {
                        if (email.isNotBlank() && email.contains("@")) {
                            viewModel.checkUserExists(email) { options ->
                                authOptions = options
                                showAuthOptions = true
                            }
                        }
                    },
                    enabled = !isLoading && !checkingUserExists && email.isNotBlank() && email.contains("@"),
                    isLoading = checkingUserExists
                )
            }
            
            // Authentication Options (after email validation)
            if (showAuthOptions) {
                // Auto-send magic link when it's the only authentication option
                // This removes the redundant "Send Magic Link" button click for new users
                LaunchedEffect(authOptions) {
                    if (authOptions is AuthOptions.MagicLinkOnly && !isLoading) {
                        // Automatically send magic link to skip redundant button click
                        viewModel.sendMagicLink(email) {
                            // Magic link sent successfully - navigate to waiting screen
                            onMagicLinkSent(email)
                        }
                    }
                }

                when (val options = authOptions) {
                    null -> {
                        // Loading state - show checking indicator
                        LoadingIndicator()
                    }
                    is AuthOptions.Invalid -> {
                        // Show error message
                        Text(
                            text = options.message,
                            color = BossTheme.colors.textPrimary,
                            fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    is AuthOptions.WithPasskey -> {
                        // Set available passkeys from already-fetched credentials
                        LaunchedEffect(availableCredentials) {
                            viewModel.passkeyAuthViewModel.setAvailablePasskeys(availableCredentials)
                        }

                        // User has passkeys - check if multiple or single
                        Button(
                            onClick = {
                                // Navigate based on passkey count from ViewModel
                                val passkeyCount = viewModel.passkeyAuthViewModel.availablePasskeys.value.size

                                if (passkeyCount > 1) {
                                    // Multiple passkeys - show selection screen
                                    onPasskeySelectionRequired(email)
                                } else {
                                    // Single passkey - direct authentication
                                    onPasskeyAuthInitiated(email)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            enabled = !isLoading && !passkeyAuthLoading,
                            shape = RoundedCornerShape(4.dp),
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = BossTheme.colors.signal,
                                contentColor = BossTheme.colors.onSignal
                            )
                        ) {
                            if (passkeyAuthLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = BossTheme.colors.textPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Fingerprint,
                                    contentDescription = "Passkey",
                                    tint = BossTheme.colors.onSignal,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Sign in with passkey",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        OutlinedButton(
                            onClick = { 
                                // Show magic link authentication as alternative
                                authOptions = AuthOptions.MagicLinkOnly(email)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            enabled = !isLoading,
                            shape = RoundedCornerShape(4.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = BossTheme.colors.textPrimary
                            ),
                            border = BorderStroke(1.dp, BossTheme.colors.line)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Password",
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Send magic link",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    
                    is AuthOptions.MagicLinkOnly -> {
                        // User exists but no passkeys - show magic link authentication only
                        Text(
                            "We'll send you a secure magic link to sign in - no password needed!",
                            color = BossTheme.colors.textSecondary,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Send Magic Link Button
                        PrimaryActionButton(
                            text = "Send Magic Link",
                            onClick = {
                                viewModel.sendMagicLink(email) {
                                    // Magic link sent successfully - navigate to waiting screen
                                    onMagicLinkSent(email)
                                }
                            },
                            enabled = !isLoading,
                            isLoading = isLoading
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
        }
    }
}
