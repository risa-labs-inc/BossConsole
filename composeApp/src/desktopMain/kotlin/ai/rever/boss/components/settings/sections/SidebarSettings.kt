package ai.rever.boss.components.settings.sections

import ai.rever.boss.components.settings.shared.SettingsDropdown
import ai.rever.boss.components.settings.shared.SettingsNumberInput
import ai.rever.boss.components.settings.shared.SettingsSection
import ai.rever.boss.components.sidebar.SidebarIconLimitMode
import ai.rever.boss.components.sidebar.SidebarVisibilitySettings
import ai.rever.boss.components.sidebar.SidebarVisibilitySettingsManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private const val MODE_ADAPTIVE = "Adaptive (fit window height)"
private const val MODE_FIXED = "Fixed number"

/**
 * Settings for the sidebar plugin-icon rails: how many icons each slot
 * shows before the rest collapse into the slot's "More" overflow menu.
 * Persisted alongside the customize-menu state in
 * [SidebarVisibilitySettings].
 */
@Composable
fun SidebarSettings() {
    val settings by SidebarVisibilitySettingsManager.currentSettings.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsSection(title = "Plugin Icons") {
            SettingsDropdown(
                label = "Icons per slot",
                options = listOf(MODE_ADAPTIVE, MODE_FIXED),
                selectedOption = when (settings.iconLimitMode) {
                    SidebarIconLimitMode.ADAPTIVE -> MODE_ADAPTIVE
                    SidebarIconLimitMode.FIXED -> MODE_FIXED
                },
                onOptionSelected = { option ->
                    val mode = if (option == MODE_FIXED) {
                        SidebarIconLimitMode.FIXED
                    } else {
                        SidebarIconLimitMode.ADAPTIVE
                    }
                    coroutineScope.launch {
                        SidebarVisibilitySettingsManager.updateSettings(
                            settings.copy(iconLimitMode = mode)
                        )
                    }
                },
                description = "Icons beyond the limit collapse into each slot's More menu. " +
                    "Adaptive shows as many as fit the window height."
            )

            SettingsNumberInput(
                label = "Fixed icon limit",
                value = settings.fixedIconLimit,
                onValueChange = { newValue ->
                    coroutineScope.launch {
                        SidebarVisibilitySettingsManager.updateSettings(
                            settings.copy(
                                fixedIconLimit = newValue.coerceIn(
                                    SidebarVisibilitySettings.FIXED_ICON_LIMIT_RANGE
                                )
                            )
                        )
                    }
                },
                range = SidebarVisibilitySettings.FIXED_ICON_LIMIT_RANGE.first..
                    SidebarVisibilitySettings.FIXED_ICON_LIMIT_RANGE.last,
                enabled = settings.iconLimitMode == SidebarIconLimitMode.FIXED,
                description = "Maximum plugin icons per slot when using a fixed number"
            )
        }
    }
}
