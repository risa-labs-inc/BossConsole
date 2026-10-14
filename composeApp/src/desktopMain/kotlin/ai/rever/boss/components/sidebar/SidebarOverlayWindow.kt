package ai.rever.boss.components.sidebar

import ai.rever.boss.components.overlays.EnsureOverlayWindowTransparent
import ai.rever.boss.components.overlays.OverlayCorner
import ai.rever.boss.components.overlays.OverlayWindow
import ai.rever.boss.components.overlays.trackedContentPaneBounds
import ai.rever.boss.plugin.browser.LocalAwtWindow
import ai.rever.boss.utils.SystemUtils
import ai.rever.boss.window.MacSidebarOverlayOwner
import ai.rever.boss.window.SidebarOverlayAnchor
import ai.rever.boss.window.updateMacSidebarOverlayPresentation
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

/** Apply this frame's size directly; measuring an animated body adds two stale render frames. */
@Composable
internal actual fun SidebarOverlayWindow(
    size: DpSize,
    region: IntRect,
    bottomInset: Dp,
    rightInset: Dp?,
    content: @Composable () -> Unit,
) {
    val parent = LocalAwtWindow.current
    if (parent == null) {
        // Test/lightweight hosts retain the renderer supplied through OverlayConfig.
        Box { OverlayCorner(Alignment.TopStart, size, regionInWindow = region, content = content) }
        return
    }
    val bounds = trackedContentPaneBounds(parent) ?: return
    val x = bounds[0] + if (rightInset == null) region.left else region.right - size.width.value.roundToInt()
    val y = bounds[1] + region.top
    val state = remember { WindowState(size = size, position = WindowPosition(x.dp, y.dp)) }
    var nativeWindow by remember(parent) { mutableStateOf<java.awt.Window?>(null) }
    val appliedBounds = remember(parent) { Rectangle() }
    val nativeOwner = SystemUtils.isMacOS && parent is ComposeWindow
    var controller by remember(parent) { mutableStateOf<MacSidebarOverlayOwner?>(null) }
    val paneOrigin =
        (parent as? ComposeWindow)?.let {
            SwingUtilities.convertPoint(it.contentPane, 0, 0, it)
        }
    val anchor =
        SidebarOverlayAnchor(
            (region.left + (paneOrigin?.x ?: 0)).toDouble(),
            (region.top + (paneOrigin?.y ?: 0)).toDouble(),
            (bottomInset.value + parent.insets.bottom).toDouble(),
            size.width.value.toDouble(),
            rightInset?.let { (it.value + parent.insets.right).toDouble() },
        )
    // This runs on the owner's frame, rather than waiting for the dialog's separate composition.
    // Keep one state object so a delayed dialog update also reads the newest geometry.
    SideEffect {
        state.size = size
        state.position = WindowPosition(x.dp, y.dp)
        nativeWindow?.takeIf { it.isDisplayable }?.let { window ->
            val screenBounds = sidebarScreenBounds(x, y, size)
            if (nativeOwner) {
                controller?.updateGeometry(anchor)
                updateMacSidebarOverlayPresentation((window as? ComposeDialog)?.windowHandle ?: 0L, screenBounds.width)
            } else {
                updateSidebarOverlayBounds(window, screenBounds, appliedBounds)
            }
        }
    }
    OverlayWindow(
        onCloseRequest = {},
        state = state,
        focusable = false,
        boundsManagedExternally = nativeOwner,
    ) { window ->
        DisposableEffect(window) {
            nativeWindow = window
            onDispose {
                if (nativeWindow === window) {
                    nativeWindow = null
                    controller = null
                }
            }
        }
        AttachSidebarOverlayOwner(parent, window, nativeOwner, anchor) { controller = it }
        EnsureOverlayWindowTransparent(window, kind = "sidebar-reveal")
        content()
    }
}

private fun sidebarScreenBounds(
    x: Int,
    y: Int,
    size: DpSize,
): Rectangle =
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

/** Non-native hosts continue to apply screen bounds through AWT. */
internal fun updateSidebarOverlayBounds(
    window: java.awt.Window,
    screenBounds: Rectangle,
    appliedBounds: Rectangle,
) {
    if (!window.isDisplayable || appliedBounds == screenBounds) return
    window.bounds = screenBounds
    appliedBounds.setBounds(screenBounds)
    if (SystemUtils.isMacOS) {
        updateMacSidebarOverlayPresentation((window as? ComposeDialog)?.windowHandle ?: 0L, screenBounds.width)
    }
}

@Composable
private fun AttachSidebarOverlayOwner(
    parent: java.awt.Window,
    window: java.awt.Window,
    nativeOwner: Boolean,
    anchor: SidebarOverlayAnchor,
    onAttach: (MacSidebarOverlayOwner) -> Unit,
) {
    val currentAnchor by rememberUpdatedState(anchor)
    val currentOnAttach by rememberUpdatedState(onAttach)
    DisposableEffect(parent, window) {
        var owner: MacSidebarOverlayOwner? = null

        fun attach() {
            val parentHandle = (parent as? ComposeWindow)?.windowHandle ?: 0L
            val childHandle = (window as? ComposeDialog)?.windowHandle ?: 0L
            if (nativeOwner && parentHandle != 0L && childHandle != 0L) {
                val attached = owner ?: MacSidebarOverlayOwner(parentHandle, childHandle).also { owner = it }
                attached.attach()
                attached.updateGeometry(currentAnchor)
                currentOnAttach(attached)
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
