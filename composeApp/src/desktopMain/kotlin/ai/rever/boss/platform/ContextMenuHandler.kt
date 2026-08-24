package ai.rever.boss.platform

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEvent
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
        setMenuPosition: (IntOffset) -> Unit,
    ): Modifier =
        composed {
            pointerInput(Unit) {
                // For desktop platforms, detect right-click
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)

                        // Handle right-click (secondary button only, not back/forward buttons)
                        val pointerPress = event.changes.find { it.type == PointerType.Companion.Mouse }
                        // A press something nearer the pointer already claimed is not ours.
                        //
                        // This runs on the MAIN pass, which travels child to parent, so a menu
                        // attached to a container sees every right-click inside it - including
                        // the ones its children opened their own menus for. Without this the tab
                        // bar's own menu opened on top of a tab's, and the bar's is the one
                        // drawn last. What looked like "the tab menu is flaky" was two menus,
                        // with the wrong one winning.
                        if (event.isSecondaryPress() && pointerPress != null && !pointerPress.isConsumed) {
                            setMenuPosition(
                                IntOffset(
                                    pointerPress.position.x.toInt(),
                                    pointerPress.position.y.toInt(),
                                ),
                            )
                            setShowMenu(true)
                            pointerPress.consume()
                        }

                        // Handle left-click to dismiss menu
                        if (showMenu && event.type == PointerEventType.Companion.Press &&
                            event.buttons.isPrimaryPressed
                        ) {
                            setShowMenu(false)
                        }
                    }
                }
            }
        }
}

/** A mouse-button-two press, as opposed to a move, a release or a left-click. */
private fun PointerEvent.isSecondaryPress(): Boolean = type == PointerEventType.Press && buttons.isSecondaryPressed
