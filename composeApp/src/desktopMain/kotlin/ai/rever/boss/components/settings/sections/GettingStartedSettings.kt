package ai.rever.boss.components.settings.sections

import ai.rever.boss.components.settings.shared.SettingsButtonRow
import ai.rever.boss.components.settings.shared.SettingsSection
import ai.rever.boss.window.LocalWindowId
import ai.rever.boss.window.MenuActionsHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun GettingStartedSettings() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSection(title = "Getting Started") {
            val windowId = LocalWindowId.current
            SettingsButtonRow(
                label = "Plugin Setup Wizard",
                buttonText = "Reopen",
                onClick = {
                    if (windowId != null) {
                        MenuActionsHandler.triggerShowPluginWizard(windowId)
                    }
                },
                description = "Reopen the plugin setup wizard to install or review your workspace tools.",
            )
        }
    }
}
