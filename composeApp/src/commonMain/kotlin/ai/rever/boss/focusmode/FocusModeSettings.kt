package ai.rever.boss.focusmode

import kotlinx.serialization.Serializable

/**
 * Configuration for Focus Mode feature.
 * Focus Mode minimizes distractions by hiding UI chrome (top bar, sidebars, bottom bar)
 * while keeping tabs and main content visible.
 *
 * @property enabled Whether focus mode is currently active
 * @property autoRevealEnabled Whether to auto-reveal hidden bars on mouse hover at edges
 * @property revealOffsetPx Distance in pixels from window edge to trigger auto-reveal
 * @property revealDelayMs Delay in milliseconds before reveal triggers after hovering at edge
 */
@Serializable
data class FocusModeSettings(
    val enabled: Boolean = false,
    val autoRevealEnabled: Boolean = true,
    val revealOffsetPx: Float = 30f,
    val revealDelayMs: Long = 500L,
)
