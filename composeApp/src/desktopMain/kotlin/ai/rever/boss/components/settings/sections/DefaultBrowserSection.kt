package ai.rever.boss.components.settings.sections

import ai.rever.boss.components.settings.shared.SettingsSection
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.utils.DefaultBrowserManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Default Browser section for BOSS Console settings
 *
 * Allows users to:
 * - Check if BOSS is the default browser
 * - Set BOSS as the default browser
 * - View platform-specific instructions
 */
@Composable
fun DefaultBrowserSection() {
    val platformName = DefaultBrowserManager.getPlatformName()
    var isDefault by remember { mutableStateOf<Boolean?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showSuccessDialog by remember { mutableStateOf(false) }
    var showInstructionsDialog by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    // Check status on mount
    LaunchedEffect(Unit) {
        isLoading = true
        errorMessage = null

        val result = DefaultBrowserManager.isDefaultBrowser()
        isLoading = false

        result.fold(
            onSuccess = { isDefault = it },
            onFailure = { error ->
                errorMessage = error.message
                isDefault = null
            }
        )
    }

    SettingsSection(
        title = "Default Browser",
        description = "Make BOSS your default web browser"
    ) {
        // Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = BossTheme.colors.ink,
            shape = RoundedCornerShape(8.dp),
            elevation = 0.dp,
            border = BorderStroke(1.dp, BossTheme.colors.line)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Status Display
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Status",
                            color = BossTheme.colors.textSecondary,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        when {
                            isLoading -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = BossTheme.colors.signal
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Checking...",
                                        color = BossTheme.colors.textSecondary,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                            errorMessage != null -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Outlined.Error,
                                        contentDescription = "Error",
                                        tint = BossTheme.colors.alert,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Error checking status",
                                        color = BossTheme.colors.alert,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                            isDefault == true -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Outlined.CheckCircle,
                                        contentDescription = "Default",
                                        tint = BossTheme.colors.ok,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "BOSS is your default browser",
                                        color = BossTheme.colors.textPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                            isDefault == false -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Outlined.Cancel,
                                        contentDescription = "Not Default",
                                        tint = BossTheme.colors.textSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "BOSS is not your default browser",
                                        color = BossTheme.colors.textSecondary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    // Action Buttons
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Refresh button
                        TextButton(
                            onClick = {
                                coroutineScope.launch {
                                    isLoading = true
                                    errorMessage = null

                                    val result = DefaultBrowserManager.isDefaultBrowser()
                                    isLoading = false

                                    result.fold(
                                        onSuccess = { isDefault = it },
                                        onFailure = { error ->
                                            errorMessage = error.message
                                            isDefault = null
                                        }
                                    )
                                }
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = BossTheme.colors.textSecondary)
                        ) {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = "Refresh",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Refresh", fontSize = 13.sp)
                        }

                        // Set as default button
                        TextButton(
                            onClick = {
                                coroutineScope.launch {
                                    isLoading = true
                                    errorMessage = null

                                    val result = DefaultBrowserManager.setAsDefaultBrowser()
                                    isLoading = false

                                    result.fold(
                                        onSuccess = { wasSetProgrammatically ->
                                            if (wasSetProgrammatically) {
                                                // Successfully set programmatically (macOS/Linux)
                                                isDefault = true
                                                showSuccessDialog = true
                                            } else {
                                                // User action required (Windows)
                                                showInstructionsDialog = true
                                            }
                                        },
                                        onFailure = { error ->
                                            errorMessage = error.message
                                        }
                                    )
                                }
                            },
                            enabled = !isLoading && isDefault != true,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = BossTheme.colors.signal,
                                disabledContentColor = BossTheme.colors.textSecondary
                            )
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = BossTheme.colors.signal
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Setting...", fontSize = 13.sp)
                            } else {
                                Icon(
                                    Icons.AutoMirrored.Outlined.OpenInNew,
                                    contentDescription = "Set",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Set as Default", fontSize = 13.sp)
                            }
                        }
                    }
                }

                // Error message
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Error: $errorMessage",
                        color = BossTheme.colors.alert,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Platform-specific info card
        Card(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = BossTheme.colors.signal.copy(alpha = 0.1f),
            shape = RoundedCornerShape(6.dp),
            elevation = 0.dp
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = "Info",
                    tint = BossTheme.colors.signal,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Platform: $platformName",
                        fontSize = 12.sp,
                        color = BossTheme.colors.textPrimary,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = when (platformName) {
                            "macOS" -> "BOSS will attempt to set itself as default automatically"
                            "Windows" -> "Windows requires manual selection in Settings"
                            else -> "Uses XDG standards for Linux desktop environments"
                        },
                        fontSize = 11.sp,
                        color = BossTheme.colors.textSecondary
                    )
                }
            }
        }
    }

    // Success Dialog
    if (showSuccessDialog) {
        AlertDialog(
            onDismissRequest = { showSuccessDialog = false },
            title = {
                Text(
                    "Success",
                    color = BossTheme.colors.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    "BOSS has been set as your default web browser. Links will now open in BOSS.",
                    color = BossTheme.colors.textSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showSuccessDialog = false }) {
                    Text("OK", color = BossTheme.colors.signal, fontSize = 13.sp)
                }
            },
            backgroundColor = BossTheme.colors.panel,
            contentColor = BossTheme.colors.textPrimary
        )
    }

    // Instructions Dialog (platform-aware)
    if (showInstructionsDialog) {
        AlertDialog(
            onDismissRequest = { showInstructionsDialog = false },
            title = {
                Text(
                    when (platformName) {
                        "macOS" -> "Complete Setup in System Settings"
                        "Windows" -> "Complete Setup in Windows Settings"
                        else -> "Complete Setup in System Settings"
                    },
                    color = BossTheme.colors.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column {
                    Text(
                        when (platformName) {
                            "macOS" -> "System Settings has been opened. Please complete these steps:"
                            "Windows" -> "Windows Settings has been opened. Please complete these steps:"
                            else -> "Please complete these steps in your system settings:"
                        },
                        color = BossTheme.colors.textSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        when (platformName) {
                            "macOS" -> {
                                "1. Find \"Default web browser\" in Desktop & Dock\n" +
                                "2. Click the dropdown menu\n" +
                                "3. Select \"BOSS Console\" from the list"
                            }
                            "Windows" -> {
                                "1. Scroll down to \"Web browser\"\n" +
                                "2. Click on the current browser\n" +
                                "3. Select \"BOSS Console\" from the list\n" +
                                "4. Close Settings"
                            }
                            else -> {
                                "1. Open \"Default Applications\" in your desktop settings\n" +
                                "2. Find \"Web Browser\"\n" +
                                "3. Select \"BOSS Console\" from the list"
                            }
                        },
                        color = BossTheme.colors.textPrimary,
                        fontSize = 13.sp,
                        lineHeight = 22.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "After completing these steps, click \"Refresh\" to verify.",
                        color = BossTheme.colors.textSecondary,
                        fontSize = 12.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showInstructionsDialog = false }) {
                    Text("Got it", color = BossTheme.colors.signal, fontSize = 13.sp)
                }
            },
            backgroundColor = BossTheme.colors.panel,
            contentColor = BossTheme.colors.textPrimary
        )
    }
}
