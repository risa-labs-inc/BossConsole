package ai.rever.boss.components.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.outlined.Launch
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import BossDarkBackground
import BossDarkSurface
import BossDarkBorder
import BossDarkTextPrimary
import BossDarkTextSecondary
import BossDarkAccent
import BossDarkError
import ai.rever.boss.services.supabase.AuthService
import ai.rever.boss.utils.FluckTabCreator
import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.IO

/**
 * Platform-specific URL opening function
 * Opens URL in system browser (or Fluck browser on desktop)
 */
expect fun openUrlInBrowser(url: String)

@Composable
fun CrossDeviceAuthenticationDialog(
    qrCodeUrl: String?,
    challenge: String?,
    sessionId: String? = null,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
    onCreateFluckTab: ((FluckTabInfo) -> Unit)? = null
) {
    if (qrCodeUrl == null) {
        onDismiss()
        return
    }
    
    var isPolling by remember { mutableStateOf(false) }
    var pollError by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    
    // Open URL in Fluck tab or browser - the page itself will display the QR code
    LaunchedEffect(qrCodeUrl) {
        // Open the mobile-auth URL which contains its own QR code display
        if (onCreateFluckTab != null) {
            val fluckTab = FluckTabCreator.createWebAuthnTab(qrCodeUrl)
            onCreateFluckTab(fluckTab)
        } else {
            // Open in external browser as fallback
            openUrlInBrowser(qrCodeUrl)
        }
        
        // Start polling for authentication completion
        if (challenge != null) {
            isPolling = true
            
            coroutineScope.launch(Dispatchers.IO) {
                // Poll for authentication completion
                var attempts = 0
                val maxAttempts = 60 // 60 seconds timeout
                
                while (attempts < maxAttempts && isPolling) {
                    delay(1000) // Poll every second
                    
                    // Check authentication status
                    val result = AuthService.checkAuthenticationStatus(challenge, sessionId)
                    
                    withContext(Dispatchers.Main) {
                        result.fold(
                            onSuccess = { completed ->
                                if (completed) {
                                    isPolling = false
                                    onSuccess()
                                }
                            },
                            onFailure = { error ->
                                // Don't stop polling on transient errors
                                if (error.message?.contains("timeout") == true) {
                                    isPolling = false
                                    pollError = "Authentication timed out. Please try again."
                                }
                            }
                        )
                    }
                    
                    attempts++
                }
                
                if (attempts >= maxAttempts) {
                    withContext(Dispatchers.Main) {
                        isPolling = false
                        pollError = "Authentication timed out. Please try again."
                    }
                }
            }
        }
    }
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = RoundedCornerShape(12.dp),
            backgroundColor = BossDarkSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Cross-Device Authentication",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BossDarkTextPrimary
                    )
                    
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = BossDarkTextSecondary
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Status indicator
                if (isPolling) {
                    CircularProgressIndicator(
                        color = BossDarkAccent,
                        modifier = Modifier.size(64.dp),
                        strokeWidth = 4.dp
                    )
                } else if (pollError != null) {
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = "Error",
                        modifier = Modifier.size(64.dp),
                        tint = BossDarkError
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Launch,
                        contentDescription = "Opening in browser",
                        modifier = Modifier.size(64.dp),
                        tint = BossDarkAccent
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Status Text
                Text(
                    text = when {
                        pollError != null -> "Authentication Failed"
                        isPolling -> "Waiting for Authentication..."
                        onCreateFluckTab != null -> "Opening Authentication in FLUCK"
                        else -> "Opening Authentication Page"
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (pollError != null) BossDarkError else BossDarkTextPrimary,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Instructions
                Text(
                    text = when {
                        pollError != null -> pollError!!
                        isPolling -> "A QR code is displayed in the browser tab. Scan it with your iPhone to complete authentication."
                        onCreateFluckTab != null -> "The authentication page is opening in a new FLUCK tab. You'll see a QR code to scan with your iPhone."
                        else -> "The authentication page is opening in your browser. You'll see a QR code to scan with your iPhone."
                    },
                    fontSize = 14.sp,
                    color = BossDarkTextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (pollError != null) {
                        // Retry button
                        OutlinedButton(
                            onClick = {
                                pollError = null
                                isPolling = true
                                // Re-open the URL
                                if (onCreateFluckTab != null) {
                                    val fluckTab = FluckTabCreator.createWebAuthnTab(qrCodeUrl)
                                    onCreateFluckTab(fluckTab)
                                } else {
                                    openUrlInBrowser(qrCodeUrl)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = BossDarkAccent
                            )
                        ) {
                            Text("Retry")
                        }
                    }
                    
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (pollError != null) BossDarkError else BossDarkBorder,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}
