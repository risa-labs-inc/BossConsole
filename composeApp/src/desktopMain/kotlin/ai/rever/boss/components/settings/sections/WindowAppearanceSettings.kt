package ai.rever.boss.components.settings.sections

import ai.rever.boss.components.settings.shared.SettingsSection
import ai.rever.boss.components.settings.shared.SettingsToggle
import ai.rever.boss.components.settings.shared.SettingsInfoRow
import ai.rever.boss.window.WindowAppearanceSettingsManager
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun WindowAppearanceSettings() {
    val settings by WindowAppearanceSettingsManager.currentSettings.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Determine platform default
    val os = System.getProperty("os.name").lowercase()
    val platformDefault = when {
        os.contains("mac") -> "Shown"
        os.contains("linux") -> "Hidden"
        os.contains("windows") -> "Hidden"
        else -> "Platform-dependent"
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsSection(title = "Title Bar") {
            SettingsToggle(
                label = "Show Title Bar",
                checked = settings.showTitleBar,
                onCheckedChange = { enabled ->
                    coroutineScope.launch {
                        WindowAppearanceSettingsManager.updateSettings(
                            settings.copy(showTitleBar = enabled)
                        )
                    }
                },
                description = "Display the \"Boss Console\" title bar at the top of the window"
            )

            SettingsInfoRow(
                label = "Platform Default",
                value = platformDefault,
                description = "The default setting for your operating system"
            )
        }
    }
}
