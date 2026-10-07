package ai.rever.boss.components.sidebar

import ai.rever.boss.components.overlays.EnsureOverlayWindowTransparent
import ai.rever.boss.components.overlays.OverlayCorner
import ai.rever.boss.components.overlays.OverlayWindow
import ai.rever.boss.components.overlays.trackedContentPaneBounds
import ai.rever.boss.plugin.browser.LocalAwtWindow
import ai.rever.boss.utils.SystemUtils
import ai.rever.boss.window.updateMacSidebarOverlayPresentation
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import kotlin.math.roundToInt

/** Apply this frame's size directly; measuring an animated body adds two stale render frames. */
@Composable
internal actual fun SidebarOverlayWindow(
    size: DpSize,
    region: IntRect,
    content: @Composable () -> Unit,
) {
    val parent = LocalAwtWindow.current
    if (parent == null) {
        // Test/lightweight hosts retain the renderer supplied through OverlayConfig.
        Box { OverlayCorner(Alignment.TopStart, size, regionInWindow = region, content = content) }
        return
    }
    val bounds = trackedContentPaneBounds(parent) ?: return
    val x = bounds[0] + region.left
    val y = bounds[1] + region.top
    val state = remember { WindowState(size = size, position = WindowPosition(x.dp, y.dp)) }
    var nativeWindow by remember { mutableStateOf<java.awt.Window?>(null) }
    val appliedBounds = remember(parent) { Rectangle() }
    // This runs on the owner's frame, rather than waiting for the dialog's separate composition.
    // Keep one state object so a delayed dialog update also reads the newest geometry.
    SideEffect {
        state.size = size
        state.position = WindowPosition(x.dp, y.dp)
        nativeWindow?.let { window ->
            val width =
                size.width.value
                    .roundToInt()
                    .coerceAtLeast(1)
            val height =
                size.height.value
                    .roundToInt()
                    .coerceAtLeast(1)
            val requestedBounds = Rectangle(x, y, width, height)
            if (appliedBounds != requestedBounds) {
                window.bounds = requestedBounds
                appliedBounds.setBounds(requestedBounds)
                if (SystemUtils.isMacOS) {
                    updateMacSidebarOverlayPresentation(
                        (window as? ComposeDialog)?.windowHandle ?: 0L,
                        width,
                    )
                }
            }
        }
    }
    OverlayWindow(onCloseRequest = {}, state = state, focusable = false) { window ->
        SideEffect { nativeWindow = window }
        DisposableEffect(window) {
            val listener =
                object : ComponentAdapter() {
                    override fun componentShown(event: ComponentEvent) {
                        if (SystemUtils.isMacOS) {
                            updateMacSidebarOverlayPresentation(
                                (window as? ComposeDialog)?.windowHandle ?: 0L,
                                window.width,
                            )
                        }
                    }
                }
            window.addComponentListener(listener)
            onDispose { window.removeComponentListener(listener) }
        }
        EnsureOverlayWindowTransparent(window, kind = "sidebar-reveal")
        content()
    }
}
