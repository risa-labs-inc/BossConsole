package ai.rever.boss.components.overlays

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import java.awt.GraphicsEnvironment
import java.awt.Window
import kotlin.math.roundToInt

/**
 * Where a heavyweight overlay window (popup, modal) should be placed, shared by
 * [HeavyweightPopup] and [HeavyweightModal].
 *
 * Both need the same thing — cover the parent window exactly, so a full-window scrim lines
 * up with it — and both used to resolve it inline with the same silent fallback. Sharing it
 * makes the fallback loud instead, which matters: the fallback is the path that produces a
 * visibly broken overlay, and it produced it with nothing in the log to say so.
 */
private val logger = BossLogger.forComponent("OverlayWindowBounds")

/**
 * The parent window's screen bounds as `[x, y, width, height]`, or null if they cannot be
 * read — **and a null is logged**, because it is not a benign condition.
 *
 * Keyed on [parent] rather than remembered forever. An overlay composable that stays in
 * composition across opens (or is re-shown after its parent moved between windows) would
 * otherwise keep the bounds it measured the first time, and place itself over the wrong
 * window.
 *
 * AWT reports these in logical units, which map 1:1 to dp in Compose Desktop, so callers
 * stay in dp and never touch the density factor.
 */
@Composable
internal fun rememberOverlayParentBounds(parent: Window?): IntArray? =
    remember(parent) {
        // Bound to a local so the checks below are a smart cast rather than repeated
        // null-safe calls on a captured parameter, which Kotlin will not narrow.
        val window = parent
        val reason =
            when {
                window == null -> "no LocalAwtWindow in scope"
                !window.isShowing -> "parent window is not showing yet"
                else -> null
            }
        if (reason != null || window == null) {
            // Loud on purpose. Falling back means the overlay covers the whole SCREEN instead
            // of the parent window, and the New Tab dialog draws a 40%-black scrim across
            // whatever it covers — so this is the difference between a normal dialog and a
            // grey wash over everything. It was previously silent, which is why an
            // intermittent report of exactly that had nothing to correlate against.
            logger.warn(
                LogCategory.UI,
                "Heavyweight overlay could not measure its parent window - falling back to full screen",
                mapOf("reason" to (reason ?: "parent became null")),
            )
            return@remember null
        }
        runCatching {
            val at = window.locationOnScreen
            intArrayOf(at.x, at.y, window.width, window.height)
        }.onFailure {
            logger.warn(
                LogCategory.UI,
                "Heavyweight overlay could not measure its parent window - falling back to full screen",
                mapOf("reason" to "locationOnScreen threw", "error" to it.toString()),
            )
        }.getOrNull()
    }

/**
 * Window state for an overlay covering [bounds], or the primary screen when they are null.
 *
 * The fallback is an EXPLICIT position and size, never `WindowPlacement.Maximized`, which is
 * what this used before. Maximizing an undecorated, transparent window routes through the
 * platform's own zoom path, and on macOS a transparent window taken through it can come up
 * opaque — turning the "could not measure the parent" fallback into a solid window over
 * everything rather than merely a mis-sized transparent one. An explicit rect establishes
 * transparency identically in both paths, so the fallback degrades in one way instead of two.
 *
 * One `rememberWindowState` call, not one per branch: branching would give the two cases
 * separate composition slots and reset the state if [bounds] ever resolved late.
 */
@Composable
internal fun rememberOverlayWindowState(bounds: IntArray?): WindowState {
    val screen =
        remember {
            runCatching {
                GraphicsEnvironment
                    .getLocalGraphicsEnvironment()
                    .defaultScreenDevice
                    .defaultConfiguration
                    .bounds
            }.map { intArrayOf(it.x, it.y, it.width, it.height) }
                // Last resort only if AWT cannot describe a screen at all. A fixed rect beats
                // a zero-sized window, which would swallow the dismissing click and leave the
                // overlay unclosable.
                .getOrDefault(intArrayOf(0, 0, 1280, 800))
        }
    val rect = overlayRectOrScreen(bounds, screen)
    return rememberWindowState(
        position = WindowPosition(rect[0].dp, rect[1].dp),
        size = DpSize(rect[2].dp, rect[3].dp),
    )
}

/**
 * The rect an overlay window should occupy: the measured parent, else the screen.
 *
 * Trivial, and split out anyway so the choice is pinned by a test — composing a `Window` needs
 * a display, so this is the only part of the decision a unit test can reach, and it is the part
 * that regressed into `WindowPlacement.Maximized`.
 */
internal fun overlayRectOrScreen(
    bounds: IntArray?,
    screen: IntArray,
): IntArray = bounds ?: screen

/**
 * The rectangle the corner is resolved inside: an explicit [regionInWindow] when the caller gave
 * one, otherwise [bounds] narrowed by [inset].
 *
 * [regionInWindow] is in dp relative to the content pane, matching `HeavyweightPopup`'s
 * `anchorInWindow`, and AWT's logical units map 1:1 to dp - so this is an offset, never a scale.
 *
 * It is CLAMPED to [bounds] rather than trusted. The caller measures it from Compose layout while
 * the pane is measured from AWT, and the two can disagree for a frame during a resize or a window
 * move; an unclamped region wider than the pane would place an always-on-top overlay outside the
 * window it belongs to. A degenerate region (zero or negative after clamping) falls back to the
 * inset path, because placing the overlay at the pane corner is wrong but visible, whereas a
 * zero-sized region puts it at the pane origin with no indication why.
 */
@Suppress("ReturnCount")
internal fun resolveRegion(
    bounds: IntArray?,
    inset: DpSize,
    regionInWindow: IntRect?,
): IntArray? {
    if (bounds == null || regionInWindow == null) return insetBounds(bounds, inset)
    val left = regionInWindow.left.coerceIn(0, bounds[2])
    val top = regionInWindow.top.coerceIn(0, bounds[3])
    val width = regionInWindow.width.coerceAtMost(bounds[2] - left)
    val height = regionInWindow.height.coerceAtMost(bounds[3] - top)
    if (width <= 0 || height <= 0) return insetBounds(bounds, inset)
    return intArrayOf(bounds[0] + left, bounds[1] + top, width, height)
}

/**
 * Top-left corner, in AWT logical units, for an overlay of [size] placed at [alignment] inside
 * [bounds] - or the origin when the parent could not be measured.
 *
 * Pure so the arithmetic is pinned by a test; composing a `Window` needs a display, so this is the
 * only reachable part. Offsets are floored at zero so content larger than the parent overhangs the
 * bottom-right rather than being pushed off the top-left, where it would be unreachable.
 */
internal fun cornerPosition(
    bounds: IntArray?,
    size: DpSize,
    alignment: Alignment,
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
): Pair<Int, Int> {
    if (bounds == null) return 0 to 0
    val content = IntSize(size.width.value.roundToInt(), size.height.value.roundToInt())
    val available = IntSize(bounds[2], bounds[3])
    val offset = alignment.align(content, available, layoutDirection)
    return bounds[0] + offset.x.coerceAtLeast(0) to bounds[1] + offset.y.coerceAtLeast(0)
}

/**
 * [bounds], with [inset] taken off its END and BOTTOM edges - the sub-region a caller anchored to
 * part of the window is actually placing itself in.
 *
 * Only the far edges, and that is the whole meaning rather than a simplification. The origin is
 * where a `TopStart` overlay goes, and a caller inset from the right and the bottom has not moved
 * its top-left corner anywhere - so a near-corner anchor must be unaffected while a far-corner one
 * moves by exactly the inset. Expressing it as a smaller rectangle rather than as an offset added
 * after the fact is what gets that for free, and keeps [cornerPosition]'s floor-at-the-origin
 * behaviour applying to the region rather than to the window.
 *
 * Widths floor at zero: an inset wider than the window would otherwise produce a negative extent,
 * and [cornerPosition] would read that as slack and place the overlay outside the parent.
 *
 * A zero inset returns [bounds] ITSELF, not a copy. Every caller that predates this passes zero,
 * and the result is a `remember` key: an equal-but-new array would change identity on every
 * recomposition and re-run the placement effect, which is a native `setLocation` each time.
 */

internal fun insetBounds(
    bounds: IntArray?,
    inset: DpSize,
): IntArray? {
    // An unmeasurable parent stays unmeasurable, and a zero inset returns the SAME instance - see
    // the KDoc on identity above.
    if (bounds == null || inset == DpSize.Zero) return bounds
    return intArrayOf(
        bounds[0],
        bounds[1],
        // Rounded, not truncated: the inset is derived as px / density, which is not integral at
        // fractional scale factors, and truncating loses up to a unit per axis.
        (bounds[2] - inset.width.value.roundToInt()).coerceAtLeast(0),
        (bounds[3] - inset.height.value.roundToInt()).coerceAtLeast(0),
    )
}
