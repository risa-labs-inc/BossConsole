package ai.rever.boss.components.sidebar

import ai.rever.boss.components.window_panel.components.main_window_panels.TabBarRevealState
import androidx.compose.animation.core.EaseOutBounce
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
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
    val transition = updateTransition(railShown && reveal.drawerVisible, label = "sidebar-reveal")
    val animatedProgress by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 80, easing = EaseOutBounce) },
        label = "sidebar-reveal-progress",
    ) { visible -> if (visible) 1f else 0f }
    val progress = if (reveal.drawerOpen) 1f else animatedProgress
    // Keep the one native body alive when a closing bounce briefly reaches zero.
    val visible = reveal.drawerVisible || (integrated && (transition.isRunning || progress > 0f))
    val overlay = integrated && sidebarShouldOverlay(railShown, visible && !reveal.drawerOpen)
    return SidebarRevealMotion(progress, visible, overlay)
}
