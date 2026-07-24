package ai.rever.boss.components.window_panel.components

import ai.rever.boss.components.dividers.VDivider
import ai.rever.boss.platform.CursorUtil.cursorForHorizontalResize
import ai.rever.boss.platform.CursorUtil.cursorForVerticalResize
import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.isFirst
import ai.rever.boss.plugin.api.Panel.Companion.isHorizontal
import ai.rever.boss.plugin.api.Panel.Companion.isLast
import ai.rever.boss.plugin.api.Panel.Companion.isVertical
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.Panel.Companion.right
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

// Each side of a relative split keeps at least this fraction of the container,
// matching BossTerm's split-pane minimum (splitMinimumSize = 0.1f).
private const val MIN_SPLIT_FRACTION = 0.1f

@Composable
fun BossResizablePanel(
    modifier: Modifier,
    panel: Panel,
    isPanelVisible: Boolean = false,
    isMainVisible: Boolean = true,
    isRelative: Boolean = false,
    defaultWeight: Float = 1f,
    sideContent: (@Composable BoxScope.() -> Unit)? = null,
    mainContent: (@Composable BoxScope.() -> Unit)? = null,
) {
    val defaultPanelSize = run { if (panel.isHorizontal) 250.dp else 200.dp }
    val resizeAreaSize = 16.dp
    val dividerHeight = 1.dp

    BoxWithConstraints(modifier = modifier) {
        val maxSize = run { if (panel.isHorizontal) maxWidth else maxHeight }

        // Minimum size prevents panels from completely disappearing (Issue #248)
        // Scales with screen size: 2% of available space with 20dp floor for small screens
        val minPanelSize = (maxSize * 0.02f).coerceAtLeast(20.dp)

        // Relative panels (split views) keep their size as a fraction of the container so
        // they scale with it; fixed panels (sidebars) keep an absolute size. Both must be
        // applied against the live container size — deriving the fraction from a container
        // size captured on an earlier composition made the divider move faster or slower
        // than the pointer after the container resized.
        var weight by remember { mutableStateOf(defaultWeight / 2f) }
        var size by remember { mutableStateOf(defaultPanelSize) }

        val panelWeight: Float =
            run {
                (if (isRelative) weight else size / maxSize).coerceIn(0f, 1f)
            }

        val relativeSize: Dp = run { maxSize * panelWeight }

        // The pointerInput gesture below is captured once and never re-created, so it must
        // read the container bounds through state that recomposition keeps current.
        val latestMaxSize by rememberUpdatedState(maxSize)
        val latestMinPanelSize by rememberUpdatedState(minPanelSize)

        val alignDirection =
            run {
                when (panel) {
                    top -> Alignment.TopCenter
                    left -> Alignment.TopStart
                    right -> Alignment.TopEnd
                    else -> Alignment.BottomCenter
                }
            }

        fun Modifier.resizeAreaOffset() =
            offset {
                val halfResizeAreaSize = resizeAreaSize / 2
                val x =
                    when (panel) {
                        left -> relativeSize - halfResizeAreaSize
                        right -> -relativeSize - dividerHeight + halfResizeAreaSize
                        else -> 0.dp
                    }.roundToPx()
                val y =
                    when (panel) {
                        top -> relativeSize - halfResizeAreaSize
                        bottom -> -relativeSize - dividerHeight + halfResizeAreaSize
                        else -> 0.dp
                    }.roundToPx()

                IntOffset(x, y)
            }

        fun Modifier.fillSize() =
            run {
                if (!isMainVisible || mainContent == null) {
                    fillMaxSize()
                } else if (panel.isHorizontal) {
                    fillMaxHeight().fillMaxWidth(panelWeight)
                } else {
                    fillMaxWidth().fillMaxHeight(panelWeight)
                }
            }

        fun Modifier.resizable() =
            run {
                if (panel.isHorizontal) {
                    fillMaxHeight()
                        .width(resizeAreaSize)
                        .resizeAreaOffset()
                        .cursorForHorizontalResize()
                } else {
                    fillMaxWidth()
                        .height(resizeAreaSize)
                        .resizeAreaOffset()
                        .cursorForVerticalResize()
                }
            }

        fun Offset.axis() = run { if (panel.isHorizontal) x else y }

        fun Dp.direction() = run { this * (if (panel.isLast) -1 else 1) }

        fun PointerInputScope.onDrag(dragAmount: Offset) {
            val delta = dragAmount.axis().toDp().direction()
            if (isRelative) {
                // Both sides of a split keep a minimum size, like BossTerm panes.
                val minSize = (latestMaxSize * MIN_SPLIT_FRACTION).coerceAtLeast(latestMinPanelSize)
                val newSize =
                    (latestMaxSize * weight + delta)
                        .coerceIn(minSize, (latestMaxSize - minSize).coerceAtLeast(minSize))
                weight = newSize / latestMaxSize
            } else {
                // Sidebars keep their small floor, but can no longer swallow the main content.
                size =
                    (size + delta)
                        .coerceIn(
                            latestMinPanelSize,
                            (latestMaxSize - latestMinPanelSize).coerceAtLeast(latestMinPanelSize),
                        )
            }
        }

        @Composable
        fun PanelDivider() {
            if (isMainVisible) {
                if (panel.isHorizontal) {
                    VDivider()
                } else {
                    Divider(color = BossTheme.colors.line)
                }
            }
        }

        @Composable
        fun Body(modifier: Modifier) {
            sideContent?.let {
                if (panel.isFirst && isPanelVisible) {
                    Box(modifier = Modifier.fillSize()) {
                        it()
                    }
                    PanelDivider()
                }
            }
            mainContent?.let {
                if (isMainVisible) {
                    Box(modifier = modifier) {
                        it()
                    }
                }
            }
            sideContent?.let {
                if (panel.isLast && isPanelVisible) {
                    PanelDivider()
                    Box(modifier = Modifier.fillSize()) {
                        it()
                    }
                }
            }
        }

        if (panel.isVertical) {
            Column(modifier = Modifier.fillMaxSize()) {
                Body(modifier = Modifier.weight(1f))
            }
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                Body(modifier = Modifier.weight(1f))
            }
        }

        if (isPanelVisible && isMainVisible) {
            Box(
                modifier =
                    Modifier
                        .align(alignDirection)
                        .resizable()
                        .alpha(0f)
                        .pointerInput(panel, isRelative) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                onDrag(dragAmount)
                            }
                        },
            )
        }
    }
}
