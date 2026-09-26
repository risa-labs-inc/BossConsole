package ai.rever.boss.keymap

import ai.rever.boss.keymap.model.KeymapSettings
import kotlinx.coroutines.flow.StateFlow

/**
 * Expect declaration for KeymapSettingsManager.
 * Platform-specific implementations handle file I/O.
 */
expect object KeymapSettingsManager {
    val currentSettings: StateFlow<KeymapSettings>

    suspend fun saveSettings()

    suspend fun updateSettings(settings: KeymapSettings)

    suspend fun loadPreset(presetName: String)

    suspend fun resetToDefault()

    suspend fun importFromJson(jsonString: String): KeymapSettings?

    fun exportToJson(): String
}

/** Touching the receiver completes its synchronous startup load before a recovery is claimed. */
internal fun KeymapSettingsManager.ensureLoaded() {
    // Accessing the object receiver already ran its initializer; do not reload settings here.
}
