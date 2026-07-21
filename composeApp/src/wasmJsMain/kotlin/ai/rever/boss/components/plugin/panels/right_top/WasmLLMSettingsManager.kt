package ai.rever.boss.components.plugin.panels.right_top

import kotlinx.browser.localStorage
import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * WASM implementation of environment variable access
 * Note: Browser environments don't have access to system environment variables
 */
actual fun getEnvironmentVariable(name: String): String? {
    // In a browser environment, we might read from window object or meta tags
    // For now, return null as browsers don't have traditional env vars
    return null
}

/**
 * WASM implementation of LLM Settings Manager
 */
actual object LLMSettingsManager {
    private const val SETTINGS_KEY = "llm_settings"
    
    actual suspend fun loadSettings() {
        try {
            val json = localStorage.getItem(SETTINGS_KEY)
            json?.let { LLMSettings.loadFromJson(it) }
        } catch (e: Exception) {
            console.error("Error loading LLM settings: ${e.message}")
        }
    }
    
    actual suspend fun saveSettings() {
        try {
            localStorage.setItem(SETTINGS_KEY, LLMSettings.toJson())
        } catch (e: Exception) {
            console.error("Error saving LLM settings: ${e.message}")
        }
    }
}
