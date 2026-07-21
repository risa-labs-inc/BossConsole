package ai.rever.boss.platform

import androidx.compose.ui.Modifier

/**
 * Android implementation of CursorUtil.
 * Android doesn't support changing mouse cursors in the same way, so this is a no-op implementation.
 */
actual object CursorUtil {
    actual fun Modifier.cursorForHorizontalResize(): Modifier = this
    actual fun Modifier.cursorForVerticalResize(): Modifier = this
}
