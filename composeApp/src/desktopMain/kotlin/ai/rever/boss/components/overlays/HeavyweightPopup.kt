package ai.rever.boss.components.overlays

import ai.rever.boss.plugin.browser.LocalAwtWindow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
 * Positioning: see [contentOffsetFor]. The cursor wins; the caller's offset is a fallback, because
 * callers compute it for the lightweight `Popup` branch and it is parent-layout-relative rather
 * than window-relative.
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
        RegisterOpenPopup()

        if (focusable) {
            DismissOnFocusLoss(window, onDismissRequest)
        }

        ScrimmedContent(
            contentOffset = contentOffset,
            windowWidth = bounds?.get(2),
            windowHeight = bounds?.get(3),
            onDismissRequest = onDismissRequest,
            content = content,
        )
    }
}

/**
 * Count this popup as open for as long as it is composed.
 *
 * A [HeavyweightModal] underneath needs to tell "the user clicked away" from "a child overlay of
 * mine took focus" — a dropdown inside a modal is its own always-on-top window, so opening it fires
 * the modal's `windowLostFocus` and would otherwise dismiss the dialog the dropdown belongs to.
 * See `shouldDismissOnFocusLoss`.
 */
@Composable
private fun RegisterOpenPopup() {
    DisposableEffect(Unit) {
        OverlayConfig.openHeavyweightPopups++
        onDispose { OverlayConfig.openHeavyweightPopups-- }
    }
}

/**
 * Dismiss when [window] loses focus.
 *
 * SECONDARY to the scrim, not the primary mechanism: it covers switching to another application,
 * which the scrim cannot see because that click never reaches this process. It does NOT fire for a
 * click on the browser — Chromium's native child window takes focus without producing an AWT focus
 * transition — which is exactly why the scrim exists.
 */
@Composable
private fun DismissOnFocusLoss(
    window: java.awt.Window,
    onDismissRequest: () -> Unit,
) {
    DisposableEffect(window) {
        val listener =
            object : java.awt.event.WindowFocusListener {
                // Only the loss matters here; gaining focus needs no action.
                override fun windowGainedFocus(e: java.awt.event.WindowEvent?) = Unit

                override fun windowLostFocus(e: java.awt.event.WindowEvent?) {
                    onDismissRequest()
                }
            }
        window.addWindowFocusListener(listener)
        onDispose { window.removeWindowFocusListener(listener) }
    }
}

/**
 * The scrim plus the positioned menu, split out of [HeavyweightPopup] to keep that composable
 * readable — it is otherwise window setup and content placement interleaved.
 *
 * The scrim is transparent but *receives the click*, which is the whole reason the overlay window
 * is parent-sized rather than content-sized.
 */
@Composable
private fun ScrimmedContent(
    contentOffset: IntOffset,
    windowWidth: Int?,
    windowHeight: Int?,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    // Clamp the menu inside the overlay window once its size is known.
    //
    // The window is exactly parent-sized and the content is placed at a raw offset, so a
    // right-click near the bottom or right edge would draw the menu partly outside it and get
    // clipped — worst for a tall submenu near the bottom. A lightweight Popup does this for you;
    // this path has to do it itself. Same shape as SwingTooltip's monitor clamp: measure, coerce.
    //
    // Measured rather than assumed because menu height varies with item count. The first frame
    // draws unclamped and corrects on the next, which is invisible at menu-open latency and beats
    // guessing a size.
    //
    // NOTE the unit conversion: onGloballyPositioned reports PIXELS, while the window bounds and
    // the offsets are AWT logical units (== dp). Those differ by 1.5x on a 150%-scaled display, so
    // clamping raw px against dp would be wrong by half a menu.
    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val contentWidthDp =
        with(density) {
            contentSize.width
                .toDp()
                .value
                .toInt()
        }
    val contentHeightDp =
        with(density) {
            contentSize.height
                .toDp()
                .value
                .toInt()
        }
    val clamped =
        if (windowWidth == null || windowHeight == null || contentSize == IntSize.Zero) {
            contentOffset
        } else {
            IntOffset(
                contentOffset.x.coerceIn(0, (windowWidth - contentWidthDp).coerceAtLeast(0)),
                contentOffset.y.coerceIn(0, (windowHeight - contentHeightDp).coerceAtLeast(0)),
            )
        }

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
                    .absoluteOffset(x = clamped.x.dp, y = clamped.y.dp)
                    .onGloballyPositioned { contentSize = it.size }
                    // Swallow clicks that land on the menu's own background or padding. Without
                    // this they fall through to the scrim and dismiss the menu out from under a
                    // user who was aiming at an item.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {},
        ) {
            content()
        }
    }
}

/**
 * Where [HeavyweightPopup]'s content sits inside its parent-sized overlay window.
 *
 * **The cursor is preferred, and the caller's [anchor] is only a fallback.** That is the opposite
 * of what a `Popup` does, and it is deliberate — the two are in different coordinate spaces:
 *
 *  - [cursorX]/[cursorY] are **screen** coordinates. The overlay window is positioned at the parent
 *    window's `locationOnScreen` and is undecorated, so its content origin IS that point, and
 *    screen-minus-window-origin lands exactly right.
 *  - [anchor] is whatever the caller computed for the lightweight `Popup` branch, which positions
 *    relative to its **parent layout node** — not to the window. Every caller today reflects that:
 *    `BossTabButton` and `BossActionButton` pass `positionInParent()`, and the
 *    `Modifier.contextMenu` path passes the pointer's *element-local* position. Treating any of
 *    those as window-local displaces the menu by however far the widget's parent sits from the
 *    window origin — which is why an earlier revision of this function, which preferred the
 *    anchor, was wrong.
 *
 * So the anchor is used only when there is no cursor (a keyboard-invoked menu), where being
 * approximately near the widget beats not appearing.
 *
 * KNOWN GAP: because the cursor wins, a widget-anchored dropdown opens at the pointer rather than
 * flush under its button. Usually close enough — the pointer is on the button — but visibly off for
 * down-arrow dropdowns. Fixing it properly means callers passing window-space coordinates
 * (`positionInWindow()`, `localToWindow()`), not reinterpreting what they pass today.
 */
internal fun contentOffsetFor(
    cursorX: Int?,
    cursorY: Int?,
    anchor: IntOffset,
    windowX: Int?,
    windowY: Int?,
): IntOffset =
    if (cursorX != null && cursorY != null) {
        IntOffset(cursorX - (windowX ?: 0), cursorY - (windowY ?: 0))
    } else {
        anchor
    }
