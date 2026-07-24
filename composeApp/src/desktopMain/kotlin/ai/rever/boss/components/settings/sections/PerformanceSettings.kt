package ai.rever.boss.components.settings.sections

import ai.rever.boss.components.settings.shared.SettingsButtonRow
import ai.rever.boss.components.settings.shared.SettingsSection
import ai.rever.boss.components.settings.shared.SettingsSlider
import ai.rever.boss.components.settings.shared.SettingsToggle
import ai.rever.boss.performance.PerformanceSettingsManager
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun PerformanceSettings() {
    val settings by PerformanceSettingsManager.currentSettings.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Local state for sliders (allows smooth dragging before committing)
    var memoryWarning by remember(settings) { mutableStateOf(settings.memoryWarningThresholdPercent.toFloat()) }
    var memoryCritical by remember(settings) { mutableStateOf(settings.memoryCriticalThresholdPercent.toFloat()) }
    var cpuWarning by remember(settings) { mutableStateOf(settings.cpuWarningThresholdPercent.toFloat()) }
    var cpuCritical by remember(settings) { mutableStateOf(settings.cpuCriticalThresholdPercent.toFloat()) }
    var historyRetention by remember(settings) { mutableStateOf(settings.historyRetentionMinutes.toFloat()) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // General Settings
        SettingsSection(title = "General") {
            SettingsToggle(
                label = "Enable Performance Monitoring",
                checked = settings.enabled,
                onCheckedChange = { enabled ->
                    coroutineScope.launch {
                        PerformanceSettingsManager.updateSettings(
                            settings.copy(enabled = enabled),
                        )
                    }
                },
                description = "Track memory, CPU, and resource usage in real-time",
            )

            SettingsToggle(
                label = "Show Status Bar Indicator",
                checked = settings.showIndicator,
                onCheckedChange = { show ->
                    coroutineScope.launch {
                        PerformanceSettingsManager.updateSettings(
                            settings.copy(showIndicator = show),
                        )
                    }
                },
                description = "Display memory and CPU usage in the bottom status bar",
                enabled = settings.enabled,
            )
        }

        // Memory Thresholds
        SettingsSection(title = "Memory Thresholds") {
            SettingsSlider(
                label = "Warning Threshold",
                value = memoryWarning,
                onValueChange = { memoryWarning = it },
                onValueChangeFinished = {
                    coroutineScope.launch {
                        PerformanceSettingsManager.updateSettings(
                            settings.copy(memoryWarningThresholdPercent = memoryWarning.toInt()),
                        )
                    }
                },
                valueRange = 50f..100f,
                steps = 9,
                valueDisplay = { "${it.toInt()}%" },
                description = "Show warning when memory usage exceeds this level",
            )

            SettingsSlider(
                label = "Critical Threshold",
                value = memoryCritical,
                onValueChange = { memoryCritical = it },
                onValueChangeFinished = {
                    coroutineScope.launch {
                        PerformanceSettingsManager.updateSettings(
                            settings.copy(memoryCriticalThresholdPercent = memoryCritical.toInt()),
                        )
                    }
                },
                valueRange = 50f..100f,
                steps = 9,
                valueDisplay = { "${it.toInt()}%" },
                description = "Show critical alert when memory usage exceeds this level",
            )
        }

        // CPU Thresholds
        SettingsSection(title = "CPU Thresholds") {
            SettingsSlider(
                label = "Warning Threshold",
                value = cpuWarning,
                onValueChange = { cpuWarning = it },
                onValueChangeFinished = {
                    coroutineScope.launch {
                        PerformanceSettingsManager.updateSettings(
                            settings.copy(cpuWarningThresholdPercent = cpuWarning.toInt()),
                        )
                    }
                },
                valueRange = 50f..100f,
                steps = 9,
                valueDisplay = { "${it.toInt()}%" },
                description = "Show warning when CPU usage exceeds this level",
            )

            SettingsSlider(
                label = "Critical Threshold",
                value = cpuCritical,
                onValueChange = { cpuCritical = it },
                onValueChangeFinished = {
                    coroutineScope.launch {
                        PerformanceSettingsManager.updateSettings(
                            settings.copy(cpuCriticalThresholdPercent = cpuCritical.toInt()),
                        )
                    }
                },
                valueRange = 50f..100f,
                steps = 9,
                valueDisplay = { "${it.toInt()}%" },
                description = "Show critical alert when CPU usage exceeds this level",
            )
        }

        // History
        SettingsSection(title = "History") {
            SettingsSlider(
                label = "History Retention",
                value = historyRetention,
                onValueChange = { historyRetention = it },
                onValueChangeFinished = {
                    coroutineScope.launch {
                        PerformanceSettingsManager.updateSettings(
                            settings.copy(historyRetentionMinutes = historyRetention.toInt()),
                        )
                    }
                },
                valueRange = 5f..60f,
                steps = 10,
                valueDisplay = { "${it.toInt()} min" },
                description = "How long to keep performance history for charts",
            )
        }

        // Reset
        SettingsSection(title = "Reset") {
            SettingsButtonRow(
                label = "Reset to Defaults",
                buttonText = "Reset",
                onClick = {
                    coroutineScope.launch {
                        PerformanceSettingsManager.resetToDefault()
                    }
                },
                description = "Restore all performance settings to their default values",
                isDestructive = true,
            )
        }
    }
}
