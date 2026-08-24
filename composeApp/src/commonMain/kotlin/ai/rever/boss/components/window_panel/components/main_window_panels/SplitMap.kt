package ai.rever.boss.components.window_panel.components.main_window_panels

import ai.rever.boss.components.overlays.HoverTooltipBox
import ai.rever.boss.components.overlays.TooltipPlacement
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shape of the map's frame.
 *
 * A window is wider than it is tall, and a map that ignored that would put a left/right split's
 * halves in the wrong proportion to each other the moment you looked at both. This is a fixed
 * pleasant rectangle rather than the split area's real aspect: the panes INSIDE are placed at
 * their true fractions, so their sizes relative to one another are exact, and only the outer
 * frame is stylised - the same trade the header glyph already makes at 16x12.
 */
private const val MAP_ASPECT = 1.5f

/** Room around the map, so it does not sit hard against the bar's edges. */
private val MAP_INSET = 10.dp

/** Gap between two panes in the map, standing in for the divider between them. */
private val PANE_GAP = 1.dp

/**
 * The split, drawn big and clickable, pinned to the foot of the vertical tab bar.
 *
 * The group headers say which pane each list of tabs belongs to; this says the same thing the
 * other way round - here is the layout, click a pane to go to it. It is the one place in the bar
 * that shows the whole arrangement at once, which is what makes a four-way split legible rather
 * than a sequence of headers to read in order.
 *
 * Drawn from the panes' MEASURED rectangles, like the header glyphs, so it is right for any
 * arrangement and follows a divider as it is dragged.
 *
 * Not drawn for a single pane: a map of one region is a filled square that says nothing and
 * clicking it would go where the user already is.
 */
@Composable
internal fun SplitMap(groups: List<TabBarGroup>) {
    if (groups.size < 2) return

    // Measured rather than taken from a BoxWithConstraints: that is a SubcomposeLayout, and this
    // sits at the foot of a column whose sibling is a lazy list. See BossMainPanel's note on what
    // that cost the last time. One frame with no panes drawn is the whole price.
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val colors = BossTheme.colors

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(MAP_INSET)
                .aspectRatio(MAP_ASPECT)
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, colors.line, RoundedCornerShape(4.dp))
                .onSizeChanged { sizePx = it },
    ) {
        if (sizePx == IntSize.Zero) return@Box
        groups.forEach { group ->
            val glyph = group.glyph ?: return@forEach
            with(density) {
                Box(
                    modifier =
                        Modifier
                            .offset(
                                x = (glyph.left * sizePx.width).toDp(),
                                y = (glyph.top * sizePx.height).toDp(),
                            ).size(
                                width = ((glyph.right - glyph.left) * sizePx.width).toDp(),
                                height = ((glyph.bottom - glyph.top) * sizePx.height).toDp(),
                            ).padding(PANE_GAP),
                ) {
                    MapPane(group = group)
                }
            }
        }
    }
}

/**
 * One pane in the map.
 *
 * Clicking goes to that pane AND opens its tabs, because arriving somewhere and still having to
 * hover to see what is there would be two gestures for one intention.
 *
 * Double-clicking shows it alone. That is what double-click already means for a pane in every
 * window manager worth copying, and the map is the one place in BOSS where a pane is a thing you
 * can point at rather than a region of the screen you are already inside.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MapPane(group: TabBarGroup) {
    val colors = BossTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    val fill =
        when {
            group.isActive -> colors.signal.copy(alpha = MAP_ACTIVE_ALPHA)
            hovered -> colors.lineStrong.copy(alpha = MAP_HOVER_ALPHA)
            else -> colors.line.copy(alpha = MAP_IDLE_ALPHA)
        }

    HoverTooltipBox(
        text = "Go to ${group.label} - double-click for full screen",
        placement = TooltipPlacement.END,
        modifier = Modifier.fillMaxSize().hoverable(interactionSource),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(2.dp))
                    .background(fill)
                    .combinedClickable(
                        onClick = {
                            group.activate()
                            group.hoverGroup()
                        },
                        onDoubleClick = group.zoom,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = group.label,
                color = if (group.isActive) colors.onSignal else colors.textSecondary,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

/** The pane the user is in, wearing the same signal its header and its tab marker do. */
private const val MAP_ACTIVE_ALPHA = 0.85f

/** Under the pointer: clearly a target, clearly not the current pane. */
private const val MAP_HOVER_ALPHA = 0.55f

/** At rest: enough to read as a region, quiet enough that the active one is obvious. */
private const val MAP_IDLE_ALPHA = 0.5f
