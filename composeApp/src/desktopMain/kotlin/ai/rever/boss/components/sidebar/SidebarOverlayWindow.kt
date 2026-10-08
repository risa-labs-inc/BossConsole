package ai.rever.boss.components.sidebar

import ai.rever.boss.components.overlays.EnsureOverlayWindowTransparent
import ai.rever.boss.components.overlays.OverlayCorner
import ai.rever.boss.components.overlays.OverlayWindow
import ai.rever.boss.components.overlays.trackedContentPaneBounds
import ai.rever.boss.plugin.browser.LocalAwtWindow
import ai.rever.boss.utils.SystemUtils
import ai.rever.boss.window.MacSidebarOverlayOwner
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
import androidx.compose.ui.awt.ComposeWindow
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
    val nativeOwner = SystemUtils.isMacOS && parent is ComposeWindow
    // This runs on the owner's frame, rather than waiting for the dialog's separate composition.
    // Keep one state object so a delayed dialog update also reads the newest geometry.
    SideEffect {
        state.size = size
        state.position = WindowPosition(x.dp, y.dp)
        nativeWindow?.let { window ->
            val screenBounds =
                Rectangle(
                    x,
                    y,
                    size.width.value
                        .roundToInt()
                        .coerceAtLeast(1),
                    size.height.value
                        .roundToInt()
                        .coerceAtLeast(1),
                )
            updateSidebarOverlayBounds(window, screenBounds, region, appliedBounds, nativeOwner)
        }
    }
    OverlayWindow(
        onCloseRequest = {},
        state = state,
        focusable = false,
        boundsManagedExternally = nativeOwner,
    ) { window ->
        SideEffect { nativeWindow = window }
        AttachSidebarOverlayOwner(parent, window, nativeOwner)
        EnsureOverlayWindowTransparent(window, kind = "sidebar-reveal")
        content()
    }
}

/** Keep native movement independent of delayed AWT move events and Compose recomposition. */
internal fun updateSidebarOverlayBounds(
    window: java.awt.Window,
    screenBounds: Rectangle,
    region: IntRect,
    appliedBounds: Rectangle,
    nativeOwner: Boolean,
) {
    val requested = Rectangle(screenBounds)
    if (nativeOwner) requested.setLocation(region.left, region.top)
    if (appliedBounds == requested) return
    val sameOrigin = appliedBounds.x == region.left && appliedBounds.y == region.top
    if (nativeOwner && appliedBounds.width > 0 && sameOrigin) {
        window.setSize(screenBounds.width, screenBounds.height)
    } else {
        window.bounds = screenBounds
    }
    appliedBounds.setBounds(requested)
    if (SystemUtils.isMacOS) {
        updateMacSidebarOverlayPresentation((window as? ComposeDialog)?.windowHandle ?: 0L, screenBounds.width)
    }
}

@Composable
private fun AttachSidebarOverlayOwner(
    parent: java.awt.Window,
    window: java.awt.Window,
    nativeOwner: Boolean,
) {
    DisposableEffect(parent, window) {
        var owner: MacSidebarOverlayOwner? = null

        fun attach() {
            val parentHandle = (parent as? ComposeWindow)?.windowHandle ?: 0L
            val childHandle = (window as? ComposeDialog)?.windowHandle ?: 0L
            if (nativeOwner && parentHandle != 0L && childHandle != 0L) {
                if (owner == null) owner = MacSidebarOverlayOwner(parentHandle, childHandle)
                owner.attach()
            }
        }
        val listener =
            object : ComponentAdapter() {
                override fun componentShown(event: ComponentEvent) {
                    attach()
                    if (SystemUtils.isMacOS) {
                        val handle = (window as? ComposeDialog)?.windowHandle ?: 0L
                        updateMacSidebarOverlayPresentation(handle, window.width)
                    }
                }
            }
        window.addComponentListener(listener)
        attach()
        onDispose {
            window.removeComponentListener(listener)
            owner?.close()
        }
    }
}
