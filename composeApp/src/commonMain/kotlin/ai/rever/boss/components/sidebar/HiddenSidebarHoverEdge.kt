package ai.rever.boss.components.sidebar

import ai.rever.boss.components.overlays.OverlayCorner
import ai.rever.boss.components.overlays.overlayCornerIsHeavyweight
import ai.rever.boss.components.window_panel.components.main_window_panels.TabBarRevealState
import ai.rever.boss.window.LocalWindowFullscreen
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp

/** An edge target with no layout width, sharing the rail's existing reveal timing and busy latch. */
@Composable
internal fun BoxScope.HiddenSidebarHoverEdge(
    reveal: TabBarRevealState,
    region: IntRect?,
    enabled: Boolean,
) {
    if (LocalWindowFullscreen.current) return
    if (!enabled || reveal.drawerVisible || !LocalWindowInfo.current.isWindowFocused) return
    // Match BossTerm: the hidden target is as wide as its 44 dp collapsed tab strip.
    val edgeWidth = 44.dp
    if (overlayCornerIsHeavyweight() && region != null) {
        // Chromium's native view sits above Compose; the native edge target must sit above it.
        // It is removed as soon as the drawer opens, and whenever the owning window loses focus.
        OverlayCorner(
            alignment = Alignment.TopStart,
            initialSize = DpSize(edgeWidth, region.height.dp),
            regionInWindow = region,
        ) {
            Box(Modifier.width(edgeWidth).height(region.height.dp).hoverable(reveal.railHover))
        }
    } else {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .width(edgeWidth)
                .fillMaxHeight()
                .hoverable(reveal.railHover),
        )
    }
}
