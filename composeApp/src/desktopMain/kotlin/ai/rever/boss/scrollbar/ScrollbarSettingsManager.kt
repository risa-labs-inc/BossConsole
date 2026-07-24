@file:Suppress("UNUSED")

package ai.rever.boss.scrollbar

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Re-exports from plugin-scrollbar module for backward compatibility.
 * New code should import directly from ai.rever.boss.plugin.scrollbar
 */

actual object ScrollbarSettingsManager {
    private val delegate = ai.rever.boss.plugin.scrollbar.ScrollbarSettingsManager

    actual val currentSettings: StateFlow<ScrollbarSettings>
        get() = delegate.currentSettings

    actual suspend fun updateSettings(settings: ScrollbarSettings) = delegate.updateSettings(settings)

    actual suspend fun resetToDefault() = delegate.resetToDefault()

    actual fun getDefaultSettings(): ScrollbarSettings = delegate.getDefaultSettings()
}
