package ai.rever.boss.components.settings.sections

import ai.rever.boss.services.terminal.TerminalAPIAccess
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Terminal settings delegated to the terminal-tab plugin.
 *
 * When the plugin is loaded it renders BossTerm's comprehensive settings panel.
 * When absent, the composable is a no-op (graceful degradation).
 */
@Composable
fun TerminalSettings() {
    TerminalAPIAccess.TerminalSettingsPanel(modifier = Modifier.fillMaxSize())
}
