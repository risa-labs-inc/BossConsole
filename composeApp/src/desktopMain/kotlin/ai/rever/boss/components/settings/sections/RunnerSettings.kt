package ai.rever.boss.components.settings.sections

import ai.rever.boss.components.settings.shared.SettingsSection
import ai.rever.boss.components.settings.shared.SettingsSlider
import ai.rever.boss.components.settings.shared.SettingsToggle
import ai.rever.boss.components.settings.shared.SettingsTheme.AccentColor
import ai.rever.boss.components.settings.shared.SettingsTheme.BorderColor
import ai.rever.boss.components.settings.shared.SettingsTheme.TextPrimary
import ai.rever.boss.components.settings.shared.SettingsTheme.TextSecondary
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.run.RunnerSettingsManager
import ai.rever.boss.run.RunnerTerminalTarget
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun RunnerSettings() {
    val settings by RunnerSettingsManager.currentSettings.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Local state for slider
    var rerunDelay by remember(settings) { mutableStateOf(settings.rerunDelayMs.toFloat()) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Terminal Target Selection
        SettingsSection(title = "Terminal Target") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TerminalTargetOption(
                    title = "Sidebar Panel",
                    description = "Open in the left sidebar terminal area (like VS Code)",
                    selected = settings.terminalTarget == RunnerTerminalTarget.SIDEBAR_PANEL,
                    onClick = {
                        coroutineScope.launch {
                            RunnerSettingsManager.setTerminalTarget(RunnerTerminalTarget.SIDEBAR_PANEL)
                        }
                    }
                )

                TerminalTargetOption(
                    title = "Main Panel",
                    description = "Open in the main content area (like IntelliJ IDEA)",
                    selected = settings.terminalTarget == RunnerTerminalTarget.MAIN_PANEL,
                    onClick = {
                        coroutineScope.launch {
                            RunnerSettingsManager.setTerminalTarget(RunnerTerminalTarget.MAIN_PANEL)
                        }
                    }
                )
            }
        }

        // Behavior Settings
        SettingsSection(title = "Behavior") {
            SettingsToggle(
                label = "Focus on Run",
                checked = settings.focusOnRun,
                onCheckedChange = { enabled ->
                    coroutineScope.launch {
                        RunnerSettingsManager.setFocusOnRun(enabled)
                    }
                },
                description = "Automatically focus the terminal when a runner starts"
            )

            SettingsToggle(
                label = "Notify on Exit",
                checked = settings.notifyOnExit,
                onCheckedChange = { enabled ->
                    coroutineScope.launch {
                        RunnerSettingsManager.setNotifyOnExit(enabled)
                    }
                },
                description = "Show a notification when a runner process completes"
            )

            SettingsSlider(
                label = "Re-run Delay",
                value = rerunDelay,
                onValueChange = { rerunDelay = it },
                onValueChangeFinished = {
                    coroutineScope.launch {
                        RunnerSettingsManager.setRerunDelayMs(rerunDelay.toLong())
                    }
                },
                valueRange = 0f..2000f,
                steps = 19,
                valueDisplay = { "${it.toInt()} ms" },
                description = "Delay between Ctrl+C and new command (for sidebar terminal)"
            )
        }

        // Run Controls Info
        SettingsSection(title = "Run Controls") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RunControlInfoItem(
                    icon = "▶",
                    iconColor = BossTheme.colors.ok,
                    title = "Run",
                    description = "Execute the selected configuration"
                )
                RunControlInfoItem(
                    icon = "↻",
                    iconColor = BossTheme.colors.ok,
                    title = "Re-run",
                    description = "Stop current run and execute again"
                )
                RunControlInfoItem(
                    icon = "■",
                    iconColor = BossTheme.colors.alert,
                    title = "Stop",
                    description = "Terminate the running process (Ctrl+C)"
                )
            }
        }

        // Notes
        SettingsSection(title = "Notes") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                NoteItem(text = "Re-run creates new terminal tab (closes old one)")
                NoteItem(text = "Sidebar terminal must be open to use Sidebar Panel target")
            }
        }
    }
}

@Composable
private fun TerminalTargetOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (selected) AccentColor else BorderColor
    val backgroundColor = if (selected) AccentColor.copy(alpha = 0.15f) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (selected) AccentColor else TextPrimary
            )
            Text(
                text = description,
                fontSize = 11.sp,
                color = TextSecondary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = "Selected",
                tint = AccentColor,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun RunControlInfoItem(
    icon: String,
    iconColor: Color,
    title: String,
    description: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(iconColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = icon,
                fontSize = 12.sp,
                color = Color.White
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary
            )
            Text(
                text = description,
                fontSize = 11.sp,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun NoteItem(text: String) {
    Text(
        text = "• $text",
        fontSize = 12.sp,
        color = TextSecondary,
        lineHeight = 18.sp
    )
}
