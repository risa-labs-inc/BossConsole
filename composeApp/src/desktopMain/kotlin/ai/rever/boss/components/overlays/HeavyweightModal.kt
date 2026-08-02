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
 * Dismissal is focus-loss (filtered by [shouldDismissOnFocusLoss]) plus Escape, and the caller's
 * own scrim. Focus-loss alone is not sufficient and not always correct:
 *
 *  - It does NOT fire for a click on the page. Chromium's native child window takes focus without
 *    an AWT focus transition — the same thing [HeavyweightPopup] had to stop relying on. In-page
 *    dismissal therefore depends on the caller drawing its own scrim, which `NewTabDialog` does.
 *    A modal WITHOUT one would not dismiss on an in-page click.
 *  - It fires when a CHILD overlay of the modal takes focus, which must not dismiss. A dropdown
 *    inside the modal is its own always-on-top window; see [shouldDismissOnFocusLoss].
 *
 * Still true: alt-tabbing to another application dismisses, discarding anything typed (e.g. a
 * half-entered URL). Giving this the same scrim treatment as [HeavyweightPopup] would fix that
 * properly, and is the direction if a second caller appears.
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
        // Dismiss when the modal loses focus (clicked elsewhere), matching modal expectations —
        // but NOT when one of our own heavyweight popups took the focus.
        //
        // A modal can host overlays: NewTabDialog's project/folder dropdown is a ContextMenu, which
        // under HARDWARE becomes a HeavyweightPopup, i.e. a separate always-on-top window. Opening
        // it fires this listener, and dismissing here would close the dialog the dropdown belongs
        // to — the user sees the whole New Tab dialog vanish when they expand the folder list. The
        // native directory picker behind "Browse…" is the same shape.
        DisposableEffect(window) {
            val listener =
                object : java.awt.event.WindowFocusListener {
                    override fun windowGainedFocus(e: java.awt.event.WindowEvent?) = Unit

                    override fun windowLostFocus(e: java.awt.event.WindowEvent?) {
                        if (!shouldDismissOnFocusLoss(OverlayConfig.openHeavyweightPopups, e?.oppositeWindow)) return
                        onDismissRequest()
                    }
                }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }
        content()
    }
}

/**
 * Whether a heavyweight modal losing focus should actually dismiss it.
 *
 * Focus loss alone is not enough, because a modal can host its own overlays. Two things suppress
 * the dismissal:
 *
 *  - [openHeavyweightPopups] > 0 — one of our own heavyweight popups (a dropdown or context menu
 *    inside the modal) is open and took the focus. Dismissing would close the dialog the popup
 *    belongs to.
 *  - [oppositeWindow] is a window of this application — the focus went to something else of ours,
 *    such as a native file chooser owned by the app, rather than to another application. A null
 *    opposite window means focus left the app entirely, which IS a real dismiss.
 *
 * Pure so both conditions can be pinned by tests; the composable only supplies the two inputs.
 */
internal fun shouldDismissOnFocusLoss(
    openHeavyweightPopups: Int,
    oppositeWindow: java.awt.Window?,
): Boolean {
    if (openHeavyweightPopups > 0) return false
    return oppositeWindow == null
}
