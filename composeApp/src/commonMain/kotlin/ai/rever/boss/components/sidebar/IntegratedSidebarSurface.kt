package ai.rever.boss.components.sidebar

import ai.rever.boss.components.overlays.OverlayCorner
import ai.rever.boss.components.overlays.overlayCornerIsHeavyweight
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/** BossTerm's main-window panel: its background continues behind the native toolbar. */
@Composable
internal fun integratedSidebarSurface(
    enabled: Boolean,
    extendsIntoTitleBar: Boolean,
    revealProgress: Float = 1f,
    headerOnly: Boolean = false,
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
    var measuredBounds by remember { mutableStateOf<Rect?>(null) }
    SideEffect {
        geometry.shape =
            measuredBounds?.takeIf { glass }?.let { bounds ->
                sidebarSurfaceGeometry(bounds, nativeFrame, revealProgress, density)
            }
    }
    return Modifier
        // Paint beneath the outer gap and clipped corners, before applying the panel inset.
        .background(if (glass || headerOnly) Color.Transparent else colors.raised)
        .padding(start = 4.dp, end = 4.dp, bottom = 4.dp, top = if (nativeFrame) 0.dp else 4.dp)
        .onGloballyPositioned {
            val position = it.positionInRoot()
            top = position.y
            measuredBounds = Rect(position.x, position.y, position.x + it.size.width, position.y + it.size.height)
        }.drawBehind {
            val extension = if (nativeFrame) (top - 4.dp.toPx()).coerceAtLeast(0f) else 0f
            val origin = Offset(0f, -extension)
            val bodyWidth = sidebarBodyWidth(size.width.toDp(), revealProgress).toPx()
            val bounds = Size(bodyWidth, size.height + extension)
            val radius = CornerRadius(22.dp.toPx())
            clipRect(top = origin.y, bottom = if (headerOnly) 0f else size.height) {
                drawRoundRect(panel, origin, bounds, radius)
                drawRoundRect(
                    colors.textPrimary.copy(alpha = 0.18f),
                    origin,
                    bounds,
                    radius,
                    style = Stroke(0.75.dp.toPx()),
                )
            }
        }
        // Only the background extends upward; tab content stays below the toolbar controls.
        .clip(sidebarPanelShape(nativeFrame, density, revealProgress))
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
    revealProgress: Float = 1f,
    headerOnly: Boolean = false,
): Modifier =
    Modifier
        // Keep the gap and clipped corners in the reveal's hover region. The hidden edge target
        // disappears on opening, so excluding that same space here would start a close/open loop.
        .hoverable(
            if (reveal.drawerVisible) reveal.drawerHover else reveal.railHover,
            enabled = bar.hoverExpand,
        ).then(
            if (integrated) {
                integratedSidebarSurface(
                    enabled = !bar.railShown,
                    extendsIntoTitleBar = extendsIntoTitleBar,
                    revealProgress = revealProgress,
                    headerOnly = headerOnly,
                )
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

/** The same sidebar keeps its full size but reserves no row width during a temporary reveal. */
internal fun sidebarOverlayLayout(
    overlay: Boolean,
    revealProgress: Float = 1f,
): Modifier =
    Modifier
        .zIndex(if (overlay) 1f else 0f)
        .layout { measurable, constraints ->
            val sidebar = measurable.measure(constraints)
            layout(if (overlay) 0 else sidebar.width, sidebar.height) {
                sidebar.placeRelative(0, 0)
            }
        }.drawWithContent {
            if (overlay) {
                clipRect(top = -10000f, right = size.width * revealProgress) { this@drawWithContent.drawContent() }
            } else {
                drawContent()
            }
        }

/** Hide covered content without changing the main panel's measured size or viewport. */
internal fun mainPanelSidebarClip(
    overlay: Boolean,
    sidebarWidth: Dp,
): Modifier =
    if (!overlay) {
        Modifier
    } else {
        Modifier.drawWithContent {
            val covered = sidebarWidth.toPx().coerceAtMost(size.width)
            clipRect(
                left = if (layoutDirection == LayoutDirection.Ltr) covered else 0f,
                right = if (layoutDirection == LayoutDirection.Rtl) size.width - covered else size.width,
            ) { this@drawWithContent.drawContent() }
        }
    }

/** Header and body share this width equation, including the existing 4dp side insets. */
internal fun sidebarBodyWidth(
    width: Dp,
    progress: Float,
): Dp = ((width.value + 8f) * progress.coerceIn(0f, 1f) - 8f).coerceIn(0f, width.value).dp

private fun sidebarPanelShape(
    nativeFrame: Boolean,
    density: Density,
    progress: Float,
): Shape =
    GenericShape { size, _ ->
        val radius = CornerRadius(with(density) { 22.dp.toPx() })
        val width = with(density) { sidebarBodyWidth(size.width.toDp(), progress).toPx() }
        addRoundRect(
            RoundRect(
                left = 0f,
                top = 0f,
                right = width,
                bottom = size.height,
                topLeftCornerRadius = if (nativeFrame) CornerRadius.Zero else radius,
                topRightCornerRadius = if (nativeFrame) CornerRadius.Zero else radius,
                bottomLeftCornerRadius = radius,
                bottomRightCornerRadius = radius,
            ),
        )
    }

private fun sidebarSurfaceGeometry(
    bounds: Rect,
    nativeFrame: Boolean,
    progress: Float,
    density: Density,
): RoundRect =
    with(density) {
        RoundRect(
            bounds.left,
            if (nativeFrame) 4.dp.toPx() else bounds.top,
            bounds.left + sidebarBodyWidth(bounds.width.toDp(), progress).toPx(),
            bounds.bottom,
            CornerRadius(22.dp.toPx()),
        )
    }

/** Keep the owning surface and the native body on the same reveal geometry. */
@Composable
internal fun integratedSidebarLayout(
    bar: TabBarLayout,
    reveal: TabBarRevealState,
    topInset: Dp,
    integrated: Boolean,
    extendsIntoTitleBar: Boolean,
    overlay: Boolean,
    progress: Float,
): Modifier =
    sidebarOverlayLayout(overlay, progress).then(
        windowSidebarModifier(
            bar,
            reveal,
            topInset,
            integrated,
            extendsIntoTitleBar,
            revealProgress = if (overlay) progress else 1f,
            headerOnly = overlay && overlayCornerIsHeavyweight(),
        ),
    )
