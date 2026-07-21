package ai.rever.boss.components.plugin.panels.right_top

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.*

/**
 * iOS implementation of environment variable access
 */
actual fun getEnvironmentVariable(name: String): String? {
    return NSProcessInfo.processInfo.environment[name] as? String
}

/**
 * iOS implementation of LLM Settings Manager
 */
actual object LLMSettingsManager {
    private val userDefaults = NSUserDefaults.standardUserDefaults
    private val settingsKey = "llm_settings_json"
    
    actual suspend fun loadSettings() {
        withContext(Dispatchers.Main) {
            try {
                val json = userDefaults.stringForKey(settingsKey)
                json?.let { LLMSettings.loadFromJson(it) }
            } catch (e: Exception) {
                println("Error loading LLM settings: ${e.message}")
            }
        }
    }
    
    actual suspend fun saveSettings() {
        withContext(Dispatchers.Main) {
            try {
                userDefaults.setObject(LLMSettings.toJson(), settingsKey)
                userDefaults.synchronize()
            } catch (e: Exception) {
                println("Error saving LLM settings: ${e.message}")
            }
        }
    }
}
