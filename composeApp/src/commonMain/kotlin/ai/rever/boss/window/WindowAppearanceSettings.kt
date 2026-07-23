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
    val showTitleBar: Boolean = true,

    /**
     * How tabs in the main (top) tab bar are sized.
     * Default: SHRINK_TO_FIT (Safari behaviour)
     */
    val tabWidthMode: TabWidthMode = TabWidthMode.SHRINK_TO_FIT
)

/**
 * Sizing behaviour for top tabs in the main tab bar.
 */
@Serializable
enum class TabWidthMode {
    /**
     * Tabs shrink uniformly to fit the available bar width (Safari behaviour).
     * The row only scrolls once each tab has hit its favicon-sized floor.
     */
    SHRINK_TO_FIT,

    /**
     * Tabs take their content-driven width (clamped to 180–450 dp) and the
     * row scrolls as soon as they overflow the bar.
     */
    FIXED
}
