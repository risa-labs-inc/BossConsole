package ai.rever.boss.platform

import androidx.compose.ui.Modifier

/**
 * Platform-specific cursor utility for implementing different cursor styles
 * across platforms. Used primarily for resize handles.
 */
expect object CursorUtil {
    /**
     * Changes the cursor to a horizontal resize cursor when hovering.
     * Used for vertical dividers that can be dragged horizontally.
     */
    fun Modifier.cursorForHorizontalResize(): Modifier

    /**
     * Changes the cursor to a vertical resize cursor when hovering.
     * Used for horizontal dividers that can be dragged vertically.
     */
    fun Modifier.cursorForVerticalResize(): Modifier
}
