package ai.rever.boss.plugin.scrollbar

import ai.rever.boss.plugin.ui.BossDarkTextSecondary
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.max

fun Modifier.scrollbar(
    scrollState: ScrollState,
    direction: Orientation,
    config: ScrollbarConfig = ScrollbarConfig(),
): Modifier = composed {
    var (
        indicatorThickness, indicatorColor, indicatorCornerRadius,
        alpha, alphaAnimationSpec, padding
    ) = config

    val isScrollingOrPanning = scrollState.isScrollInProgress
    val isVertical = direction == Orientation.Vertical

    alpha = alpha ?: if (isScrollingOrPanning) 0.8f else 0f
    alphaAnimationSpec = alphaAnimationSpec ?: tween(
        delayMillis = if (isScrollingOrPanning) 0 else 1500,
        durationMillis = if (isScrollingOrPanning) 150 else 500
    )

    val scrollbarAlpha by animateFloatAsState(alpha, alphaAnimationSpec)

    drawWithContent {
        drawContent()

        val showScrollbar = isScrollingOrPanning || scrollbarAlpha > 0.0f

        // Draw scrollbar only if currently scrolling or if scroll animation is ongoing.
        if (showScrollbar) {
            val (topPadding, bottomPadding, startPadding, endPadding) = arrayOf(
                padding.calculateTopPadding().toPx(), padding.calculateBottomPadding().toPx(),
                padding.calculateStartPadding(layoutDirection).toPx(),
                padding.calculateEndPadding(layoutDirection).toPx()
            )

            val isLtr = layoutDirection == LayoutDirection.Ltr
            val contentOffset = scrollState.value
            val viewPortLength = if (isVertical) size.height else size.width
            val viewPortCrossAxisLength = if (isVertical) size.width else size.height
            val contentLength = max(viewPortLength + scrollState.maxValue, 0.001f /* To prevent divide by zero error */)
            val scrollbarLength = viewPortLength -
                    (if (isVertical) topPadding + bottomPadding else startPadding + endPadding)
            val indicatorThicknessPx = indicatorThickness.toPx()
            val indicatorLength = max((scrollbarLength / contentLength) * viewPortLength, 20f.dp.toPx())
            val indicatorOffset = (scrollbarLength / contentLength) * contentOffset
            val scrollIndicatorSize = if (isVertical) Size(indicatorThicknessPx, indicatorLength)
            else Size(indicatorLength, indicatorThicknessPx)

            val scrollIndicatorPosition = if (isVertical)
                Offset(
                    x = if (isLtr) viewPortCrossAxisLength - indicatorThicknessPx - endPadding
                    else startPadding,
                    y = indicatorOffset + topPadding
                )
            else
                Offset(
                    x = if (isLtr) indicatorOffset + startPadding
                    else viewPortLength - indicatorOffset - indicatorLength - endPadding,
                    y = if (config.horizontalScrollbarAtTop) topPadding
                    else viewPortCrossAxisLength - indicatorThicknessPx - bottomPadding
                )

            drawRoundRect(
                color = indicatorColor,
                cornerRadius = indicatorCornerRadius.let { CornerRadius(it.toPx(), it.toPx()) },
                topLeft = scrollIndicatorPosition,
                size = scrollIndicatorSize,
                alpha = scrollbarAlpha
            )
        }
    }
}

data class ScrollbarConfig(
    val indicatorThickness: Dp = 8.dp,
    val indicatorColor: Color = Color.Gray.copy(alpha = 0.7f),
    val indicatorCornerRadius: Dp = indicatorThickness / 2,
    val alpha: Float? = null,
    val alphaAnimationSpec: AnimationSpec<Float>? = null,
    val padding: PaddingValues = PaddingValues(all = 0.dp),
    val horizontalScrollbarAtTop: Boolean = false
)

/**
 * Default scrollbar configuration for bottom panels (Console, Git Log, Git Changes)
 * Uses thinner indicator for compact panel context.
 * This is a static default - for dynamic settings use getPanelScrollbarConfig()
 */
val PanelScrollbarConfig = ScrollbarConfig(
    indicatorThickness = ScrollbarDimensions.PANEL_THICKNESS,
    indicatorColor = BossDarkTextSecondary,
    indicatorCornerRadius = ScrollbarDimensions.PANEL_THICKNESS / 2
)

/**
 * Default scrollbar configuration for horizontal bars (Tab Bar, Bottom Bar)
 * Uses very thin indicator positioned at top for compact horizontal chrome.
 * This is a static default - for dynamic settings use getBarScrollbarConfig()
 */
val HorizontalBarScrollbarConfig = ScrollbarConfig(
    indicatorThickness = ScrollbarDimensions.BAR_THICKNESS,
    indicatorColor = BossDarkTextSecondary,
    indicatorCornerRadius = 4.dp,
    horizontalScrollbarAtTop = true
)

/**
 * Get panel scrollbar configuration using current user settings.
 * Use this in @Composable functions for dynamic settings.
 */
@Composable
fun getPanelScrollbarConfig(): ScrollbarConfig {
    val settings by ScrollbarSettingsManager.currentSettings.collectAsState()
    return remember(settings) {
        ScrollbarConfig(
            indicatorThickness = settings.panelThicknessDp,
            indicatorColor = BossDarkTextSecondary,
            indicatorCornerRadius = settings.panelThicknessDp / 2,
            alpha = if (settings.alwaysShowScrollbars) 0.8f else null,
            // Only override animation spec when always showing - otherwise let the
            // scrollbar modifier handle the animation with proper scroll-aware timing
            alphaAnimationSpec = if (settings.alwaysShowScrollbars) {
                tween(delayMillis = 0, durationMillis = settings.fadeDurationMs)
            } else {
                null // Use default scroll-aware animation
            }
        )
    }
}

/**
 * Get horizontal bar scrollbar configuration using current user settings.
 * Use this in @Composable functions for dynamic settings.
 */
@Composable
fun getBarScrollbarConfig(): ScrollbarConfig {
    val settings by ScrollbarSettingsManager.currentSettings.collectAsState()
    return remember(settings) {
        ScrollbarConfig(
            indicatorThickness = settings.barThicknessDp,
            indicatorColor = BossDarkTextSecondary,
            indicatorCornerRadius = 4.dp,
            horizontalScrollbarAtTop = true,
            alpha = if (settings.alwaysShowScrollbars) 0.8f else null,
            // Only override animation spec when always showing - otherwise let the
            // scrollbar modifier handle the animation with proper scroll-aware timing
            alphaAnimationSpec = if (settings.alwaysShowScrollbars) {
                tween(delayMillis = 0, durationMillis = settings.fadeDurationMs)
            } else {
                null // Use default scroll-aware animation
            }
        )
    }
}

fun Modifier.verticalScrollWithScrollbar(
    scrollState: ScrollState,
    enabled: Boolean = true,
    flingBehavior: FlingBehavior? = null,
    reverseScrolling: Boolean = false,
    scrollbarConfig: ScrollbarConfig = ScrollbarConfig()
): Modifier = this
    .scrollbar(scrollState, direction = Orientation.Vertical, config = scrollbarConfig)
    .verticalScroll(scrollState, enabled, flingBehavior, reverseScrolling)


fun Modifier.horizontalScrollWithScrollbar(
    scrollState: ScrollState,
    enabled: Boolean = true,
    flingBehavior: FlingBehavior? = null,
    reverseScrolling: Boolean = false,
    scrollbarConfig: ScrollbarConfig = ScrollbarConfig()
): Modifier = this
    .scrollbar(scrollState, direction = Orientation.Horizontal, config = scrollbarConfig)
    .horizontalScroll(scrollState, enabled, flingBehavior, reverseScrolling)

/**
 * Scrollbar modifier for LazyList (LazyRow/LazyColumn)
 * Works with LazyListState instead of ScrollState
 */
fun Modifier.lazyListScrollbar(
    listState: LazyListState,
    direction: Orientation,
    config: ScrollbarConfig = ScrollbarConfig(),
): Modifier = composed {
    var (
        indicatorThickness, indicatorColor, indicatorCornerRadius,
        alpha, alphaAnimationSpec, padding
    ) = config

    val isScrolling = listState.isScrollInProgress
    val isVertical = direction == Orientation.Vertical

    alpha = alpha ?: if (isScrolling) 0.8f else 0f
    alphaAnimationSpec = alphaAnimationSpec ?: tween(
        delayMillis = if (isScrolling) 0 else 1500,
        durationMillis = if (isScrolling) 150 else 500
    )

    val scrollbarAlpha by animateFloatAsState(alpha, alphaAnimationSpec)

    drawWithContent {
        drawContent()

        val showScrollbar = isScrolling || scrollbarAlpha > 0.0f

        if (showScrollbar) {
            val layoutInfo = listState.layoutInfo
            val viewportLength = if (isVertical) size.height.toInt() else size.width.toInt()

            // Calculate total content length from all items
            // Use average of ALL visible items for better accuracy (tabs have varying widths)
            val averageItemSize = if (layoutInfo.visibleItemsInfo.isNotEmpty()) {
                layoutInfo.visibleItemsInfo.sumOf { it.size } / layoutInfo.visibleItemsInfo.size
            } else {
                0
            }
            val contentLength = layoutInfo.totalItemsCount * averageItemSize

            // Only show scrollbar if content is larger than viewport
            if (contentLength > viewportLength && averageItemSize > 0) {
                val (topPadding, bottomPadding, startPadding, endPadding) = arrayOf(
                    padding.calculateTopPadding().toPx(),
                    padding.calculateBottomPadding().toPx(),
                    padding.calculateStartPadding(layoutDirection).toPx(),
                    padding.calculateEndPadding(layoutDirection).toPx()
                )

                val scrollbarLength = viewportLength -
                    (if (isVertical) topPadding + bottomPadding else startPadding + endPadding)

                // Calculate current scroll offset
                val scrollOffset = listState.firstVisibleItemIndex * averageItemSize +
                    listState.firstVisibleItemScrollOffset

                // Calculate indicator length proportional to viewport/content ratio
                val indicatorLength = max(
                    (scrollbarLength / contentLength.toFloat()) * viewportLength,
                    20f.dp.toPx()
                )

                // Calculate indicator offset proportional to scroll position
                val maxScroll = contentLength - viewportLength
                val indicatorOffset = if (maxScroll > 0) {
                    (scrollbarLength / contentLength.toFloat()) * scrollOffset
                } else 0f

                val indicatorThicknessPx = indicatorThickness.toPx()
                val scrollIndicatorSize = if (isVertical)
                    Size(indicatorThicknessPx, indicatorLength)
                else
                    Size(indicatorLength, indicatorThicknessPx)

                val isLtr = layoutDirection == LayoutDirection.Ltr
                val viewPortCrossAxisLength = if (isVertical) size.width else size.height

                val scrollIndicatorPosition = if (isVertical)
                    Offset(
                        x = if (isLtr) viewPortCrossAxisLength - indicatorThicknessPx - endPadding
                        else startPadding,
                        y = indicatorOffset + topPadding
                    )
                else
                    Offset(
                        x = if (isLtr) indicatorOffset + startPadding
                        else viewportLength - indicatorOffset - indicatorLength - endPadding,
                        y = if (config.horizontalScrollbarAtTop) topPadding
                        else viewPortCrossAxisLength - indicatorThicknessPx - bottomPadding
                    )

                drawRoundRect(
                    color = indicatorColor,
                    cornerRadius = indicatorCornerRadius.let { CornerRadius(it.toPx(), it.toPx()) },
                    topLeft = scrollIndicatorPosition,
                    size = scrollIndicatorSize,
                    alpha = scrollbarAlpha
                )
            }
        }
    }
}

/**
 * Helper extension for horizontal LazyList scrollbar
 */
fun Modifier.horizontalLazyListScrollbar(
    listState: LazyListState,
    scrollbarConfig: ScrollbarConfig = ScrollbarConfig()
): Modifier = this.lazyListScrollbar(
    listState,
    direction = Orientation.Horizontal,
    config = scrollbarConfig
)
