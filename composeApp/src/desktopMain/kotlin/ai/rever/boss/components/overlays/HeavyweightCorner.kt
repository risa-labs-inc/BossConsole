package ai.rever.boss.components.overlays

import ai.rever.boss.plugin.browser.LocalAwtWindow
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.delay
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.RootPaneContainer
import java.awt.Window as AwtWindow

/** How long to wait between attempts to measure a parent that is not showing yet. */
private const val MEASURE_RETRY_MS = 50L

/**
 * How many measurement attempts before giving up, so a parent that never becomes measurable costs a
 * bounded number of wakeups rather than one per [MEASURE_RETRY_MS] for the session.
 */
internal const val MEASURE_ATTEMPTS = 100

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
 *    re-read as the window moves rather than remembered once (see [trackedContentPaneBounds]).
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
    val bounds = trackedContentPaneBounds(parent)
    // The rectangle the corner is actually resolved inside: the content pane, less whatever the
    // caller says is not theirs. Remembered rather than computed inline so it is a STABLE instance -
    // `bounds` only changes identity on a real change (see [trackedContentPaneBounds]), and a fresh
    // array every recomposition would re-run the placement effect, and with it a native
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
 * The parent content pane's screen bounds, kept current as the window moves and resizes.
 *
 * Split out of [HeavyweightCorner] so the tracking is one idea in one place, and because the two
 * effects below are the whole of it.
 */
@Composable
private fun trackedContentPaneBounds(parent: AwtWindow?): IntArray? {
    var bounds by remember(parent) { mutableStateOf(contentPaneBounds(parent)) }

    // Track the parent. `rememberOverlayParentBounds` is keyed on the window INSTANCE, which never
    // changes, so it captures the bounds once - fine for the sub-second overlays that came before,
    // wrong for one that is up while the user can drag or resize the window out from under it.
    // Only assign on an actual change: each one is a native setLocation.
    //
    // Event-driven, NOT polled on the frame clock. This used to be `while (true) { withFrameNanos
    // { } ... }`, and a registered frame awaiter keeps the Compose scene from ever going idle - a
    // full repaint plus a native locationOnScreen every frame, for a rectangle that changes only
    // when the user moves or resizes the window. That was affordable when the only caller was a
    // toast up for a few seconds; the focus-mode quick actions are up for the whole focus-mode
    // session, which on the configuration they target (auto-reveal off, so the top bar stays
    // cleared) is the whole time the app is open.
    //
    // Two listeners, because they see different events. The WINDOW reports its own moves and
    // resizes; the content pane reports resizes that are not the window's, such as a menu bar
    // appearing. The pane never reports a MOVE when the window is dragged - its position inside
    // the window has not changed - so the window listener is the one that cannot be dropped.
    DisposableEffect(parent) {
        val pane = (parent as? RootPaneContainer)?.contentPane

        fun refresh() {
            val next = contentPaneBounds(parent) ?: return
            if (boundsChanged(bounds, next)) bounds = next
        }

        val listener =
            object : ComponentAdapter() {
                override fun componentMoved(e: ComponentEvent?) = refresh()

                override fun componentResized(e: ComponentEvent?) = refresh()

                override fun componentShown(e: ComponentEvent?) = refresh()
            }
        parent?.addComponentListener(listener)
        pane?.addComponentListener(listener)
        onDispose {
            parent?.removeComponentListener(listener)
            pane?.removeComponentListener(listener)
        }
    }

    // Listeners fire on a CHANGE, so a parent that is not yet showing when this mounts would never
    // be measured at all: `contentPaneBounds` returns null until the pane is showing, and
    // `cornerPosition` then falls back to the screen origin, detaching the overlay from the window
    // entirely. The frame-clock loop covered that by accident, retrying every frame until the pane
    // appeared - the one thing lost by going event-driven, so it is restored deliberately rather
    // than left to chance.
    //
    // Bounded twice over: it stops at the first successful measurement, and gives up after
    // [MEASURE_ATTEMPTS] regardless. Without the cap, a null parent - `LocalAwtWindow` unprovided,
    // which is what a test host looks like - would leave a wakeup timer running for the whole
    // session, which is the shape of the problem this whole change exists to remove.
    // Through the same guard the listeners use, not a bare assignment. The two writers interleave:
    // this one sleeps for MEASURE_RETRY_MS, and a componentShown/componentResized landing inside
    // that window stores a good rectangle which a bare `bounds = contentPaneBounds(parent)` would
    // then overwrite - with null, if the pane happens not to be showing at that instant, which puts
    // the overlay at the primary display's origin. Even when it succeeds it would store an
    // equal-but-fresh IntArray, and since IntArray equality is by reference that reads as a change:
    // new region identity, placement effect restart, native setLocation.
    LaunchedEffect(parent) {
        var attempts = 0
        while (shouldKeepMeasuring(bounds, attempts)) {
            delay(MEASURE_RETRY_MS)
            contentPaneBounds(parent)?.let { next ->
                if (boundsChanged(bounds, next)) bounds = next
            }
            attempts++
        }
        // Only now is an unmeasurable parent a real finding rather than a slow one.
        if (bounds == null) reportUnmeasurableParent(parent)
    }

    return bounds
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
    return pane?.let {
        runCatching { it.locationOnScreen }.getOrNull()?.let { at ->
            intArrayOf(at.x, at.y, it.width, it.height)
        }
    }
}

/**
 * Report, once per session, that the parent could never be measured.
 *
 * Loud, because this does not degrade gracefully: [cornerPosition] falls back to 0,0, which is the
 * top-left of the PRIMARY display, so the overlay detaches from the window entirely.
 * `OverlayWindowBounds` makes the same condition loud for the same reason - it was silent once, and
 * an intermittent report of exactly this had nothing to correlate against.
 *
 * Called only when the retry gives up, never from [contentPaneBounds] itself, and that distinction
 * is now load-bearing. A single unmeasurable read is no longer evidence of anything: the quick
 * actions mount on the FIRST composition of a window in focus mode, routinely before the content
 * pane is showing, and the retry repairs it 50ms later. Warning on the read would spend the
 * one-per-session flag on that transient for precisely the users this overlay was built for, and a
 * genuine failure later in the same session would then be silent - which is the failure mode the
 * flag exists to prevent, inverted.
 */
private fun reportUnmeasurableParent(parent: AwtWindow?) {
    if (!unmeasurableParentReported.compareAndSet(false, true)) return
    val pane = (parent as? RootPaneContainer)?.contentPane
    logger.warn(
        LogCategory.UI,
        "Corner overlay could not measure its parent content pane - placing at the screen origin",
        mapOf(
            "reason" to
                when {
                    pane == null -> "no content pane"
                    !pane.isShowing -> "content pane never started showing"
                    else -> "locationOnScreen failed"
                },
            "attempts" to MEASURE_ATTEMPTS.toString(),
        ),
    )
}

/** One warning per session for [reportUnmeasurableParent]; every overlay would otherwise report. */
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
 * Whether [next] is worth storing over [current].
 *
 * Named and pure because the alternative is invisible: a listener fires on every step of a window
 * drag, and each assignment is a native `setLocation` on the overlay. Assigning unconditionally
 * still *looks* right on screen, so nothing but a test distinguishes it from this.
 */
internal fun boundsChanged(
    current: IntArray?,
    next: IntArray,
): Boolean = current == null || !next.contentEquals(current)

/**
 * Whether the mount-time measurement retry should run again, given [bounds] so far and how many
 * [attempts] have been made.
 *
 * Two terminating conditions and both matter. The first measurement ends it, which is the normal
 * case. The cap ends it when no measurement is ever going to land - a null parent, which is what a
 * test host or a headless entry point looks like - so the fallback for a window that has not
 * appeared yet cannot become a wakeup timer that outlives the session. That would be the same
 * always-awake cost this renderer moved off the frame clock to escape.
 */
internal fun shouldKeepMeasuring(
    bounds: IntArray?,
    attempts: Int,
): Boolean = bounds == null && attempts < MEASURE_ATTEMPTS

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
