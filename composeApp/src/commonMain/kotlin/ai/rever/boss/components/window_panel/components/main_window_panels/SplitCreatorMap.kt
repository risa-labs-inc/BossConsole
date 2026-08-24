package ai.rever.boss.components.window_panel.components.main_window_panels

import ai.rever.boss.components.overlays.HoverTooltipBox
import ai.rever.boss.components.overlays.TooltipPlacement
import ai.rever.boss.components.window_panel.SplitDirection
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * How much of the frame the centre - the pane you already have - takes.
 *
 * The four trapezoids are what is left around it. Big enough that the centre reads as the pane
 * rather than as a gap between targets, small enough that each trapezoid is comfortably clickable
 * at the width the bar gives this.
 */
private const val CENTRE_FRACTION = 0.44f

/** Same frame the real map uses, so the foot of the bar does not change shape when you split. */
private const val MAP_ASPECT = 1.5f
private val MAP_INSET = 10.dp

private const val TARGET_HOVER_ALPHA = 0.85f
private const val TARGET_IDLE_ALPHA = 0.34f
private const val CENTRE_ALPHA = 0.5f

/**
 * The map, for a window that is not split yet: four places to put a second pane.
 *
 * The real [SplitMap] needs at least two panes to say anything - a map of one region is a filled
 * square that says nothing, and clicking it would go where the user already is. So the foot of the
 * bar used to be empty until the first split, which is the one moment it could have been most
 * useful: splitting was a right-click on a pane in a map that was not being drawn.
 *
 * This fills that space with the same frame, turned into a target. The centre is the pane you have
 * (named, as it is on the real map); the four trapezoids around it are where a new one would go,
 * cut from the corners so each one points at its own edge. Click one and it splits that way.
 *
 * Clicking asks for a tab FIRST and splits with it - a pane created empty is closed again about
 * 50ms later by checkAndCloseEmptyPanels, so a split that made one would appear to do nothing.
 * That is [TabBarGroup.split]'s job, and it is the same path the pane menu's Split entries take.
 */
@Composable
internal fun SplitCreatorMap(group: TabBarGroup) {
    val colors = BossTheme.colors
    var hovered by remember { mutableStateOf<SplitDirection?>(null) }

    HoverTooltipBox(
        text = "Split this pane - click the side the new pane goes on",
        placement = TooltipPlacement.END,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(MAP_INSET)
                    .aspectRatio(MAP_ASPECT)
                    .clip(RoundedCornerShape(4.dp))
                    // Hover and click are separate handlers on purpose: detectTapGestures owns
                    // the press and would swallow the moves the highlight needs.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val position = event.changes.lastOrNull()?.position
                                hovered =
                                    if (event.type == PointerEventType.Exit) {
                                        null
                                    } else {
                                        position?.let { size.targetAt(it) }
                                    }
                            }
                        }
                    }.pointerInput(group.split) {
                        detectTapGestures { offset -> size.targetAt(offset)?.let(group.split) }
                    },
            contentAlignment = Alignment.Center,
        ) {
            SplitTargets(hovered = hovered)

            Text(
                text = if (hovered != null) "Split ${hovered?.displayName}" else group.label,
                color = if (hovered != null) colors.signalText else colors.textSecondary,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

/** The four targets and the pane in the middle. Split out of [SplitCreatorMap] for its length. */
@Composable
private fun SplitTargets(hovered: SplitDirection?) {
    val colors = BossTheme.colors

    Canvas(modifier = Modifier.fillMaxSize()) {
        val centre = centreRect(size)
        SplitDirection.entries.forEach { direction ->
            drawPath(
                path = trapezoidFor(direction, size, centre),
                color =
                    if (direction == hovered) {
                        colors.signal.copy(alpha = TARGET_HOVER_ALPHA)
                    } else {
                        colors.line.copy(alpha = TARGET_IDLE_ALPHA)
                    },
            )
        }
        // The centre last, so it sits over the trapezoids' shared corners rather than under
        // whatever the fold of two paths leaves there.
        drawRect(
            color = colors.lineStrong.copy(alpha = CENTRE_ALPHA),
            topLeft = Offset(centre.left, centre.top),
            size = Size(centre.right - centre.left, centre.bottom - centre.top),
        )
    }
}

/** [splitTargetAt] against a measured frame, which is how both pointer handlers ask. */
private fun IntSize.targetAt(at: Offset) = splitTargetAt(at.x, at.y, width.toFloat(), height.toFloat())

/** The centre rectangle, in pixels, for a frame of [size]. */
private fun centreRect(size: Size): Bounds {
    val insetX = size.width * (1f - CENTRE_FRACTION) / 2f
    val insetY = size.height * (1f - CENTRE_FRACTION) / 2f
    return Bounds(insetX, insetY, size.width - insetX, size.height - insetY)
}

private data class Bounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * One trapezoid: an outer edge, and the centre's matching edge.
 *
 * Because the centre is the frame scaled about its middle, its corners sit on the frame's own
 * diagonals - so the four shapes are exactly the corner-to-corner cuts, with no gaps or overlaps
 * to fill in, and [splitTargetAt] can decide which one a point is in without reconstructing them.
 */
private fun trapezoidFor(
    direction: SplitDirection,
    size: Size,
    centre: Bounds,
): Path =
    Path().apply {
        when (direction) {
            SplitDirection.UP -> {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(centre.right, centre.top)
                lineTo(centre.left, centre.top)
            }

            SplitDirection.RIGHT -> {
                moveTo(size.width, 0f)
                lineTo(size.width, size.height)
                lineTo(centre.right, centre.bottom)
                lineTo(centre.right, centre.top)
            }

            SplitDirection.DOWN -> {
                moveTo(size.width, size.height)
                lineTo(0f, size.height)
                lineTo(centre.left, centre.bottom)
                lineTo(centre.right, centre.bottom)
            }

            SplitDirection.LEFT -> {
                moveTo(0f, size.height)
                lineTo(0f, 0f)
                lineTo(centre.left, centre.top)
                lineTo(centre.left, centre.bottom)
            }
        }
        close()
    }

/**
 * Which target a point falls in, or null for the centre.
 *
 * The centre is the pane that already exists, so a click there is not a split and deliberately
 * does nothing rather than picking a nearest edge - the four regions each have a whole side of
 * the frame to be hit in, and guessing at the middle would split on a click that meant "no".
 *
 * Outside the centre, the boundaries are the frame's diagonals, so the test is which of the two
 * offsets from the middle is larger. Normalised first, so it is the RECTANGLE's diagonals and not
 * a square's - the frame is half again as wide as it is tall.
 */
internal fun splitTargetAt(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
): SplitDirection? {
    if (width <= 0f || height <= 0f) return null

    val dx = x / width - 0.5f
    val dy = y / height - 0.5f
    val half = CENTRE_FRACTION / 2f

    return when {
        abs(dx) <= half && abs(dy) <= half -> null
        abs(dx) > abs(dy) -> if (dx < 0f) SplitDirection.LEFT else SplitDirection.RIGHT
        else -> if (dy < 0f) SplitDirection.UP else SplitDirection.DOWN
    }
}
