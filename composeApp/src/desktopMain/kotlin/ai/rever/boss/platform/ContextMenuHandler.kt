package ai.rever.boss.platform

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset

/**
 * Desktop implementation of ContextMenuHandler using right-click detection.
 */
@OptIn(ExperimentalComposeUiApi::class)
actual class ContextMenuHandler {

    /**
     * Desktop implementation uses right-click as primary activation method
     * with long press as fallback for touchpads.
     */
    actual fun Modifier.applyContextMenuBehavior(
        showMenu: Boolean,
        setShowMenu: (Boolean) -> Unit,
        setMenuPosition: (IntOffset) -> Unit
    ): Modifier = composed {
        pointerInput(Unit) {
            // For desktop platforms, detect right-click
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)

                    // Handle right-click (secondary button only, not back/forward buttons)
                    val pointerPress = event.changes.find { it.type == PointerType.Companion.Mouse }
                    if (event.type == PointerEventType.Companion.Press &&
                        event.buttons.isSecondaryPressed &&
                        pointerPress != null) {

                        setMenuPosition(
                            IntOffset(
                                pointerPress.position.x.toInt(),
                                pointerPress.position.y.toInt()
                            )
                        )
                        setShowMenu(true)
                        pointerPress.consume()
                    }

                    // Handle left-click to dismiss menu
                    if (showMenu && event.type == PointerEventType.Companion.Press &&
                        event.buttons.isPrimaryPressed) {
                        setShowMenu(false)
                    }
                }
            }
        }
    }

}
