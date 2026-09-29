package ai.rever.boss.components.sidebar

import ai.rever.boss.components.window_panel.components.main_window_panels.TabBarLayout
import ai.rever.boss.components.window_panel.components.main_window_panels.TabBarRevealState
import ai.rever.boss.components.window_panel.components.main_window_panels.overlayRegionInWindow
import ai.rever.boss.components.window_panel.components.main_window_panels.rememberToggleCollapseAction
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.theme.LocalGlassSidebarGeometry
import ai.rever.boss.theme.LocalWindowGlass
import ai.rever.boss.theme.sidebarGlassEnabled
import ai.rever.boss.utils.SystemUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp

/** BossTerm's main-window panel: its background continues behind the native toolbar. */
@Composable
internal fun integratedSidebarSurface(
    enabled: Boolean,
    extendsIntoTitleBar: Boolean,
): Modifier {
    val colors = BossTheme.colors
    val glass = sidebarGlassEnabled
    val panel =
        when {
            glass -> colors.ink.copy(alpha = LocalWindowGlass.current.chromeOpacity)
            else -> colors.panel
        }
    if (!enabled) return Modifier.background(if (glass) Color.Transparent else colors.raised)
    val geometry = LocalGlassSidebarGeometry.current
    val density = LocalDensity.current
    DisposableEffect(geometry) { onDispose { geometry.shape = null } }
    var top by remember { mutableFloatStateOf(0f) }
    val nativeFrame = SystemUtils.isMacOS && extendsIntoTitleBar
    return Modifier
        // Paint beneath the outer gap and clipped corners, before applying the panel inset.
        .background(if (glass) Color.Transparent else colors.raised)
        .padding(start = 4.dp, end = 4.dp, bottom = 4.dp, top = if (nativeFrame) 0.dp else 4.dp)
        .onGloballyPositioned {
            val position = it.positionInRoot()
            top = position.y
            val headerTop = with(density) { 4.dp.toPx() }
            val radius = with(density) { 22.dp.toPx() }
            geometry.shape =
                if (glass) {
                    RoundRect(
                        position.x,
                        if (nativeFrame) headerTop else position.y,
                        position.x + it.size.width,
                        position.y + it.size.height,
                        CornerRadius(radius),
                    )
                } else {
                    null
                }
        }.drawBehind {
            val extension = if (nativeFrame) (top - 4.dp.toPx()).coerceAtLeast(0f) else 0f
            val origin = Offset(0f, -extension)
            val bounds = Size(size.width, size.height + extension)
            val radius = CornerRadius(22.dp.toPx())
            drawRoundRect(panel, origin, bounds, radius)
            drawRoundRect(
                colors.textPrimary.copy(alpha = 0.18f),
                origin,
                bounds,
                radius,
                style = Stroke(0.75.dp.toPx()),
            )
        }
        // Only the background extends upward; tab content stays below the toolbar controls.
        .clip(
            if (nativeFrame) RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp) else RoundedCornerShape(22.dp),
        )
}

/** A revealed main-window panel closes locally; a pinned one still uses the saved preference. */
@Composable
internal fun integratedSidebarToggle(
    bar: TabBarLayout,
    reveal: TabBarRevealState,
): () -> Unit {
    val togglePinned = rememberToggleCollapseAction(bar, reveal)
    return if (reveal.drawerVisible) ({ reveal.dismiss(pointerInSidebar = true) }) else togglePinned
}

/** The panel's full-height paint and pointer region belong to the same main-window surface. */
@Composable
internal fun windowSidebarModifier(
    bar: TabBarLayout,
    reveal: TabBarRevealState,
    topInset: Dp,
    integrated: Boolean,
    extendsIntoTitleBar: Boolean = true,
): Modifier =
    Modifier
        // Keep the gap and clipped corners in the reveal's hover region. The hidden edge target
        // disappears on opening, so excluding that same space here would start a close/open loop.
        .hoverable(
            if (reveal.drawerVisible) reveal.drawerHover else reveal.railHover,
            enabled = bar.hoverExpand,
        ).then(
            if (integrated) {
                integratedSidebarSurface(enabled = !bar.railShown, extendsIntoTitleBar = extendsIntoTitleBar)
            } else {
                Modifier
            },
        ).padding(top = topInset)

/** Share the same measured window geometry with the hover target and native toolbar. */
internal fun sidebarRegion(
    coordinates: LayoutCoordinates,
    density: Float,
    onLeadingChange: (Float) -> Unit,
): IntRect? {
    val region = overlayRegionInWindow(coordinates.boundsInWindow(), density)
    region?.let { onLeadingChange(it.left.toFloat()) }
    return region
}
