package ai.rever.boss.platform

import androidx.compose.ui.Modifier

/**
 * Web implementation of CursorUtil.
 * Uses CSS cursor styles via a JS interop function.
 */
actual object CursorUtil {
    // CSS cursor property value for horizontal resize
    actual fun Modifier.cursorForHorizontalResize(): Modifier {
        // In a real implementation, this would use JS interop to set CSS cursor
        return this
    }

    // CSS cursor property value for vertical resize
    actual fun Modifier.cursorForVerticalResize(): Modifier {
        // In a real implementation, this would use JS interop to set CSS cursor
        return this
    }
}
