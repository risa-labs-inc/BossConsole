package ai.rever.boss.components.sidebar

import ai.rever.boss.components.window_panel.components.main_window_panels.TabBarRevealState
import ai.rever.boss.components.window_panel.components.main_window_panels.sidebarNativePointerTrackingAvailable
import ai.rever.boss.window.LocalWindowFullscreen
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp

/** An edge target with no layout width, sharing the rail's existing reveal timing and busy latch. */
@Composable
internal fun BoxScope.HiddenSidebarHoverEdge(
    reveal: TabBarRevealState,
    enabled: Boolean,
    revealing: Boolean = reveal.drawerVisible,
) {
    // Native cursor tracking already crosses Chromium. Creating an invisible dialog here
    // adds a window show/close transaction every time the pinned sidebar is toggled.
    if (LocalWindowFullscreen.current || sidebarNativePointerTrackingAvailable()) return
    if (!enabled || revealing || !LocalWindowInfo.current.isWindowFocused) return
    // Fallback activation still belongs to the exact window edge, not the retention margin.
    val edgeWidth = 1.dp
    Box(
        Modifier
            .align(Alignment.CenterStart)
            .width(edgeWidth)
            .fillMaxHeight()
            .hoverable(reveal.railHover),
    )
}
