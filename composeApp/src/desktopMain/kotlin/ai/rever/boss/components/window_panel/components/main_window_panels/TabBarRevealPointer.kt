package ai.rever.boss.components.window_panel.components.main_window_panels

import ai.rever.boss.plugin.browser.LocalAwtWindow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import java.awt.MouseInfo
import javax.swing.RootPaneContainer
import kotlinx.coroutines.delay

/** AWT logical screen coordinates are dp, including on HiDPI displays. */
@Composable
actual fun TrackTabBarRevealPointer(
    state: TabBarRevealState,
    enabled: Boolean,
    region: IntRect?,
    sidebarWidth: Dp,
) {
    val parent = LocalAwtWindow.current
    LaunchedEffect(parent, enabled, region, sidebarWidth) {
        state.pointerAtEdge = false
        state.pointerInRevealArea = false
        if (!enabled || region == null || parent == null) return@LaunchedEffect
        var previousX: Int? = null
        try {
            while (true) {
                val pane = (parent as? RootPaneContainer)?.contentPane
                val origin = runCatching {
                    if (pane?.isShowing == true) pane.locationOnScreen else null
                }.getOrNull()
                val cursor = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
                if (origin != null && cursor != null && (parent.isActive || state.drawerVisible)) {
                    val x = cursor.x - origin.x
                    val y = cursor.y - origin.y
                    // Activation belongs to the WINDOW edge, regardless of the plugin strip
                    // or panel columns before the tab sidebar. Retention uses its actual right edge.
                    val windowRegion = IntRect(0, 0, pane!!.width, pane.height)
                    state.pointerAtEdge = pointerReachesSidebarEdge(x, y, previousX, windowRegion)
                    state.pointerInRevealArea = pointerWithinSidebarMargin(
                        x, windowRegion, region.left + sidebarWidth.value,
                    )
                    previousX = x
                } else {
                    state.pointerAtEdge = false
                    state.pointerInRevealArea = false
                    previousX = null
                }
                delay(16L)
            }
        } finally {
            state.pointerAtEdge = false
            state.pointerInRevealArea = false
        }
    }
}
