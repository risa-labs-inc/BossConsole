package ai.rever.boss.components.settings.sections

import ai.rever.boss.services.editor.EditorAPIAccess
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Editor settings delegated to the editor-tab plugin.
 *
 * When the plugin is loaded it renders BossEditor's comprehensive settings
 * panel; before its asynchronous startup registration completes, a short
 * notice renders instead and swaps to the real panel automatically
 * (rememberProvider observes API registration).
 */
@Composable
fun BossEditorSettings() {
    val provider = EditorAPIAccess.rememberProvider()
    if (provider != null) {
        provider.EditorSettingsPanel(modifier = Modifier.fillMaxSize())
    } else {
        PluginSettingsUnavailableNotice("Editor settings are provided by the Code Editor plugin, which isn't loaded yet.")
    }
}
