package ai.rever.boss.components.overlays

import ai.rever.boss.plugin.browser.LocalAwtWindow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState

/**
 * Heavyweight overlay window for HARDWARE_ACCELERATED browser mode.
 *
 * Renders [content] in a separate undecorated, transparent, always-on-top window so it layers
 * ABOVE JxBrowser's heavyweight GPU surface (a lightweight Compose `Popup` renders behind it).
 * Injected into [OverlayConfig.heavyweightPopup] by the desktop entry point and used ONLY when
 * [OverlayConfig.useHeavyweightPopups] is true — never under OFF_SCREEN, so macOS and Linux keep
 * the ordinary Compose popup path.
 *
 * **The window is sized to the parent window, not to the content**, and its empty area dismisses
 * on click. That is deliberate, and replaces two things that did not work in the version this was
 * ported from (BossConsoleLite, where both were flagged as draft):
 *
 *  - *Dismiss-on-click-outside used to rely on the popup window losing focus.* Clicking the
 *    browser gives focus to Chromium's native child window, which does not produce the AWT focus
 *    transition the listener waits for, so a left-click simply left the menu on screen. Observed
 *    directly on Windows. A parent-sized scrim gets a real mouse event instead, so dismissal no
 *    longer depends on focus semantics at all.
 *  - *The old fixed 320x480 window left a transparent margin around the content that swallowed
 *    clicks* — clicking just outside the menu hit an invisible window and did nothing. Now that
 *    area is the scrim, so clicking it does the thing the user meant.
 *
 * The focus-loss listener is kept as a secondary path (it still fires when another application is
 * activated), and Escape still dismisses.
 *
 * Positioning: see [contentOffsetFor]. A caller-supplied anchor is honoured when there is one
 * (widget-anchored dropdowns open under their widget), and the cursor is used otherwise, which is
 * what a right-click context menu wants.
 *
 * See benchmarks/speedometer/win/WINDOWS.md for why HARDWARE is the Windows default.
 */
@Composable
fun HeavyweightPopup(
    onDismissRequest: () -> Unit,
    offset: IntOffset,
    focusable: Boolean,
    content: @Composable () -> Unit,
) {
    val parent = LocalAwtWindow.current

    // Parent bounds and cursor are captured once, at open: a menu is transient, so the window
    // will not move meaningfully while it is up, and re-reading them would make the content
    // drift. AWT reports these in logical units, which map 1:1 to dp in Compose Desktop — so
    // everything below stays in dp and never touches physical pixels or the density factor.
    val bounds =
        remember {
            runCatching {
                parent?.takeIf { it.isShowing }?.let {
                    val at = it.locationOnScreen
                    intArrayOf(at.x, at.y, it.width, it.height)
                }
            }.getOrNull()
        }
    val cursor =
        remember {
            runCatching {
                java.awt.MouseInfo
                    .getPointerInfo()
                    ?.location
            }.getOrNull()
        }

    val state =
        if (bounds != null) {
            rememberWindowState(
                position = WindowPosition(bounds[0].dp, bounds[1].dp),
                size = DpSize(bounds[2].dp, bounds[3].dp),
            )
        } else {
            // No resolvable parent (should not happen in-app). Cover the screen rather than
            // falling back to a small window, so the scrim still catches the dismissing click.
            rememberWindowState(placement = WindowPlacement.Maximized)
        }

    // Where the content sits INSIDE the overlay window.
    val contentOffset = contentOffsetFor(cursor?.x, cursor?.y, offset, bounds?.get(0), bounds?.get(1))

    Window(
        onCloseRequest = onDismissRequest,
        state = state,
        undecorated = true,
        transparent = true,
        alwaysOnTop = true,
        focusable = focusable,
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
        // Secondary dismissal: covers switching to another application, which the scrim cannot
        // see because the click never reaches this process.
        if (focusable) {
            DisposableEffect(window) {
                val listener =
                    object : java.awt.event.WindowFocusListener {
                        override fun windowGainedFocus(e: java.awt.event.WindowEvent?) {}

                        override fun windowLostFocus(e: java.awt.event.WindowEvent?) {
                            onDismissRequest()
                        }
                    }
                window.addWindowFocusListener(listener)
                onDispose { window.removeWindowFocusListener(listener) }
            }
        }

        // The scrim. Transparent, but it receives the click — which is the whole point.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onDismissRequest() },
        ) {
            Box(
                modifier =
                    Modifier
                        .absoluteOffset(x = contentOffset.x.dp, y = contentOffset.y.dp)
                        // Swallow clicks that land on the menu's own background or padding.
                        // Without this they fall through to the scrim above and dismiss the menu
                        // out from under a user who was aiming at an item.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { },
            ) {
                content()
            }
        }
    }
}

/**
 * Where [HeavyweightPopup]'s content sits inside its parent-sized overlay window.
 *
 * Pure and separated out because the two inputs live in DIFFERENT coordinate spaces, which is easy
 * to get wrong and invisible in a screenshot until it lands off-screen:
 *
 *  - [anchor] (the caller's `offset`) is already **window-local** — callers compute it from a
 *    widget's position, e.g. BossTabButton passes the button's position plus its height so the
 *    menu opens *under the button*. It must be used as-is.
 *  - [cursorX]/[cursorY] are **screen** coordinates from `MouseInfo`, so they need the window
 *    origin subtracted.
 *
 * A caller-supplied anchor wins when it is non-zero: a widget-anchored dropdown should open under
 * its widget, not at the pointer (the pointer is on the widget, but not at its corner). The cursor
 * is used for [IntOffset.Zero], which is what a right-click context menu passes — there the
 * pointer IS the intended location.
 */
internal fun contentOffsetFor(
    cursorX: Int?,
    cursorY: Int?,
    anchor: IntOffset,
    windowX: Int?,
    windowY: Int?,
): IntOffset {
    if (anchor != IntOffset.Zero) return anchor
    if (cursorX == null || cursorY == null) return anchor
    return IntOffset(cursorX - (windowX ?: 0), cursorY - (windowY ?: 0))
}
