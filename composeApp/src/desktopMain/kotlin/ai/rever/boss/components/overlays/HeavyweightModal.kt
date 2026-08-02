package ai.rever.boss.components.overlays

import ai.rever.boss.plugin.browser.LocalAwtWindow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState

/**
 * Heavyweight modal host for HARDWARE_ACCELERATED browser mode.
 *
 * Renders [content] (a full-window scrim + centered dialog, e.g. the new-tab dialog) in a separate
 * transparent, undecorated, always-on-top window sized to the parent window, so it layers ABOVE
 * the heavyweight JxBrowser surface instead of behind it. Injected into
 * OverlayConfig.heavyweightModal and only used when OverlayConfig.useHeavyweightPopups is true, so
 * it can't affect the OFF_SCREEN path.
 *
 * Bounds are captured once at open — a modal is transient, so the parent will not move
 * meaningfully while it is up. The logical-px to dp mapping is 1:1 in Compose Desktop, verified on
 * a 150%-scaled Windows display alongside [HeavyweightPopup]; the earlier "may mis-size on HiDPI"
 * caveat here was resolved by that work and has been removed rather than left contradicting it.
 *
 * KNOWN GAP: dismissal is focus-loss plus Escape. Focus-loss is the mechanism [HeavyweightPopup]
 * had to abandon — clicking the browser gives focus to Chromium's native child window without
 * producing an AWT focus transition — so it does not fire for an in-page click here either. This
 * still behaves for the one current caller because `NewTabDialog` draws its own full-size
 * click-dismissing scrim inside the modal window. Two consequences worth knowing before adding a
 * second caller: a modal WITHOUT its own scrim would not dismiss on an in-page click, and
 * alt-tabbing away discards whatever was typed (e.g. a half-entered URL). A scrim like the one in
 * [HeavyweightPopup] is the fix if either becomes a problem.
 */
@Composable
fun HeavyweightModal(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    val parent = LocalAwtWindow.current
    val bounds =
        remember {
            runCatching {
                if (parent != null && parent.isShowing) {
                    val loc = parent.locationOnScreen
                    intArrayOf(loc.x, loc.y, parent.width, parent.height)
                } else {
                    null
                }
            }.getOrNull()
        }
    val state =
        if (bounds != null) {
            rememberWindowState(
                position = WindowPosition(bounds[0].dp, bounds[1].dp),
                size = DpSize(bounds[2].dp, bounds[3].dp),
            )
        } else {
            rememberWindowState(placement = WindowPlacement.Maximized)
        }

    Window(
        onCloseRequest = onDismissRequest,
        state = state,
        undecorated = true,
        transparent = true,
        alwaysOnTop = true,
        focusable = true,
        resizable = false,
        onKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                onDismissRequest()
                true
            } else {
                false
            }
        },
    ) {
        // Dismiss when the modal loses focus (clicked elsewhere), matching modal expectations.
        DisposableEffect(window) {
            val listener =
                object : java.awt.event.WindowFocusListener {
                    override fun windowGainedFocus(e: java.awt.event.WindowEvent?) = Unit

                    override fun windowLostFocus(e: java.awt.event.WindowEvent?) {
                        onDismissRequest()
                    }
                }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }
        content()
    }
}
