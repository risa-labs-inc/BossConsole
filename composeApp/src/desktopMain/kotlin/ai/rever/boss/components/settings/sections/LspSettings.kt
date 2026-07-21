package ai.rever.boss.components.settings.sections

import ai.rever.boss.services.editor.EditorAPIAccess
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * LSP / language-server settings delegated to the editor-tab plugin.
 *
 * The LSP stack lives in BossEditor, which is bundled inside the editor-tab
 * plugin, so the settings UI is rendered by the plugin. Before the plugin's
 * asynchronous startup registration completes, a short notice renders instead
 * and swaps to the real panel automatically (rememberProvider observes API
 * registration).
 */
@Composable
fun LspSettings() {
    val provider = EditorAPIAccess.rememberProvider()
    if (provider != null) {
        provider.LspSettingsPanel(modifier = Modifier.fillMaxSize())
    } else {
        PluginSettingsUnavailableNotice("Language-server settings are provided by the Code Editor plugin, which isn't loaded yet.")
    }
}
