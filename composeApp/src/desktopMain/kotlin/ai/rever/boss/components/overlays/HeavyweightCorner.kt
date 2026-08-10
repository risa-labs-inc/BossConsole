package ai.rever.boss.components.overlays

import ai.rever.boss.plugin.browser.LocalAwtWindow
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import javax.swing.RootPaneContainer
import java.awt.Window as AwtWindow

/**
 * Heavyweight host for a corner overlay that outlives a keypress - toast notifications.
 *
 * **Content-sized, and that is the whole reason this exists instead of reusing a sibling.** Both
 * existing renderers are wrong here, for opposite reasons:
 *
 *  - [HeavyweightHud] is parent-sized. A non-focusable AWT window still receives mouse events and
 *    the JVM has no portable click-through, so it swallows every click underneath it. That is
 *    tolerable for the Ctrl+Tab switcher, which is up only while a key is held, and not tolerable
 *    for a toast that lingers for seconds while the user keeps working - it would make the whole
 *    window unclickable for the duration.
 *  - `HeavyweightPopup`'s scrim calls `onDismissRequest` on any click, so clicking anywhere in the
 *    app would dismiss the toast instead of reaching what was clicked.
 *
 * A window sized to its content covers only the toast itself: its own buttons still work, and every
 * click outside it reaches the app. Same trade as [HeavyweightGhost], which is content-sized for
 * the same reason.
 *
 * **Callers must compose this only while there is something to show.** Because the window eats
 * clicks wherever it sits, one composed unconditionally is a permanently dead region of the app -
 * and, being always-on-top, of whatever other application is in front.
 *
 * Two things here are deliberately not the obvious implementation, both because the obvious one
 * fails silently:
 *
 *  - Content is measured against a ceiling ([initialSize], clamped to the parent by
 *    [clampCeiling]), **never against the window's current size** (see [measuredAgainst]).
 *    Measuring against the window makes the size a one-way ratchet: the window shrinks to fit what
 *    is showing, the next toast is then measured inside that smaller window, measures clipped, and
 *    the overlay can never grow back.
 *  - Parent bounds come from the CONTENT PANE, not the window (see [contentPaneBounds]), and are
 *    re-read on the frame clock rather than remembered once.
 *
 * [inset] narrows that parent rectangle at its end and bottom edges (see [insetBounds]). It is how
 * a caller anchored to a SUB-REGION of the window - the main content area, say, rather than the
 * sidebars and status bar around it - lands where it draws on the lightweight path. It changes
 * where the overlay is placed and never how big it is, so the region whose clicks it swallows is
 * the same either way.
 */
@Composable
fun HeavyweightCorner(
    alignment: Alignment,
    initialSize: DpSize,
    inset: DpSize = DpSize.Zero,
    content: @Composable () -> Unit,
) {
    val parent = LocalAwtWindow.current
    val density = LocalDensity.current.density
    var measured by remember { mutableStateOf<DpSize?>(null) }
    val size = measured ?: initialSize
    var bounds by remember(parent) { mutableStateOf(contentPaneBounds(parent)) }
    // The rectangle the corner is actually resolved inside: the content pane, less whatever the
    // caller says is not theirs. Remembered rather than computed inline so it is a STABLE instance -
    // `bounds` only changes identity on a real change (see the frame-clock effect below), and a
    // fresh array every recomposition would re-run the placement effect, and with it a native
    // setLocation, for nothing.
    val region = remember(bounds, inset) { insetBounds(bounds, inset) }
    // Clamp the ceiling to the region. The ceiling is a hard clip, not a soft start, and toast text
    // is arbitrary plugin content: three wordy toasts can exceed a fixed height, and because the
    // window is CONTENT-sized the overflow is not cosmetic - the bottom toast's dismiss button ends
    // up outside the window, unclickable, on the INDEFINITE path where dismissing is the only way
    // out. Clamping to the region keeps the overlay inside it without reintroducing any dependency
    // on the overlay's OWN size, which is what the ratchet was.
    val ceiling = clampCeiling(initialSize, region)

    val state =
        rememberWindowState(
            size = size,
            position = cornerPosition(region, size, alignment).let { WindowPosition(it.first.dp, it.second.dp) },
        )

    // Track the parent on the frame clock. `rememberOverlayParentBounds` is keyed on the window
    // INSTANCE, which never changes, so it captures the bounds once - fine for the sub-second
    // overlays that came before, wrong for one that is up while the user can drag or resize the
    // window out from under it. Only assign on an actual change: each one is a native setLocation.
    LaunchedEffect(parent) {
        while (true) {
            withFrameNanos { }
            val next = contentPaneBounds(parent) ?: continue
            val current = bounds
            if (current == null || !next.contentEquals(current)) bounds = next
        }
    }

    // Assign window state from an effect, never during composition - writing it inline during
    // composition is what made the cursor overlay jitter.
    LaunchedEffect(size, region, alignment) {
        state.size = size
        val at = cornerPosition(region, size, alignment)
        state.position = WindowPosition(at.first.dp, at.second.dp)
    }

    Window(
        onCloseRequest = {},
        state = state,
        undecorated = true,
        transparent = true,
        alwaysOnTop = true,
        focusable = false,
        resizable = false,
    ) {
        EnsureOverlayWindowTransparent(window)
        Box(
            modifier =
                Modifier
                    // Order matters: the constraint override is OUTSIDE, so the observer inside it
                    // reports a size measured against the ceiling rather than against the window.
                    .measuredAgainst(ceiling)
                    .onGloballyPositioned { coordinates ->
                        val next =
                            DpSize(
                                (coordinates.size.width / density).dp,
                                (coordinates.size.height / density).dp,
                            )
                        // Ignore a zero measurement: it happens while the overlay is torn down, and
                        // acting on it would collapse the window and hide content still showing.
                        if (next.width.value > 0f && next.height.value > 0f && next != measured) {
                            measured = next
                        }
                    },
        ) {
            content()
        }
    }
}

/**
 * Measures content against [ceiling] rather than against the incoming constraints.
 *
 * Callers observe the result with an `onGloballyPositioned` placed INSIDE this modifier, so what it
 * reports is the ceiling-constrained size rather than whatever the window currently is.
 *
 * This is what stops the overlay's size from becoming a one-way ratchet. The natural implementation
 * - measure normally and report `onGloballyPositioned`'s size - feeds the window's own size back
 * into the measurement: once the window has shrunk to fit one toast, the next is measured inside
 * that smaller window, so it measures CLIPPED, the reported size never grows, and every later toast
 * renders squashed. Nothing warns; the window is still transparent, still correctly placed, and
 * still passes every gate.
 *
 * Measuring against a constant ceiling instead makes the answer independent of the current size, so
 * it converges rather than ratcheting. [ceiling] is therefore a hard upper bound on the overlay, not
 * merely a first guess.
 */
internal fun Modifier.measuredAgainst(ceiling: DpSize): Modifier =
    layout { measurable, _ ->
        val placeable =
            measurable.measure(
                Constraints(
                    maxWidth = ceiling.width.roundToPx(),
                    maxHeight = ceiling.height.roundToPx(),
                ),
            )
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

/**
 * The parent's CONTENT PANE bounds on screen as `[x, y, width, height]`, or null if unreadable.
 *
 * Not the window's own bounds, which is what [rememberOverlayParentBounds] returns. `BossWindow` is
 * decorated, so its frame bounds include the native title bar and borders - and for an overlay
 * anchored to a CORNER that difference is the bug, not a rounding error: anchored to the frame, a
 * top-aligned overlay sits over the title bar, and since it eats clicks, over the window controls
 * with it. The parent-sized renderers cannot see this because they cover a superset either way.
 *
 * [HeavyweightGhost] documents the same trap from the other side and avoids it by reading the cursor
 * instead of converting at all; a corner anchor has no equivalent escape, so it converts correctly.
 */
internal fun contentPaneBounds(parent: AwtWindow?): IntArray? {
    val pane = (parent as? RootPaneContainer)?.contentPane?.takeIf { it.isShowing }
    val bounds =
        pane?.let {
            runCatching { it.locationOnScreen }.getOrNull()?.let { at ->
                intArrayOf(at.x, at.y, it.width, it.height)
            }
        }
    // Loud, once. A null here does not degrade gracefully: cornerPosition falls back to 0,0, which
    // is the top-left of the PRIMARY display, so the overlay detaches from the window entirely.
    // OverlayWindowBounds makes the same condition loud for the same reason - it was silent once,
    // and an intermittent report of exactly this had nothing to correlate against.
    if (bounds == null && unmeasurableParentReported.compareAndSet(false, true)) {
        logger.warn(
            LogCategory.UI,
            "Corner overlay could not measure its parent content pane - placing at the screen origin",
            mapOf("reason" to if (pane == null) "no showing content pane" else "locationOnScreen failed"),
        )
    }
    return bounds
}

/** One warning per session for [contentPaneBounds]; it is consulted on the frame clock. */
private val unmeasurableParentReported =
    java.util.concurrent.atomic
        .AtomicBoolean(false)

private val logger = BossLogger.forComponent("HeavyweightCorner")

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
): Pair<Int, Int> {
    if (bounds == null) return 0 to 0
    val width = size.width.value.toInt()
    val height = size.height.value.toInt()
    val slackX = (bounds[2] - width).coerceAtLeast(0)
    val slackY = (bounds[3] - height).coerceAtLeast(0)
    val x =
        bounds[0] +
            when (alignment) {
                Alignment.TopStart, Alignment.CenterStart, Alignment.BottomStart -> 0
                Alignment.TopEnd, Alignment.CenterEnd, Alignment.BottomEnd -> slackX
                else -> slackX / 2
            }
    val y =
        bounds[1] +
            when (alignment) {
                Alignment.TopStart, Alignment.TopCenter, Alignment.TopEnd -> 0
                Alignment.BottomStart, Alignment.BottomCenter, Alignment.BottomEnd -> slackY
                else -> slackY / 2
            }
    return x to y
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
        (bounds[2] - inset.width.value.toInt()).coerceAtLeast(0),
        (bounds[3] - inset.height.value.toInt()).coerceAtLeast(0),
    )
}

/**
 * [initialSize], reduced to fit inside [bounds] when the parent is smaller.
 *
 * Only ever shrinks, and never consults the overlay's own current size - that dependency is exactly
 * the ratchet [measuredAgainst] exists to break. A null [bounds] leaves the ceiling alone, since an
 * unmeasurable parent says nothing about how big the content may be.
 */
internal fun clampCeiling(
    initialSize: DpSize,
    bounds: IntArray?,
): DpSize {
    if (bounds == null) return initialSize
    return DpSize(
        minOf(initialSize.width.value, bounds[2].toFloat()).dp,
        minOf(initialSize.height.value, bounds[3].toFloat()).dp,
    )
}
