package ai.rever.boss.components.settings.sections

import ai.rever.boss.components.settings.shared.SettingsButtonRow
import ai.rever.boss.components.settings.shared.SettingsDropdown
import ai.rever.boss.components.settings.shared.SettingsNumberInput
import ai.rever.boss.components.settings.shared.SettingsSection
import ai.rever.boss.components.settings.shared.SettingsTextField
import ai.rever.boss.components.settings.shared.SettingsTheme.AccentColor
import ai.rever.boss.components.settings.shared.SettingsTheme.SurfaceColor
import ai.rever.boss.components.settings.shared.SettingsTheme.TextPrimary
import ai.rever.boss.components.settings.shared.SettingsTheme.TextSecondary
import ai.rever.boss.components.settings.shared.SettingsToggle
import ai.rever.boss.plugin.browser.BrowserSettings
import ai.rever.boss.plugin.browser.BrowserSettingsManager
import ai.rever.boss.terminal.ExistingSplitTargetMode
import ai.rever.boss.terminal.TerminalLinkOpenMode
import ai.rever.boss.terminal.TerminalLinkSettingsManager
import ai.rever.boss.utils.ApplicationRestarter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun FluckBrowserSettings() {
    var userAgent by remember { mutableStateOf(BrowserSettings.userAgent ?: "Default") }
    var currentProfile by remember { mutableStateOf(BrowserSettings.currentProfile) }
    var customUserAgent by remember { mutableStateOf(BrowserSettings.customUserAgent ?: "") }
    var maxInitRetries by remember { mutableStateOf(BrowserSettings.maxInitRetries) }
    var maxRecoveryAttempts by remember { mutableStateOf(BrowserSettings.maxRecoveryAttempts) }

    val terminalLinkSettings by TerminalLinkSettingsManager.currentSettings.collectAsState()
    var terminalLinkOpenMode by remember(terminalLinkSettings) { mutableStateOf(terminalLinkSettings.openMode) }
    var existingSplitTarget by remember(terminalLinkSettings) { mutableStateOf(terminalLinkSettings.existingSplitTarget) }

    val userAgents = listOf("Default", "Chrome", "Firefox", "Safari", "Edge", "Custom")
    var showRestartDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Helper to save and check for restart
    fun saveAndCheckRestart() {
        val profileChanged = BrowserSettings.currentProfile != currentProfile
        val userAgentChanged =
            BrowserSettings.userAgent != (if (userAgent == "Default") null else userAgent) ||
                (userAgent == "Custom" && BrowserSettings.customUserAgent != customUserAgent)

        BrowserSettings.currentProfile = currentProfile
        BrowserSettings.userAgent = if (userAgent == "Default") null else userAgent
        if (userAgent == "Custom") {
            BrowserSettings.customUserAgent = customUserAgent
        }
        BrowserSettings.maxInitRetries = maxInitRetries
        BrowserSettings.maxRecoveryAttempts = maxRecoveryAttempts

        coroutineScope.launch {
            BrowserSettingsManager.saveSettings()
        }

        if (profileChanged || userAgentChanged) {
            showRestartDialog = true
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // User Agent
        SettingsSection(title = "User Agent") {
            SettingsDropdown(
                label = "Browser Identity",
                options = userAgents,
                selectedOption = userAgent,
                onOptionSelected = {
                    userAgent = it
                    BrowserSettings.userAgent = if (it == "Default") null else it
                },
                description = "How websites identify your browser",
            )

            if (userAgent == "Custom") {
                SettingsTextField(
                    label = "Custom User Agent String",
                    value = customUserAgent,
                    onValueChange = {
                        customUserAgent = it
                        BrowserSettings.customUserAgent = it
                    },
                    placeholder = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)...",
                )
            }

            // Restart notice
            Card(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = AccentColor.copy(alpha = 0.1f),
                shape = RoundedCornerShape(6.dp),
                elevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = "Info",
                        tint = AccentColor,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Application restart required for browser settings changes",
                        fontSize = 12.sp,
                        color = TextSecondary,
                    )
                }
            }
        }

        // Default Browser
        DefaultBrowserSection()

        // Terminal Link Behavior
        SettingsSection(title = "Terminal Links") {
            val linkOpenModeOptions =
                listOf(
                    "Always Ask" to TerminalLinkOpenMode.ALWAYS_ASK,
                    "Existing Split" to TerminalLinkOpenMode.EXISTING_SPLIT,
                    "Vertical Split" to TerminalLinkOpenMode.VERTICAL_SPLIT,
                    "Horizontal Split" to TerminalLinkOpenMode.HORIZONTAL_SPLIT,
                    "New Tab" to TerminalLinkOpenMode.NEW_TAB,
                    "System Default" to TerminalLinkOpenMode.SYSTEM_DEFAULT,
                )

            SettingsDropdown(
                label = "Open links with",
                options = linkOpenModeOptions.map { it.first },
                selectedOption = linkOpenModeOptions.find { it.second == terminalLinkOpenMode }?.first ?: "Always Ask",
                onOptionSelected = { selectedLabel ->
                    val selectedMode =
                        linkOpenModeOptions.find { it.first == selectedLabel }?.second
                            ?: TerminalLinkOpenMode.ALWAYS_ASK
                    terminalLinkOpenMode = selectedMode
                    coroutineScope.launch {
                        TerminalLinkSettingsManager.setOpenMode(selectedMode)
                    }
                },
                description =
                    when (terminalLinkOpenMode) {
                        TerminalLinkOpenMode.ALWAYS_ASK -> "A dialog appears each time you click a link"
                        TerminalLinkOpenMode.EXISTING_SPLIT -> "Opens in an existing split panel if available"
                        TerminalLinkOpenMode.VERTICAL_SPLIT -> "Opens in a new panel to the right"
                        TerminalLinkOpenMode.HORIZONTAL_SPLIT -> "Opens in a new panel below"
                        TerminalLinkOpenMode.NEW_TAB -> "Opens in a new tab"
                        TerminalLinkOpenMode.SYSTEM_DEFAULT -> "Opens outside BOSS with the system default app"
                    },
            )

            if (terminalLinkOpenMode == TerminalLinkOpenMode.EXISTING_SPLIT) {
                val targetModeOptions =
                    listOf(
                        "Most Recent Active" to ExistingSplitTargetMode.MOST_RECENT_ACTIVE,
                        "First Available" to ExistingSplitTargetMode.FIRST_AVAILABLE,
                    )

                SettingsDropdown(
                    label = "Target panel",
                    options = targetModeOptions.map { it.first },
                    selectedOption = targetModeOptions.find { it.second == existingSplitTarget }?.first ?: "Most Recent Active",
                    onOptionSelected = { selectedLabel ->
                        val selectedMode =
                            targetModeOptions.find { it.first == selectedLabel }?.second
                                ?: ExistingSplitTargetMode.MOST_RECENT_ACTIVE
                        existingSplitTarget = selectedMode
                        coroutineScope.launch {
                            TerminalLinkSettingsManager.setExistingSplitTarget(selectedMode)
                        }
                    },
                    description =
                        when (existingSplitTarget) {
                            ExistingSplitTargetMode.MOST_RECENT_ACTIVE -> "Opens in the most recently used panel"
                            ExistingSplitTargetMode.FIRST_AVAILABLE -> "Opens in the first available panel"
                        },
                )
            }

            if (terminalLinkOpenMode != TerminalLinkOpenMode.ALWAYS_ASK) {
                SettingsButtonRow(
                    label = "Reset Link Behavior",
                    buttonText = "Reset",
                    onClick = {
                        terminalLinkOpenMode = TerminalLinkOpenMode.ALWAYS_ASK
                        coroutineScope.launch {
                            TerminalLinkSettingsManager.resetToDefault()
                        }
                    },
                    description = "Reset to \"Always Ask\" mode",
                )
            }
        }

        // Secret Manager Settings
        SettingsSection(title = "Secret Manager") {
            var discretePasswordFill by remember { mutableStateOf(BrowserSettings.discretePasswordFill) }

            SettingsToggle(
                label = "Discrete Password Fill",
                checked = discretePasswordFill,
                onCheckedChange = { enabled ->
                    discretePasswordFill = enabled
                    BrowserSettings.discretePasswordFill = enabled
                    coroutineScope.launch {
                        BrowserSettingsManager.saveSettings()
                    }
                },
                description = "Hide filled passwords with blur effect for privacy",
            )

            // Info card explaining the feature
            Card(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = AccentColor.copy(alpha = 0.1f),
                shape = RoundedCornerShape(6.dp),
                elevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.VisibilityOff,
                        contentDescription = "Privacy",
                        tint = AccentColor,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text =
                            "When enabled, auto-filled password fields are blurred for shoulder surfing protection. " +
                                "Blur persists even on \"show password\" toggles.",
                        fontSize = 12.sp,
                        color = TextSecondary,
                    )
                }
            }
        }

        // Tab Sharing
        SettingsSection(title = "Tab Sharing") {
            var showShareButton by remember { mutableStateOf(BrowserSettings.showShareButton) }

            SettingsToggle(
                label = "Show share (QR) button",
                checked = showShareButton,
                onCheckedChange = { enabled ->
                    showShareButton = enabled
                    BrowserSettings.showShareButton = enabled
                    coroutineScope.launch {
                        BrowserSettingsManager.saveSettings()
                    }
                },
                description = "Adds the co-browse QR / share button to the browser toolbar. Off by default.",
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = AccentColor.copy(alpha = 0.1f),
                shape = RoundedCornerShape(6.dp),
                elevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = "Info",
                        tint = AccentColor,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text =
                            "When on, each browser tab shows a QR/share button to start a co-browse session. " +
                                "Open a new browser tab for the change to take effect.",
                        fontSize = 12.sp,
                        color = TextSecondary,
                    )
                }
            }
        }

        // Profile Management
        ProfileManagementSection(
            currentProfile = currentProfile,
            onProfileChange = { currentProfile = it },
        )

        // Advanced Settings
        SettingsSection(title = "Advanced") {
            SettingsNumberInput(
                label = "Max Initialization Retries",
                value = maxInitRetries,
                onValueChange = { maxInitRetries = it },
                range = 1..10,
                description = "Attempts to initialize browser on startup",
            )

            SettingsNumberInput(
                label = "Max Recovery Attempts",
                value = maxRecoveryAttempts,
                onValueChange = { maxRecoveryAttempts = it },
                range = 1..10,
                description = "Attempts to recover when browser becomes invalid",
            )

            SettingsButtonRow(
                label = "Apply Browser Settings",
                buttonText = "Apply",
                onClick = { saveAndCheckRestart() },
                description = "Save changes (may require restart)",
            )
        }
    }

    // Restart dialog
    if (showRestartDialog) {
        AlertDialog(
            onDismissRequest = { showRestartDialog = false },
            title = {
                Text(
                    "Restart Required",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Column {
                    Text(
                        "Browser settings have been changed and require an application restart to take effect.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Make sure to save any unsaved work before restarting.",
                        color = TextSecondary.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestartDialog = false
                        ApplicationRestarter.scheduleRestart(delayMillis = 500)
                    },
                ) {
                    Text("Restart Now", color = AccentColor)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestartDialog = false }) {
                    Text("Later", color = TextSecondary)
                }
            },
            backgroundColor = SurfaceColor,
            contentColor = TextPrimary,
        )
    }
}
