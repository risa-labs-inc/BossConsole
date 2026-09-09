package ai.rever.boss.plugin.browser

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Height of the bar, and the size the corner overlay opens at before it measures. */
internal val FIND_BAR_HEIGHT = 34.dp

/**
 * First-frame size of the corner overlay. Content is measured against the parent region and can
 * grow beyond this estimate. The value is above the bar's natural width to avoid a visible initial
 * grow-in while preserving a small click-catching placeholder.
 */
internal val FIND_BAR_INITIAL_SIZE = DpSize(360.dp, 60.dp)

/**
 * Gap between the bar and the pane's top and end edges, in dp.
 *
 * Applied by shrinking the anchor REGION rather than by padding the bar. Padding would work on the
 * lightweight path and be actively wrong on the heavyweight one: that overlay window is sized to
 * its content, so transparent padding grows the window, and a non-focusable AWT window still eats
 * every click under it (the JVM has no portable click-through). The margin would become a dead
 * strip across the top-right of the page.
 */
internal const val FIND_BAR_MARGIN_DP = 8

/**
 * How long a candidate outside a main window panel yields before claiming a `browser.find` event.
 *
 * Long enough that a main-panel surface in the same window always claims first (it does so in the
 * same dispatch), short enough to be invisible if it is the only candidate. A fixed order beats a
 * race between coroutine resumptions, which is what decided this before.
 */
internal const val SHORTCUT_DEFERRAL_MS = 40L

/** [FIND_BAR_MARGIN_DP] as a `Dp`, for the lightweight path, which pads instead of insetting. */
internal val FIND_BAR_MARGIN = FIND_BAR_MARGIN_DP.dp

/**
 * The rectangle a pane's find bar anchors inside: [boundsInWindow] converted to dp and pulled in
 * by [FIND_BAR_MARGIN_DP], or null when there is nowhere sensible to put it.
 *
 * Null is a real answer, not a failure. `boundsInWindow` reports CLIPPED bounds, so a pane scrolled
 * or collapsed out of view measures empty - and `HeavyweightCorner` resolves an unmeasurable parent
 * to the screen origin, which would put an always-on-top bar in the corner of the primary display
 * instead of over the page.
 */
@Suppress("ReturnCount")
internal fun findBarRegion(
    boundsInWindow: Rect,
    density: Float,
): IntRect? {
    if (density <= 0f || !density.isFinite()) return null
    if (!boundsInWindow.hasArea()) return null
    val region =
        IntRect(
            left = (boundsInWindow.left / density).roundToInt(),
            top = (boundsInWindow.top / density).roundToInt(),
            right = (boundsInWindow.right / density).roundToInt(),
            bottom = (boundsInWindow.bottom / density).roundToInt(),
        ).deflate(FIND_BAR_MARGIN_DP)
    // A pane narrower than twice the margin deflates to nothing. Better no bar than one placed at
    // an inverted rectangle's corner.
    return region.takeIf { it.width > 0 && it.height > 0 }
}

/**
 * Whether this rectangle describes a real, finite area.
 *
 * `boundsInWindow` can be empty (a clipped-away pane) and, for a node in a degenerate layout, can
 * carry infinities - which survive the division by density and turn into a garbage `IntRect`.
 */
private fun Rect.hasArea(): Boolean {
    val w = width
    val h = height
    return w.isFinite() && h.isFinite() && w > 0f && h > 0f
}
