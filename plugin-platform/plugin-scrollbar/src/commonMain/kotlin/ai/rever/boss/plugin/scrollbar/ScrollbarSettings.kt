package ai.rever.boss.plugin.scrollbar

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable

/**
 * Standard scrollbar thickness values used throughout the application.
 * These provide consistent sizing for different contexts.
 */
object ScrollbarDimensions {
    /** Thickness for panel scrollbars (Console, Git panels, sidebar panels) */
    val PANEL_THICKNESS = 6.dp

    /** Thickness for horizontal chrome bars (Tab Bar, Bottom Bar) */
    val BAR_THICKNESS = 2.dp

    /** Minimum allowed thickness */
    val MIN_THICKNESS = 2.dp

    /** Maximum allowed thickness */
    val MAX_THICKNESS = 16.dp
}

/**
 * User-configurable scrollbar settings.
 * These settings affect the visual appearance and behavior of scrollbars
 * throughout the application.
 */
@Serializable
data class ScrollbarSettings(
    /**
     * Thickness of panel scrollbars in dp (Console, Git panels, sidebar panels).
     * Default: 6dp
     */
    val panelThickness: Int = 6,

    /**
     * Thickness of horizontal bar scrollbars in dp (Tab Bar, Bottom Bar).
     * Default: 2dp
     */
    val barThickness: Int = 2,

    /**
     * Whether to always show scrollbars or only when scrolling.
     * Default: false (show only when scrolling)
     */
    val alwaysShowScrollbars: Boolean = false,

    /**
     * Fade delay in milliseconds before scrollbar fades out after scrolling stops.
     * Default: 1500ms
     */
    val fadeDelayMs: Int = 1500,

    /**
     * Fade duration in milliseconds for scrollbar fade animation.
     * Default: 500ms
     */
    val fadeDurationMs: Int = 500
) {
    /**
     * Get panel thickness as Dp
     */
    val panelThicknessDp: Dp get() = panelThickness.dp

    /**
     * Get bar thickness as Dp
     */
    val barThicknessDp: Dp get() = barThickness.dp
}
