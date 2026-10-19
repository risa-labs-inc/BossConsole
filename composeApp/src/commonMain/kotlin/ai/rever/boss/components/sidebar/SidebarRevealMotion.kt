package ai.rever.boss.components.sidebar

import ai.rever.boss.components.window_panel.components.main_window_panels.TabBarRevealState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

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
        transitionSpec = { if (targetState && reveal.drawerOpen) snap() else tween(durationMillis = 80) },
        label = "sidebar-reveal-progress",
    ) { visible -> if (visible) 1f else 0f }
    val progress = if (!railShown || reveal.drawerOpen) 1f else animatedProgress
    // Keep the same native body alive until the closing animation finishes.
    val visible = reveal.drawerVisible || (integrated && (transition.isRunning || animatedProgress > 0f))
    val reservesWidth = rememberSidebarWidthReservation(reveal, visible, railShown)
    val overlay = integrated && sidebarShouldOverlay(railShown, visible && !reservesWidth)
    return SidebarRevealMotion(progress, visible, overlay)
}

/** Keep the browser viewport stable until an explicitly opened sidebar finishes closing. */
@Composable
private fun rememberSidebarWidthReservation(
    reveal: TabBarRevealState,
    visible: Boolean,
    railShown: Boolean,
): Boolean {
    var explicitlyOpened by remember(reveal) { mutableStateOf(reveal.drawerOpen) }
    val transientReveal = reveal.isTransientReveal
    val reservesWidth = reveal.drawerOpen || (explicitlyOpened && visible && !transientReveal)
    SideEffect {
        if (reveal.drawerOpen) explicitlyOpened = true
        // A fresh hover can interrupt the close before visibility ever becomes false.
        if (!visible || !railShown || transientReveal) explicitlyOpened = false
    }
    return reservesWidth
}
