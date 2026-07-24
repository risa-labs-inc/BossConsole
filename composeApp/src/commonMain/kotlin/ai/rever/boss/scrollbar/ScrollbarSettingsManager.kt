@file:Suppress("UNUSED")

package ai.rever.boss.scrollbar

import kotlinx.coroutines.flow.StateFlow

/**
 * Re-exports from plugin-scrollbar module for backward compatibility.
 * New code should import directly from ai.rever.boss.plugin.scrollbar
 */

// Note: We can't typealias an object, so we delegate to it
expect object ScrollbarSettingsManager {
    val currentSettings: StateFlow<ScrollbarSettings>

    suspend fun updateSettings(settings: ScrollbarSettings)

    suspend fun resetToDefault()

    fun getDefaultSettings(): ScrollbarSettings
}
