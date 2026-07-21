package ai.rever.boss.window

import kotlinx.serialization.Serializable

/**
 * Settings for window appearance customization
 */
@Serializable
data class WindowAppearanceSettings(
    /**
     * Whether to show the Boss Console title bar
     * Default: true on macOS, false on Linux/Windows
     */
    val showTitleBar: Boolean = true
)
