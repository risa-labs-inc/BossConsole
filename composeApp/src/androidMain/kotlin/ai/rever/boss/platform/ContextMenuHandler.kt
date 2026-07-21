package ai.rever.boss.platform

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset

/**
 * Android implementation of ContextMenuHandler using long press detection.
 */
actual class ContextMenuHandler {

    /**
     * Android implementation uses long press for context menu activation.
     */
    actual fun Modifier.applyContextMenuBehavior(
        showMenu: Boolean,
        setShowMenu: (Boolean) -> Unit,
        setMenuPosition: (IntOffset) -> Unit
    ): Modifier = composed {
        pointerInput(Unit) {
            detectTapGestures(
                onLongPress = { offset ->
                    setMenuPosition(IntOffset(offset.x.toInt(), offset.y.toInt()))
                    setShowMenu(true)
                },
                onTap = {
                    if (showMenu) {
                        setShowMenu(false)
                    }
                }
            )
        }
    }

    actual fun getInstructionText(): String =
        "Long press anywhere to see the context menu"
}
