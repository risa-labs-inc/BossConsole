package ai.rever.boss.components.plugin.panels.right_top

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android implementation of environment variable access
 * Note: Android doesn't have traditional environment variables, 
 * so we return null and rely on SharedPreferences for API keys
 */
actual fun getEnvironmentVariable(name: String): String? {
    // Android doesn't support environment variables in the traditional sense
    // You could potentially read from BuildConfig or other sources here
    return null
}

/**
 * Android implementation of LLM Settings Manager
 */
actual object LLMSettingsManager {
    private lateinit var context: Context
    private val prefsName = "llm_settings"
    private val settingsKey = "settings_json"
    
    fun init(appContext: Context) {
        context = appContext.applicationContext
    }
    
    actual suspend fun loadSettings() {
        withContext(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                val json = prefs.getString(settingsKey, null)
                json?.let { LLMSettings.loadFromJson(it) }
            } catch (e: Exception) {
                println("Error loading LLM settings: ${e.message}")
            }
        }
    }
    
    actual suspend fun saveSettings() {
        withContext(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                prefs.edit().putString(settingsKey, LLMSettings.toJson()).apply()
            } catch (e: Exception) {
                println("Error saving LLM settings: ${e.message}")
            }
        }
    }
}
