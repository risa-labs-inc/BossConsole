package ai.rever.boss.platform

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import java.awt.Cursor

/**
 * Desktop (JVM) implementation of CursorUtil using Compose's pointerHoverIcon.
 *
 * Uses Compose's cursor system instead of directly manipulating AWT cursors,
 * which prevents interference with heavyweight components like JxBrowser.
 */
actual object CursorUtil {
    /**
     * Changes the cursor to a horizontal resize cursor when hovering.
     */
    actual fun Modifier.cursorForHorizontalResize(): Modifier =
        this.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))

    /**
     * Changes the cursor to a vertical resize cursor when hovering.
     */
    actual fun Modifier.cursorForVerticalResize(): Modifier =
        this.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)))
}
