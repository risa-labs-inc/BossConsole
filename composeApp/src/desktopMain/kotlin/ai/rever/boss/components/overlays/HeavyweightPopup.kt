package ai.rever.boss.components.overlays

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState

/**
 * DRAFT — heavyweight overlay window for HARDWARE_ACCELERATED browser mode.
 *
 * Renders [content] in a separate undecorated, transparent, always-on-top window so it layers
 * ABOVE JxBrowser's heavyweight GPU surface (lightweight Compose `Popup`s render behind it). This
 * is injected into [OverlayConfig.heavyweightPopup] by the desktop entry point and is ONLY used
 * when [OverlayConfig.useHeavyweightPopups] is true — i.e. never in the shipping OFF_SCREEN
 * default, so it cannot regress production.
 *
 * Known refinements still needed (require a build/test loop, ideally with BOSS_RENDERING_MODE=
 * HARDWARE so the path is exercised):
 *  - Positioning uses the cursor's screen location. Correct for cursor-driven context menus, but
 *    wrong for widget-anchored dropdowns/tooltips — those need anchor→screen coordinate
 *    conversion (and the px→dp mapping needs verifying on high-DPI displays).
 *  - The fixed window size leaves a transparent, click-capturing margin around the content;
 *    sizing the window to its content would be tighter.
 *  - Dismiss-on-click-outside relies on window focus loss; Escape also dismisses.
 *
 * These are deliberately left for iteration because they can only be validated by running in HW
 * mode and observing real layering/positioning. See benchmarks/speedometer/win/WINDOWS.md for why HARDWARE became the Windows default.
 */
@Composable
fun HeavyweightPopup(
    onDismissRequest: () -> Unit,
    offset: IntOffset,
    focusable: Boolean,
    content: @Composable () -> Unit,
) {
    val position =
        remember {
            val cursor =
                runCatching {
                    java.awt.MouseInfo
                        .getPointerInfo()
                        ?.location
                }.getOrNull()
            if (cursor != null) {
                WindowPosition(cursor.x.dp, cursor.y.dp)
            } else {
                WindowPosition(offset.x.dp, offset.y.dp)
            }
        }
    val state = rememberWindowState(position = position, width = 320.dp, height = 480.dp)

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
        // Dismiss when the popup window loses focus (i.e. the user clicked elsewhere).
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
        content()
    }
}
