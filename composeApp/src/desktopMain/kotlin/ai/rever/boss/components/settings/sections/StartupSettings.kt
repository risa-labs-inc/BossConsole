package ai.rever.boss.components.settings.sections

import ai.rever.boss.components.settings.shared.SettingsSection
import ai.rever.boss.components.settings.shared.SettingsLongInput
import ai.rever.boss.components.settings.shared.SettingsButtonRow
import ai.rever.boss.components.settings.shared.SettingsTheme.AccentColor
import ai.rever.boss.components.settings.shared.SettingsTheme.TextPrimary
import ai.rever.boss.components.settings.shared.SettingsTheme.TextSecondary
import ai.rever.boss.startup.StartupSettingsManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private const val MIN_TIMEOUT_MS = 100L
private const val MAX_TIMEOUT_MS = 30000L
private const val DEFAULT_TIMEOUT_MS = 1000L

@Composable
fun StartupSettingsSection() {
    val settings by StartupSettingsManager.currentSettings.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsSection(title = "Workspace Loading") {
            SettingsLongInput(
                label = "Workspace Load Timeout",
                value = settings.workspaceLoadTimeoutMs,
                onValueChange = { newValue ->
                    coroutineScope.launch {
                        StartupSettingsManager.setWorkspaceLoadTimeout(newValue)
                    }
                },
                range = MIN_TIMEOUT_MS..MAX_TIMEOUT_MS,
                description = "Time to wait before showing New Tab dialog (${MIN_TIMEOUT_MS}-${MAX_TIMEOUT_MS}ms)"
            )

            if (settings.workspaceLoadTimeoutMs != DEFAULT_TIMEOUT_MS) {
                SettingsButtonRow(
                    label = "Reset Timeout",
                    buttonText = "Reset",
                    onClick = {
                        coroutineScope.launch {
                            StartupSettingsManager.resetToDefault()
                        }
                    },
                    description = "Reset to default value (${DEFAULT_TIMEOUT_MS}ms)"
                )
            }
        }

        // Info card
        SettingsSection(title = "About") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = AccentColor.copy(alpha = 0.1f),
                shape = RoundedCornerShape(6.dp),
                elevation = 0.dp
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Workspace Loading",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "On startup, the app waits for the workspace manager to load your Last Session. " +
                                "If no workspaces are found within the timeout, it assumes a fresh install and shows the New Tab dialog.",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}
