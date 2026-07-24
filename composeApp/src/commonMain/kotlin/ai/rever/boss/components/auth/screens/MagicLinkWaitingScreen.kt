package ai.rever.boss.components.auth.screens

import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.rever.boss.services.supabase.AuthService
import ai.rever.boss.components.auth.forms.*
import ai.rever.boss.viewmodels.LoginViewModel
import kotlinx.coroutines.delay

private val logger = BossLogger.forComponent("MagicLinkWaitingScreen")

/**
 * Screen displayed after magic link has been sent, providing waiting instructions,
 * resend functionality, and manual link input for debugging purposes.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MagicLinkWaitingScreen(
    email: String,
    viewModel: LoginViewModel,
    onBack: () -> Unit,
    onSuccess: () -> Unit,
    isLoading: Boolean = false,
    errorMessage: String? = null
) {
    var magicLinkInput by remember { mutableStateOf("") }
    var showManualInput by remember { mutableStateOf(false) }
    var resendCooldown by remember { mutableStateOf(0) }
    var resendAttempts by remember { mutableStateOf(0) }
    var showSuccessMessage by remember { mutableStateOf(false) }
    
    val keyboardController = LocalSoftwareKeyboardController.current
    
    // Countdown timer for resend rate limiting
    LaunchedEffect(resendCooldown) {
        if (resendCooldown > 0) {
            delay(1000)
            resendCooldown--
        }
    }
    
    // Show success message temporarily after resend
    LaunchedEffect(showSuccessMessage) {
        if (showSuccessMessage) {
            delay(3000)
            showSuccessMessage = false
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
            AuthCardTitle("Check Your Email")
            
            // Main instruction
            Text(
                text = "We've sent a magic link to:",
                fontSize = 14.sp,
                color = BossTheme.colors.textSecondary,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Email address confirmation
            Text(
                text = email,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = BossTheme.colors.signal,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Instructions
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(6.dp),
                backgroundColor = BossTheme.colors.panel,
                elevation = 0.dp,
                border = BorderStroke(1.dp, BossTheme.colors.line)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Email,
                        contentDescription = "Email",
                        tint = BossTheme.colors.signal,
                        modifier = Modifier.size(24.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "Click the link in your email to sign in automatically",
                        fontSize = 13.sp,
                        color = BossTheme.colors.textSecondary,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(20.dp))
            
            // Success message for resend
            if (showSuccessMessage) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Success",
                        tint = BossTheme.colors.ok,
                        modifier = Modifier.size(16.dp)
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Text(
                        text = "Magic link sent successfully!",
                        fontSize = 13.sp,
                        color = BossTheme.colors.ok
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            // Only show actual error messages, not success messages (which are handled by UI state)
            if (errorMessage != null && !errorMessage.contains("sent") && !errorMessage.contains("Check your email")) {
                ErrorMessage(errorMessage)
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            // Resend button with cooldown
            OutlinedButton(
                onClick = {
                    if (resendCooldown == 0) {
                        // Calculate cooldown based on attempts (exponential backoff)
                        val cooldownTime = when (resendAttempts) {
                            0 -> 60
                            1 -> 120
                            2 -> 300
                            else -> 600
                        }
                        
                        resendAttempts++
                        resendCooldown = cooldownTime
                        
                        // Check if user is already authenticated - if so, don't send magic link
                        val authState = AuthService.authState.value
                        if (authState is AuthService.AuthState.Authenticated) {
                            logger.debug(LogCategory.AUTH, "User already authenticated, skipping magic link resend")
                            showSuccessMessage = true // Show success message without actually sending
                            return@OutlinedButton
                        }
                        
                        viewModel.sendMagicLink(email) {
                            showSuccessMessage = true
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                enabled = !isLoading && resendCooldown == 0,
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (resendCooldown == 0) BossTheme.colors.textPrimary
                    else BossTheme.colors.textSecondary
                ),
                border = BorderStroke(1.dp, BossTheme.colors.line)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = BossTheme.colors.textSecondary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Resend",
                        modifier = Modifier.size(20.dp),
                        tint = if (resendCooldown == 0) BossTheme.colors.textPrimary else BossTheme.colors.textSecondary
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Text(
                        text = if (resendCooldown == 0) {
                            "Resend Magic Link"
                        } else {
                            "Resend in ${resendCooldown}s"
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Manual input toggle
            TextButton(
                onClick = { showManualInput = !showManualInput }
            ) {
                Text(
                    text = if (showManualInput) "Hide Manual Input" else "Having trouble? Paste magic link manually",
                    fontSize = 12.sp,
                    color = BossTheme.colors.textSecondary,
                    textDecoration = TextDecoration.Underline
                )
            }
            
            // Manual magic link input (for debugging)
            if (showManualInput) {
                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedTextField(
                    value = magicLinkInput,
                    onValueChange = { magicLinkInput = it },
                    label = { Text("Magic Link URL", color = BossTheme.colors.textSecondary, fontSize = 12.sp) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Link,
                            contentDescription = "Link",
                            tint = BossTheme.colors.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            keyboardController?.hide()
                            if (magicLinkInput.isNotBlank()) {
                                // Process the manual magic link
                                processMagicLink(magicLinkInput, onSuccess)
                            }
                        }
                    ),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        textColor = BossTheme.colors.textPrimary,
                        backgroundColor = BossTheme.colors.panel,
                        focusedBorderColor = BossTheme.colors.signal,
                        unfocusedBorderColor = BossTheme.colors.line,
                        cursorColor = BossTheme.colors.signal,
                        focusedLabelColor = BossTheme.colors.signal,
                        unfocusedLabelColor = BossTheme.colors.textSecondary
                    )
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Button(
                    onClick = {
                        if (magicLinkInput.isNotBlank()) {
                            processMagicLink(magicLinkInput, onSuccess)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = magicLinkInput.isNotBlank() && !isLoading,
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = BossTheme.colors.signal,
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        text = "Verify Magic Link",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(20.dp))
            
            // Back button
            TextButton(
                onClick = onBack
            ) {
                Text(
                    text = "Back to Sign In",
                    fontSize = 14.sp,
                    color = BossTheme.colors.signal,
                    textDecoration = TextDecoration.Underline
                )
            }
        }
        }
    }
}

/**
 * Process a manual magic link input (for debugging purposes)
 */
private fun processMagicLink(magicLinkUrl: String, onSuccess: () -> Unit) {
    try {
        logger.debug(LogCategory.AUTH, "Processing manual magic link")
        
        // Handle different URL formats and ensure HTTPS for security
        val processedUrl = when {
            // Already a proper deep link
            magicLinkUrl.startsWith("boss://") -> magicLinkUrl
            
            // Supabase magic link from api.risaboss.com or other domains - extract token and create deep link
            (magicLinkUrl.contains("verify") && magicLinkUrl.contains("token=")) -> {
                val token = extractTokenFromUrl(magicLinkUrl)
                val type = extractTypeFromUrl(magicLinkUrl) ?: "magiclink"
                if (token != null) {
                    "boss://auth/verify?token=$token&type=$type"
                } else {
                    logger.warn(LogCategory.AUTH, "Failed to extract token from URL")
                    return
                }
            }
            
            // Force HTTPS for security - convert HTTP to HTTPS
            magicLinkUrl.startsWith("http://") -> {
                val httpsUrl = magicLinkUrl.replaceFirst("http://", "https://")
                logger.debug(LogCategory.AUTH, "Converting insecure HTTP to HTTPS")
                httpsUrl
            }
            
            // Already HTTPS - good to go
            magicLinkUrl.startsWith("https://") -> magicLinkUrl
            
            // Add https:// if missing
            magicLinkUrl.contains("://").not() -> "https://$magicLinkUrl"
            
            else -> magicLinkUrl
        }

        logger.debug(LogCategory.AUTH, "Processed URL for deep link")
        ai.rever.boss.utils.DeepLinkHandler.processDeepLink(processedUrl)
        onSuccess()

    } catch (e: Exception) {
        logger.warn(LogCategory.AUTH, "Error processing manual magic link", error = e)
    }
}

private fun extractTokenFromUrl(url: String): String? {
    return try {
        val tokenParam = "token="
        val tokenStart = url.indexOf(tokenParam)
        if (tokenStart == -1) return null
        
        val tokenValueStart = tokenStart + tokenParam.length
        val tokenEnd = url.indexOf("&", tokenValueStart).let { if (it == -1) url.length else it }
        
        url.substring(tokenValueStart, tokenEnd)
    } catch (e: Exception) {
        logger.warn(LogCategory.AUTH, "Error extracting token from URL", error = e)
        null
    }
}

private fun extractTypeFromUrl(url: String): String? {
    return try {
        val typeParam = "type="
        val typeStart = url.indexOf(typeParam)
        if (typeStart == -1) return null
        
        val typeValueStart = typeStart + typeParam.length
        val typeEnd = url.indexOf("&", typeValueStart).let { if (it == -1) url.length else it }
        
        url.substring(typeValueStart, typeEnd)
    } catch (e: Exception) {
        logger.warn(LogCategory.AUTH, "Error extracting type from URL", error = e)
        null
    }
}
