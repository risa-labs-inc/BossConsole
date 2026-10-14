@file:Suppress("PackageNaming") // Matches the existing common/actual package.

package ai.rever.boss.components.window_panel.components.main_window_panels

import ai.rever.boss.plugin.browser.BrowserPagePressEvents
import ai.rever.boss.plugin.browser.LocalAwtWindow
import ai.rever.boss.plugin.window.LocalWindowId
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.delay
import java.awt.AWTEvent
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities

@Composable
internal actual fun sidebarNativePointerTrackingAvailable(): Boolean = LocalAwtWindow.current is RootPaneContainer

/** AWT logical screen coordinates are dp, including on HiDPI displays. */
@Composable
actual fun TrackTabBarRevealPointer(
    state: TabBarRevealState,
    enabled: Boolean,
    region: IntRect?,
    sidebarWidth: Dp,
) {
    val parent = LocalAwtWindow.current
    val direction = LocalLayoutDirection.current
    TrackSidebarMainPanelPresses(state, enabled, region, sidebarWidth)
    LaunchedEffect(parent, enabled, region, sidebarWidth, direction) {
        state.pointerAtEdge = false
        state.pointerInRevealArea = false
        if (!enabled || region == null || parent == null) return@LaunchedEffect
        var previousX: Int? = null
        try {
            while (true) {
                val pane = (parent as? RootPaneContainer)?.contentPane
                val origin =
                    runCatching {
                        if (pane?.isShowing == true) pane.locationOnScreen else null
                    }.getOrNull()
                val cursor = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
                val windowTracksPointer = parent.isActive || state.drawerVisible
                if (origin != null && cursor != null && windowTracksPointer) {
                    val x = cursor.x - origin.x
                    val y = cursor.y - origin.y
                    // Activation belongs to the WINDOW edge, regardless of the plugin strip
                    // or panel columns before the tab sidebar. Retention uses its actual right edge.
                    val windowRegion = IntRect(0, 0, pane!!.width, pane.height)
                    state.pointerAtEdge = pointerReachesSidebarEdge(x, y, previousX, windowRegion, direction)
                    state.pointerInRevealArea =
                        pointerWithinSidebarMargin(
                            x,
                            windowRegion,
                            if (direction == LayoutDirection.Ltr) {
                                region.left + sidebarWidth.value
                            } else {
                                windowRegion.right - region.right + sidebarWidth.value
                            },
                            direction,
                        )
                    previousX = x
                } else {
                    state.pointerAtEdge = false
                    state.pointerInRevealArea = false
                    previousX = null
                }
                delay(SIDEBAR_POINTER_SAMPLE_INTERVAL_MS)
            }
        } finally {
            state.pointerAtEdge = false
            state.pointerInRevealArea = false
        }
    }
}

/** Observe without consuming, so the very click that closes the reveal still reaches the panel. */
@Composable
private fun TrackSidebarMainPanelPresses(
    state: TabBarRevealState,
    enabled: Boolean,
    region: IntRect?,
    sidebarWidth: Dp,
) {
    val parent = LocalAwtWindow.current
    val windowId = LocalWindowId.current
    LaunchedEffect(windowId, enabled, state) {
        if (!enabled || windowId == null) return@LaunchedEffect
        BrowserPagePressEvents.presses.collect { id ->
            if (id == windowId) state.dismissFromMainPanel()
        }
    }
    val direction by rememberUpdatedState(LocalLayoutDirection.current)
    val currentRegion by rememberUpdatedState(region)
    val currentWidth by rememberUpdatedState(sidebarWidth)
    val currentState by rememberUpdatedState(state)
    DisposableEffect(parent, enabled) {
        fun press(at: Point) {
            val pane = (parent as? RootPaneContainer)?.contentPane
            val origin = runCatching { pane?.takeIf { it.isShowing }?.locationOnScreen }.getOrNull()
            val bounds = currentRegion
            if (origin != null && bounds != null) {
                currentState.dismissMainPanelPress(at.x - origin.x, at.y - origin.y, bounds, currentWidth, direction)
            }
        }
        val listener =
            AWTEventListener { event ->
                if (event is MouseEvent && event.id == MouseEvent.MOUSE_PRESSED &&
                    SwingUtilities.getWindowAncestor(event.component) === parent
                ) {
                    press(event.locationOnScreen)
                }
            }
        val toolkit = Toolkit.getDefaultToolkit()
        val listening = enabled && parent is RootPaneContainer
        if (listening) toolkit.addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
        onDispose {
            if (listening) toolkit.removeAWTEventListener(listener)
        }
    }
}
