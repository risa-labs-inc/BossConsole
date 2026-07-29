package ai.rever.boss.components.settings.sections

import ai.rever.boss.services.llm.LlmProviderAPIAccess
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * AI provider settings delegated to the plugin that owns them (secret-manager).
 *
 * The host used to implement this section itself, including a hardcoded model list
 * that went stale between releases and a key store that wrote plaintext credentials
 * to disk. All of it — provider registry, credentials, environment-variable
 * resolution, and the live model catalogue — now lives in the plugin, which stores
 * keys as encrypted secrets and fetches model lists from the providers themselves.
 *
 * When the plugin is loaded it renders that panel; before its asynchronous startup
 * registration completes, a short notice renders instead and swaps to the real panel
 * automatically (rememberProvider observes API registration).
 */
@Composable
fun LLMProvidersSettings() {
    val provider = LlmProviderAPIAccess.rememberProvider()
    // supportsSettingsPanel distinguishes "no panel" from "blank panel": the API's panel
    // member has a default no-op, so a plugin that registers without overriding it would
    // otherwise render an empty section with no explanation.
    if (provider != null && provider.supportsSettingsPanel) {
        provider.LlmProviderSettingsPanel(modifier = Modifier.fillMaxSize())
    } else {
        PluginSettingsUnavailableNotice(
            "AI provider settings are provided by the Secret Manager plugin, which isn't loaded yet.",
        )
    }
}
