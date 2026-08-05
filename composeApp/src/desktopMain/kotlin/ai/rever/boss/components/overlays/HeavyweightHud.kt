package ai.rever.boss.components.overlays

import ai.rever.boss.plugin.browser.LocalAwtWindow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window

/**
 * Heavyweight HUD host for HARDWARE_ACCELERATED browser mode.
 *
 * Renders [content] at [alignment] inside a transparent, undecorated, always-on-top window covering
 * the parent window, so a transient status card layers ABOVE the heavyweight JxBrowser surface
 * instead of behind the page. Injected into [OverlayConfig.heavyweightHud]; the Ctrl+Tab MRU
 * switcher is the caller.
 *
 * **`focusable = false` is the whole point, and is what makes this a separate renderer from
 * [HeavyweightModal].** The switcher exists only while Ctrl is held: the moment this window took
 * focus, the key stream driving it would go to the overlay instead of the app and the overlay would
 * never advance or close. For the same reason there is no scrim, no click handling and no
 * focus-loss dismissal here - the HUD has no dismissal of its own, it is shown exactly as long as
 * the caller composes it.
 *
 * Accepted cost: the window covers the parent while it is up, and a non-focusable AWT window still
 * receives mouse events (the JVM has no portable click-through), so a click landing during the hold
 * hits the overlay rather than the app. That window is a keypress long, and the alternative -
 * sizing to the content - cannot be done without knowing the content's size before composing it.
 */
@Composable
fun HeavyweightHud(
    alignment: Alignment,
    content: @Composable () -> Unit,
) {
    val parent = LocalAwtWindow.current
    val bounds = rememberOverlayParentBounds(parent)
    val state = rememberOverlayWindowState(bounds)

    Window(
        onCloseRequest = {},
        state = state,
        undecorated = true,
        transparent = true,
        alwaysOnTop = true,
        focusable = false,
        resizable = false,
    ) {
        EnsureOverlayWindowTransparent(window)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = alignment) {
            content()
        }
    }
}
