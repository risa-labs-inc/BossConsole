package ai.rever.boss.components.settings.sections

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import BossDarkAccent
import BossDarkBackground
import BossDarkBorder
import BossDarkContentBackground
import BossDarkError
import BossDarkSuccess
import BossDarkSurface
import BossDarkTextPrimary
import BossDarkTextSecondary
import BossDarkWarning
import ai.rever.boss.components.settings.shared.SettingsSection
import ai.rever.boss.services.supabase.AuthService
import ai.rever.boss.services.passkey.PasskeyInfo
import ai.rever.boss.services.passkey.PasskeyState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val securitySettingsLogger = BossLogger.forComponent("SecuritySettings")

/**
 * WebAuthn capabilities information
 */
data class WebAuthnCapabilities(
    val hasJxBrowserEngine: Boolean,
    val hasTouchId: Boolean,
    val hasSecurityKeySupport: Boolean,
    val hasNfcSupport: Boolean,
    val hasHybridTransport: Boolean,
    val supportedTransports: List<String>,
    val platformName: String
)

/**
 * Detect WebAuthn capabilities on the current platform
 */
private suspend fun detectWebAuthnCapabilities(): WebAuthnCapabilities {
    return try {
        val os = System.getProperty("os.name").lowercase()
        val platformName = when {
            os.contains("mac") -> "macOS"
            os.contains("windows") -> "Windows"  
            os.contains("linux") -> "Linux"
            else -> "Unknown"
        }
        
        // Try to check if JxBrowser is available by checking if passkey is supported
        val hasJxBrowser = try {
            // This is a simplified check - we assume JxBrowser is available if passkeys are supported
            AuthService.isPasskeySupported()
        } catch (e: Exception) {
            false
        }
        
        val hasTouchId = when {
            os.contains("mac") -> {
                try {
                    AuthService.isPasskeySupported()
                } catch (e: Exception) { false }
            }
            else -> false
        }
        
        // Enhanced capabilities when JxBrowser is available
        val supportedTransports = mutableListOf<String>()
        supportedTransports.add("internal")
        
        var hasSecurityKey = false
        var hasNfc = false
        var hasHybrid = false
        
        if (hasJxBrowser) {
            // Enhanced capabilities with JxBrowser
            hasSecurityKey = true
            hasNfc = true
            supportedTransports.addAll(listOf("usb", "nfc"))
            
            if (os.contains("mac")) {
                hasHybrid = true
                supportedTransports.add("hybrid")
            }
        } else {
            // Basic capabilities without JxBrowser
            hasSecurityKey = os.contains("mac") || os.contains("windows")
        }
        
        WebAuthnCapabilities(
            hasJxBrowserEngine = hasJxBrowser,
            hasTouchId = hasTouchId,
            hasSecurityKeySupport = hasSecurityKey,
            hasNfcSupport = hasNfc,
            hasHybridTransport = hasHybrid,
            supportedTransports = supportedTransports,
            platformName = platformName
        )
        
    } catch (e: Exception) {
        // Fallback capabilities
        val os = System.getProperty("os.name").lowercase()
        WebAuthnCapabilities(
            hasJxBrowserEngine = false,
            hasTouchId = false,
            hasSecurityKeySupport = false,
            hasNfcSupport = false,
            hasHybridTransport = false,
            supportedTransports = listOf("internal"),
            platformName = if (os.contains("mac")) "macOS" else if (os.contains("windows")) "Windows" else "Linux"
        )
    }
}

@Composable
fun SecuritySettings() {
    val authState by AuthService.authState.collectAsState()

    // Observe passkey state for embedded browser trigger
    val passkeyStateFlow = AuthService.getPasskeyState()
    val passkeyState by passkeyStateFlow?.collectAsState() ?: remember { mutableStateOf(null) }
    var showEmbeddedBrowser by remember { mutableStateOf(false) }
    var passkeyBrowserUrl by remember { mutableStateOf("") }
    var passkeyBrowserSessionId by remember { mutableStateOf("") }
    var initialPasskeyCount by remember { mutableStateOf(0) }  // Track count when browser opens for polling fallback

    var passkeyFactors by remember { mutableStateOf<List<PasskeyInfo>>(emptyList()) }
    var isLoadingPasskeys by remember { mutableStateOf(false) }
    var touchIDSupported by remember { mutableStateOf(false) }
    var webAuthnCapabilities by remember { mutableStateOf<WebAuthnCapabilities?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showRemovePasskeyDialog by remember { mutableStateOf<PasskeyInfo?>(null) }
    var showEnhancedEnrollDialog by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) } // Add refresh trigger
    val coroutineScope = rememberCoroutineScope()
    
    // Function to refresh passkey list
    val refreshPasskeyList = suspend {
        isLoadingPasskeys = true
        AuthService.getUserPasskeys().fold(
            onSuccess = { passkeys ->
                passkeyFactors = passkeys
                isLoadingPasskeys = false
                errorMessage = null
            },
            onFailure = { error ->
                // Don't show error for passkeys if Touch ID not supported
                if (touchIDSupported) {
                    errorMessage = "Failed to load WebAuthn credentials: ${error.message}"
                }
                isLoadingPasskeys = false
            }
        )
    }
    
    // Load passkeys when component mounts or refreshKey changes
    LaunchedEffect(refreshKey) {
        if (authState is AuthService.AuthState.Authenticated) {
            // Check Touch ID support and detect WebAuthn capabilities (only on first load)
            if (refreshKey == 0) {
                try {
                    touchIDSupported = AuthService.isPasskeySupported()
                    webAuthnCapabilities = detectWebAuthnCapabilities()
                } catch (e: Exception) {
                    touchIDSupported = false
                    webAuthnCapabilities = null
                }
            }
            
            // Refresh passkey list
            refreshPasskeyList()
        }
    }
    
    // Add periodic refresh to catch passkeys added outside of settings
    LaunchedEffect(authState) {
        if (authState is AuthService.AuthState.Authenticated) {
            while (true) {
                kotlinx.coroutines.delay(5000) // Check every 5 seconds
                if (!isLoadingPasskeys) { // Only refresh if not currently loading
                    val currentCount = passkeyFactors.size
                    AuthService.getUserPasskeys().fold(
                        onSuccess = { passkeys ->
                            // Only update if the count changed (new passkey added/removed)
                            if (passkeys.size != currentCount) {
                                passkeyFactors = passkeys
                                errorMessage = null

                                // Fallback: Close embedded browser if new passkey detected during registration
                                if (showEmbeddedBrowser && passkeys.size > initialPasskeyCount) {
                                        securitySettingsLogger.debug(LogCategory.PASSKEY, "New passkey detected via polling, closing embedded browser", mapOf("previousCount" to currentCount, "newCount" to passkeys.size))
                                    showEmbeddedBrowser = false
                                }
                            }
                        },
                        onFailure = { /* Ignore periodic refresh errors */ }
                    )
                }
            }
        }
    }

    // Monitor passkey state for embedded browser trigger
    LaunchedEffect(passkeyState) {
        if (passkeyState is PasskeyState.ShowEmbeddedBrowser) {
            val browserState = passkeyState as PasskeyState.ShowEmbeddedBrowser
            securitySettingsLogger.debug(LogCategory.PASSKEY, "Passkey state changed to ShowEmbeddedBrowser, showing browser screen")
            passkeyBrowserUrl = browserState.url
            passkeyBrowserSessionId = browserState.sessionId
            initialPasskeyCount = passkeyFactors.size  // Track initial count for polling fallback detection
            showEmbeddedBrowser = true
            showEnhancedEnrollDialog = false  // Close the enrollment dialog if it's open
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        
        // Authentication status check
        if (authState !is AuthService.AuthState.Authenticated) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = BossDarkError.copy(alpha = 0.1f),
                shape = RoundedCornerShape(8.dp),
                elevation = 0.dp
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Warning,
                        contentDescription = "Warning",
                        tint = BossDarkError,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "You must be logged in to manage security settings",
                        fontSize = 14.sp,
                        color = BossDarkTextPrimary
                    )
                }
            }
            return@Column
        }
        
        // WebAuthn / Touch ID Authentication
        SettingsSection(
            title = "WebAuthn Authentication",
            description = if (touchIDSupported) 
                "Manage WebAuthn credentials for secure, passwordless authentication"
            else 
                "WebAuthn is not available on this device"
        ) {
            if (!touchIDSupported) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = BossDarkWarning.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp),
                    elevation = 0.dp
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Warning,
                            contentDescription = "Warning",
                            tint = BossDarkWarning,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "WebAuthn is not supported on this device. Please ensure you have biometric authentication enabled in System Preferences.",
                            fontSize = 14.sp,
                            color = BossDarkTextPrimary
                        )
                    }
                }
            } else {
                if (isLoadingPasskeys) {
                    // Loading state
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = BossDarkContentBackground,
                        shape = RoundedCornerShape(8.dp),
                        elevation = 0.dp,
                        border = BorderStroke(1.dp, BossDarkBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = BossDarkAccent,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = "Loading WebAuthn credentials...",
                                fontSize = 14.sp,
                                color = BossDarkTextPrimary
                            )
                        }
                    }
                } else {
                    // Show WebAuthn capabilities if available
                    webAuthnCapabilities?.let { capabilities ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            backgroundColor = BossDarkContentBackground,
                            shape = RoundedCornerShape(8.dp),
                            elevation = 0.dp,
                            border = BorderStroke(1.dp, BossDarkBorder)
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp)
                            ) {
                                Text(
                                    text = "WebAuthn Capabilities",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = BossDarkTextPrimary
                                )
                                
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    WebAuthnCapabilityRow(
                                        icon = if (capabilities.hasJxBrowserEngine) Icons.Outlined.CheckCircle else Icons.Outlined.Error,
                                        label = "WebAuthn Support",
                                        status = if (capabilities.hasJxBrowserEngine) "Available" else "Not supported",
                                        enabled = capabilities.hasJxBrowserEngine
                                    )
                                    
                                    WebAuthnCapabilityRow(
                                        icon = if (capabilities.hasTouchId) Icons.Outlined.Fingerprint else Icons.Outlined.Error,
                                        label = "Platform Authenticator",
                                        status = if (capabilities.hasTouchId) "Touch ID/Windows Hello" else "Not available",
                                        enabled = capabilities.hasTouchId
                                    )
                                    
                                    WebAuthnCapabilityRow(
                                        icon = if (capabilities.hasSecurityKeySupport) Icons.Outlined.Usb else Icons.Outlined.Error,
                                        label = "Security Key Support",
                                        status = if (capabilities.hasSecurityKeySupport) "USB/NFC keys supported" else "Not supported",
                                        enabled = capabilities.hasSecurityKeySupport
                                    )
                                    
                                    WebAuthnCapabilityRow(
                                        icon = if (capabilities.hasHybridTransport) Icons.Outlined.Smartphone else Icons.Outlined.Error,
                                        label = "Cross-Device Authentication",
                                        status = if (capabilities.hasHybridTransport) "QR Code/Bluetooth available" else "Not supported",
                                        enabled = capabilities.hasHybridTransport
                                    )
                                    
                                    WebAuthnCapabilityRow(
                                        icon = if (capabilities.hasNfcSupport) Icons.Outlined.Nfc else Icons.Outlined.Error,
                                        label = "NFC Support",
                                        status = if (capabilities.hasNfcSupport) "NFC authenticators supported" else "Not supported",
                                        enabled = capabilities.hasNfcSupport
                                    )
                                }
                            }
                        }
                    }
                    
                    // Current WebAuthn status
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = BossDarkContentBackground,
                        shape = RoundedCornerShape(8.dp),
                        elevation = 0.dp,
                        border = BorderStroke(1.dp, BossDarkBorder)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Outlined.Security,
                                        contentDescription = "WebAuthn",
                                        tint = BossDarkAccent,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column {
                                        Text(
                                            text = "WebAuthn Credentials",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = BossDarkTextPrimary
                                        )
                                        Text(
                                            text = if (passkeyFactors.isEmpty()) "No credentials enrolled" else "${passkeyFactors.size} credential(s) enrolled",
                                            fontSize = 14.sp,
                                            color = BossDarkTextSecondary
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.weight(1f))

                                if (passkeyFactors.isEmpty()) {
                                    Button(
                                        onClick = {
                                            showEnhancedEnrollDialog = true
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            backgroundColor = BossDarkAccent,
                                            contentColor = BossDarkTextPrimary
                                        )
                                    ) {
                                        Icon(
                                            Icons.Outlined.Add,
                                            contentDescription = "Setup",
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Set Up Passkey")
                                    }
                                }
                            }
                        }
                    }
                    
                    // List enrolled WebAuthn credentials
                    if (passkeyFactors.isNotEmpty()) {
                        passkeyFactors.forEach { passkey ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                backgroundColor = BossDarkContentBackground,
                                shape = RoundedCornerShape(8.dp),
                                elevation = 0.dp,
                                border = BorderStroke(1.dp, BossDarkBorder)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        when (getAuthenticatorTypeDescription(passkey)) {
                                            "Touch ID" -> Icons.Outlined.Fingerprint
                                            "Cross-device" -> Icons.Outlined.Smartphone
                                            "Security Key" -> Icons.Outlined.Usb
                                            else -> Icons.Outlined.Security
                                        },
                                        contentDescription = passkey.displayName,
                                        tint = BossDarkAccent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = passkey.displayName,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = BossDarkTextPrimary
                                        )
                                        Text(
                                            text = "Added ${formatTimestamp(passkey.createdAt)}",
                                            fontSize = 12.sp,
                                            color = BossDarkTextSecondary
                                        )

                                        // Show additional details
                                        Text(
                                            text = formatPasskeyDetails(passkey),
                                            fontSize = 11.sp,
                                            color = BossDarkTextSecondary.copy(alpha = 0.7f),
                                            maxLines = 1
                                        )
                                    }

                                    IconButton(
                                        onClick = { showRemovePasskeyDialog = passkey },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = "Remove",
                                            tint = BossDarkError,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Add another WebAuthn credential button
                        Button(
                            onClick = {
                                showEnhancedEnrollDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = BossDarkContentBackground,
                                contentColor = BossDarkAccent
                            ),
                            border = BorderStroke(1.dp, BossDarkBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Outlined.Add,
                                contentDescription = "Add",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add Another Passkey")
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Security Tips
        SettingsSection(
            title = "Security Best Practices",
            description = "Tips for secure authentication"
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = BossDarkContentBackground,
                shape = RoundedCornerShape(8.dp),
                elevation = 0.dp,
                border = BorderStroke(1.dp, BossDarkBorder)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    SecurityTip(
                        icon = Icons.Outlined.Key,
                        text = "Use WebAuthn for passwordless, secure authentication"
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    SecurityTip(
                        icon = Icons.Outlined.DeviceHub,
                        text = "Register multiple devices for redundancy"
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    SecurityTip(
                        icon = Icons.Outlined.Security,
                        text = "WebAuthn provides superior security over traditional passwords"
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    SecurityTip(
                        icon = Icons.Outlined.Fingerprint,
                        text = "Biometric authentication keeps your credentials secure on-device"
                    )
                }
            }
        }
        
        // Error message
        errorMessage?.let { message ->
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = BossDarkError.copy(alpha = 0.1f),
                shape = RoundedCornerShape(8.dp),
                elevation = 0.dp,
                border = BorderStroke(1.dp, BossDarkError.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Error,
                        contentDescription = "Error",
                        tint = BossDarkError,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = message,
                        fontSize = 14.sp,
                        color = BossDarkTextPrimary
                    )
                }
            }
        }
    }
    
    // Show remove passkey confirmation dialog
    showRemovePasskeyDialog?.let { passkey ->
        AlertDialog(
            onDismissRequest = { showRemovePasskeyDialog = null },
            title = {
                Text(
                    "Remove WebAuthn Credential",
                    color = BossDarkTextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        "Are you sure you want to remove this WebAuthn credential?",
                        color = BossDarkTextSecondary,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        backgroundColor = BossDarkContentBackground,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, BossDarkBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                when (getAuthenticatorTypeDescription(passkey)) {
                                    "Touch ID" -> Icons.Outlined.Fingerprint
                                    "Cross-device" -> Icons.Outlined.Smartphone
                                    "Security Key" -> Icons.Outlined.Usb
                                    else -> Icons.Outlined.Security
                                },
                                contentDescription = passkey.displayName,
                                tint = BossDarkAccent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = passkey.displayName,
                                color = BossDarkTextPrimary,
                                fontSize = 14.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Warning: You will no longer be able to use this credential to sign in.",
                        color = BossDarkError,
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            // Use database ID for deletion, fallback to credential ID if not available
                            val idToDelete = passkey.id ?: passkey.credentialId
                            AuthService.deletePasskey(idToDelete).fold(
                                onSuccess = {
                                    // Trigger refresh of passkey list
                                    refreshKey++
                                    showRemovePasskeyDialog = null
                                },
                                onFailure = { error ->
                                    errorMessage = "Failed to remove WebAuthn credential: ${error.message}"
                                    showRemovePasskeyDialog = null
                                }
                            )
                        }
                    }
                ) {
                    Text("Remove", color = BossDarkError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemovePasskeyDialog = null }) {
                    Text("Cancel", color = BossDarkTextSecondary)
                }
            },
            backgroundColor = BossDarkBackground,
            contentColor = BossDarkTextPrimary
        )
    }
    
    // Show enhanced enroll dialog
    if (showEnhancedEnrollDialog) {
        PasskeyEnrollmentDialog(
            onDismiss = { showEnhancedEnrollDialog = false },
            onSuccess = {
                showEnhancedEnrollDialog = false
                // Trigger refresh of passkey list after successful enrollment
                refreshKey++
            },
            onError = { error ->
                errorMessage = error
                showEnhancedEnrollDialog = false
            }
        )
    }

    // Show embedded browser for passkey registration
    if (showEmbeddedBrowser) {
        ai.rever.boss.components.auth.screens.PasskeyBrowserScreen(
            url = passkeyBrowserUrl,
            sessionId = passkeyBrowserSessionId,
            onSuccess = {
                securitySettingsLogger.info(LogCategory.PASSKEY, "Passkey browser registration successful")
                showEmbeddedBrowser = false
                // Trigger refresh of passkey list after successful registration
                refreshKey++
            },
            onBack = {
                securitySettingsLogger.debug(LogCategory.PASSKEY, "User cancelled passkey registration from browser")
                showEmbeddedBrowser = false
            }
        )
    }
}

@Composable
private fun PasskeyEnrollmentDialog(
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    var isEnrolling by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Set Up Passkey",
                color = BossDarkTextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                if (isEnrolling) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = BossDarkAccent,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            "Setting up your passkey...",
                            color = BossDarkTextSecondary,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    Text(
                        "Set up a passkey for secure, passwordless authentication using Touch ID or Windows Hello.",
                        color = BossDarkTextSecondary,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "• No passwords to remember",
                        color = BossDarkTextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "• More secure than traditional passwords",
                        color = BossDarkTextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "• Works across all your devices",
                        color = BossDarkTextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            if (!isEnrolling) {
                Button(
                    onClick = {
                        isEnrolling = true
                        coroutineScope.launch {
                            try {
                                // Attempt passkey registration
                                AuthService.registerPasskey().fold(
                                    onSuccess = {
                                        isEnrolling = false
                                        onSuccess()
                                    },
                                    onFailure = { error ->
                                        isEnrolling = false
                                        onError("Failed to enroll passkey: ${error.message}")
                                    }
                                )
                            } catch (e: Exception) {
                                isEnrolling = false
                                onError("Passkey enrollment failed: ${e.message}")
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = BossDarkAccent,
                        contentColor = BossDarkTextPrimary
                    )
                ) {
                    Text("Set Up Passkey")
                }
            }
        },
        dismissButton = {
            if (!isEnrolling) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = BossDarkTextSecondary)
                }
            }
        },
        backgroundColor = BossDarkBackground,
        contentColor = BossDarkTextPrimary
    )
}

@Composable
private fun SecurityTip(
    icon: ImageVector,
    text: String
) {
    Row(
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = BossDarkAccent,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            fontSize = 13.sp,
            color = BossDarkTextPrimary.copy(alpha = 0.9f),
            lineHeight = 18.sp
        )
    }
}

/**
 * Composable for displaying WebAuthn capability rows
 */
@Composable
private fun WebAuthnCapabilityRow(
    icon: ImageVector,
    label: String,
    status: String,
    enabled: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (enabled) BossDarkSuccess else BossDarkError,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label,
                fontSize = 14.sp,
                color = BossDarkTextPrimary
            )
        }
        Text(
            text = status,
            fontSize = 12.sp,
            color = if (enabled) BossDarkSuccess else BossDarkTextSecondary
        )
    }
}

/**
 * Helper functions for enhanced passkey display
 */
private fun getAuthenticatorTypeDescription(passkey: PasskeyInfo): String {
    return when {
        passkey.transports.contains("usb") -> "USB Security Key"
        passkey.transports.contains("nfc") -> "NFC Authenticator"
        passkey.transports.contains("hybrid") -> "Cross-device Authentication"
        else -> "Touch ID"
    }
}

private fun formatPasskeyDetails(passkey: PasskeyInfo): String {
    val status = "Verified"
    val createdDate = try {
        val date = java.util.Date(passkey.createdAt)
        java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault()).format(date)
    } catch (e: Exception) {
        "Unknown date"
    }
    
    val lastUsed = if (passkey.lastUsed != null) {
        try {
            val date = java.util.Date(passkey.lastUsed)
            val now = System.currentTimeMillis()
            val diffDays = (now - passkey.lastUsed) / (24 * 60 * 60 * 1000)
            when {
                diffDays == 0L -> "today"
                diffDays == 1L -> "yesterday"  
                diffDays < 30 -> "${diffDays} days ago"
                else -> java.text.SimpleDateFormat("MMM dd", java.util.Locale.getDefault()).format(date)
            }
        } catch (e: Exception) {
            "recently"
        }
    } else {
        "never used"
    }
    
    return "$status • Created $createdDate • Last used $lastUsed"
}

/**
 * Helper function to format timestamps for display in the Touch ID credentials list
 */
private fun formatTimestamp(timestamp: Long): String {
    return try {
        val date = java.util.Date(timestamp)
        val format = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
        format.format(date)
    } catch (e: Exception) {
        "Unknown"
    }
}
