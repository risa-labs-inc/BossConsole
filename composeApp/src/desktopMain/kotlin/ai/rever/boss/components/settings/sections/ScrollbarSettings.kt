package ai.rever.boss.components.settings.sections

import ai.rever.boss.components.settings.shared.SettingsInfoRow
import ai.rever.boss.components.settings.shared.SettingsSection
import ai.rever.boss.components.settings.shared.SettingsSlider
import ai.rever.boss.components.settings.shared.SettingsToggle
import ai.rever.boss.scrollbar.ScrollbarDimensions
import ai.rever.boss.scrollbar.ScrollbarSettingsManager
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun ScrollbarSettings() {
    val settings by ScrollbarSettingsManager.currentSettings.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Local state for sliders to provide immediate feedback
    var panelThickness by remember(settings.panelThickness) {
        mutableStateOf(settings.panelThickness.toFloat())
    }
    var barThickness by remember(settings.barThickness) {
        mutableStateOf(settings.barThickness.toFloat())
    }
    var fadeDelay by remember(settings.fadeDelayMs) {
        mutableStateOf(settings.fadeDelayMs.toFloat())
    }
    var fadeDuration by remember(settings.fadeDurationMs) {
        mutableStateOf(settings.fadeDurationMs.toFloat())
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSection(title = "Scrollbar Thickness") {
            SettingsSlider(
                label = "Panel Scrollbar Thickness",
                value = panelThickness,
                onValueChange = { panelThickness = it },
                onValueChangeFinished = {
                    coroutineScope.launch {
                        ScrollbarSettingsManager.updateSettings(
                            settings.copy(panelThickness = panelThickness.toInt()),
                        )
                    }
                },
                valueRange = ScrollbarDimensions.MIN_THICKNESS.value..ScrollbarDimensions.MAX_THICKNESS.value,
                steps = 13, // 2dp to 16dp = 14 values, 13 steps
                valueDisplay = { "${it.toInt()}dp" },
                description = "Thickness of scrollbars in panels (Console, Git, Codebase, etc.)",
            )

            SettingsSlider(
                label = "Bar Scrollbar Thickness",
                value = barThickness,
                onValueChange = { barThickness = it },
                onValueChangeFinished = {
                    coroutineScope.launch {
                        ScrollbarSettingsManager.updateSettings(
                            settings.copy(barThickness = barThickness.toInt()),
                        )
                    }
                },
                valueRange = ScrollbarDimensions.MIN_THICKNESS.value..ScrollbarDimensions.MAX_THICKNESS.value,
                steps = 13,
                valueDisplay = { "${it.toInt()}dp" },
                description = "Thickness of scrollbars in horizontal bars (Tab Bar, Bottom Bar)",
            )

            SettingsInfoRow(
                label = "Default Panel Thickness",
                value = "${ScrollbarDimensions.PANEL_THICKNESS.value.toInt()}dp",
                description = "Standard thickness for panel scrollbars",
            )

            SettingsInfoRow(
                label = "Default Bar Thickness",
                value = "${ScrollbarDimensions.BAR_THICKNESS.value.toInt()}dp",
                description = "Standard thickness for horizontal bar scrollbars",
            )
        }

        SettingsSection(title = "Visibility") {
            SettingsToggle(
                label = "Always Show Scrollbars",
                checked = settings.alwaysShowScrollbars,
                onCheckedChange = { enabled ->
                    coroutineScope.launch {
                        ScrollbarSettingsManager.updateSettings(
                            settings.copy(alwaysShowScrollbars = enabled),
                        )
                    }
                },
                description = "Show scrollbars at all times instead of only when scrolling",
            )
        }

        SettingsSection(title = "Animation") {
            SettingsSlider(
                label = "Fade Delay",
                value = fadeDelay,
                onValueChange = { fadeDelay = it },
                onValueChangeFinished = {
                    coroutineScope.launch {
                        ScrollbarSettingsManager.updateSettings(
                            settings.copy(fadeDelayMs = fadeDelay.toInt()),
                        )
                    }
                },
                valueRange = 0f..3000f,
                steps = 6, // 0, 500, 1000, 1500, 2000, 2500, 3000 = 7 values
                valueDisplay = { "${it.toInt()}ms" },
                description = "Time before scrollbar fades out after scrolling stops",
            )

            SettingsSlider(
                label = "Fade Duration",
                value = fadeDuration,
                onValueChange = { fadeDuration = it },
                onValueChangeFinished = {
                    coroutineScope.launch {
                        ScrollbarSettingsManager.updateSettings(
                            settings.copy(fadeDurationMs = fadeDuration.toInt()),
                        )
                    }
                },
                valueRange = 100f..1000f,
                steps = 9, // 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000 = 10 values
                valueDisplay = { "${it.toInt()}ms" },
                description = "Duration of the scrollbar fade-out animation",
            )

            SettingsInfoRow(
                label = "Auto-hide Behavior",
                value = "1.5s inactivity",
                description = "Scrollbars auto-hide after 1.5 seconds of inactivity",
            )

            SettingsInfoRow(
                label = "Fade Animation",
                value = "150ms / 500ms",
                description = "Fade animation duration: 150ms (show) / 500ms (hide)",
            )
        }
    }
}
