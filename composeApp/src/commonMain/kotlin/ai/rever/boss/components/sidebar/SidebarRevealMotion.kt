package ai.rever.boss.components.sidebar

import ai.rever.boss.components.window_panel.components.main_window_panels.TabBarRevealState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue

internal data class SidebarRevealMotion(
    val progress: Float,
    val visible: Boolean,
    val overlay: Boolean,
)

/** One clock drives the body, header, and native window, including the closing frames. */
@Composable
internal fun rememberSidebarRevealMotion(
    reveal: TabBarRevealState,
    railShown: Boolean,
    integrated: Boolean,
): SidebarRevealMotion {
    val animatedProgress by animateFloatAsState(
        targetValue = if (railShown && reveal.drawerVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "sidebar-reveal",
    )
    val progress = if (reveal.drawerOpen) 1f else animatedProgress
    val visible = reveal.drawerVisible || (integrated && progress > 0f)
    val overlay = integrated && sidebarShouldOverlay(railShown, visible && !reveal.drawerOpen)
    return SidebarRevealMotion(progress, visible, overlay)
}
