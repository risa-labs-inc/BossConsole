package ai.rever.boss.components.overlays

import ai.rever.boss.plugin.browser.LocalAwtWindow
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.ApplyBossWindowIcon
import ai.rever.boss.window.BossWindowIcon
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
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import java.awt.Dialog
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.RootPaneContainer
import kotlin.math.roundToInt
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
 *  - Content is measured against a ceiling sized to the parent region (see [regionCeiling]),
 *    **never against the window's current size** (see [measuredAgainst]).
 *    Measuring against the window makes the size a one-way ratchet: the window shrinks to fit what
 *    is showing, the next toast is then measured inside that smaller window, measures clipped, and
 *    the overlay can never grow back.
 *  - Parent bounds come from the CONTENT PANE, not the window (see [contentPaneBounds]), and are
 *    re-read as the window moves rather than remembered once (see [trackedContentPaneBounds]).
 *
 * **No window is composed until the parent has been measured.** `cornerPosition(null, ...)` returns
 * the screen origin, so composing through that gap put an undecorated always-on-top overlay at the
 * top-left of the PRIMARY monitor - over whatever was there - until the first retry tick. That gap
 * is not hypothetical: the focus-mode quick actions mount on the very first composition of a window,
 * routinely before the AWT content pane is showing. The frame clock used to paper over it by
 * correcting within a frame; the retry takes [MEASURE_RETRY_MS], so it had to stop being papered
 * over. If the retry gives up entirely, staying hidden is the deliberate choice: a permanently
 * misplaced always-on-top window is worse than none, and every caller only composes this while its
 * own window is focused, so a measurable pane is the normal case rather than the lucky one.
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
    focusable: Boolean = false,
    regionInWindow: IntRect? = null,
    content: @Composable () -> Unit,
) {
    val parent = LocalAwtWindow.current
    val density = LocalDensity.current.density
    var measured by remember { mutableStateOf<DpSize?>(null) }
    val size = measured ?: initialSize
    val bounds = trackedContentPaneBounds(parent) ?: return
    // The rectangle the corner is actually resolved inside: the content pane, less whatever the
    // caller says is not theirs. Remembered rather than computed inline so it is a STABLE instance -
    // `bounds` only changes identity on a real change (see [trackedContentPaneBounds]), and a fresh
    // array every recomposition would re-run the placement effect, and with it a native
    // setLocation, for nothing.
    val region = remember(bounds, inset, regionInWindow) { resolveRegion(bounds, inset, regionInWindow) }
    // The measurement ceiling is the REGION itself - the whole parent content pane - not the
    // first-frame [initialSize]. The ceiling is a hard clip, and toast text is arbitrary plugin
    // content: three wordy toasts can exceed a fixed height like 600dp, and because the window is
    // CONTENT-sized the overflow is not cosmetic - the bottom toast's dismiss button ends up outside
    // the window, unclickable, on the INDEFINITE path where dismissing is the only way out. Sizing
    // the ceiling to the region lets content grow to whatever the parent can actually show while
    // [initialSize] stays a small first-frame placeholder; the two were one number before (#154). It
    // never depends on the overlay's OWN size, which is what the ratchet was.
    val ceiling = regionCeiling(region, initialSize)

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

    // What the overlay measures, shared by both hosts below so the sizing rules cannot diverge.
    val measuringContent: @Composable () -> Unit = {
        MeasuredAgainstCeiling(
            ceiling = ceiling,
            density = density,
            onMeasured = { measured = it },
            current = measured,
            content = content,
        )
    }

    // A FOCUSABLE overlay is hosted by a dialog OWNED by the parent, not by a top-level window, and
    // the difference is three behaviours rather than a preference:
    //
    //  - **The owner stays active.** An unowned top-level window taking focus deactivates the main
    //    window, and things branch on that: `FocusModeQuickActions` renders its LIGHTWEIGHT path
    //    when the window is unfocused, which under HARDWARE_ACCELERATED draws behind the Chromium
    //    surface - so the quick-actions cluster disappeared for as long as the find bar was up, in
    //    the one configuration it exists for.
    //  - **App shortcuts keep routing.** `AWTKeyboardInterceptor.findWindowId` walks `owner`, so an
    //    owned dialog resolves to its window's id; an unowned one resolves to nothing and every
    //    keymap binding goes dead while the overlay holds focus.
    //  - **No taskbar button and no Alt-Tab card.** `WindowIcon.kt` documents this as the reason the
    //    find bar was a `JDialog` before it was Compose; an owned dialog keeps that property, a
    //    plain `Frame` does not.
    //
    // Non-focusable callers (toasts, the focus-mode cluster) keep the top-level window they have
    // always had, so none of this can regress them.
    if (focusable && parent != null) {
        OwnedCornerDialog(
            parent = parent,
            state = state,
            content = measuringContent,
        )
        return
    }

    Window(
        onCloseRequest = {},
        state = state,
        undecorated = true,
        transparent = true,
        alwaysOnTop = true,
        focusable = focusable,
        resizable = false,
        icon = BossWindowIcon.painter,
    ) {
        EnsureOverlayWindowTransparent(window, kind = "corner")
        ApplyBossWindowIcon(window)
        measuringContent()
    }
}

/**
 * Reports [content]'s size measured against [ceiling] rather than against the window it is in.
 *
 * Extracted from [HeavyweightCorner] so each host branch reads as a host and this reads as the
 * sizing rule. See [measuredAgainst] for why the ceiling, and not the window, is what content is
 * measured against.
 */
@Composable
private fun MeasuredAgainstCeiling(
    ceiling: DpSize,
    density: Float,
    current: DpSize?,
    onMeasured: (DpSize) -> Unit,
    content: @Composable () -> Unit,
) {
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
                    if (next.width.value > 0f && next.height.value > 0f && next != current) {
                        onMeasured(next)
                    }
                },
    ) {
        content()
    }
}

/**
 * Hosts [content] in an undecorated, transparent, MODELESS dialog owned by [parent].
 *
 * Uses the low-level `DialogWindow` overload because the properties that make this worth doing have
 * to be set on the AWT dialog itself, and two of them must be set BEFORE it becomes displayable:
 * `type` throws once the dialog has been shown, and a transparent background needs `isUndecorated`
 * already true. `create` is the only hook that runs early enough.
 *
 * `isAlwaysOnTop` is deliberately NOT set. An owned dialog already floats above its owner, which is
 * the whole requirement (Chromium's surface is a native child of that same owner); always-on-top
 * would additionally raise it above every OTHER application, which a find bar has no business doing.
 */
@Composable
private fun OwnedCornerDialog(
    parent: AwtWindow,
    state: WindowState,
    content: @Composable () -> Unit,
) {
    DialogWindow(
        create = {
            ComposeDialog(parent, Dialog.ModalityType.MODELESS).apply {
                // Before anything shows it: setType throws on a displayable window, and
                // setBackground with an alpha is ignored while the dialog is still decorated.
                isUndecorated = true
                runCatching { type = AwtWindow.Type.UTILITY }
                isResizable = false
                background = java.awt.Color(0, 0, 0, 0)
            }
        },
        dispose = ComposeDialog::dispose,
        update = { dialog ->
            // Position and size come from the same WindowState the top-level branch drives, so the
            // corner maths has exactly one implementation.
            val at = state.position
            if (at is WindowPosition.Absolute) {
                dialog.setLocation(at.x.value.toInt(), at.y.value.toInt())
            }
            dialog.setSize(
                state.size.width.value
                    .toInt(),
                state.size.height.value
                    .toInt(),
            )
        },
    ) {
        EnsureOverlayWindowTransparent(window, kind = "corner-dialog")
        // Branded even though an owned UTILITY dialog shows no icon surface. Two reasons, and the
        // second is why this is not an exemption: `type` is set through runCatching, so a platform
        // that refuses it leaves an ordinary dialog that DOES have one - and reasoning "this window
        // does not need branding" is precisely how nine windows shipped wearing the JDK coffee cup.
        // WindowIconConventionTest enforces this for every DialogWindow call site.
        ApplyBossWindowIcon(window)
        content()
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
    // No parent at all is host wiring - LocalAwtWindow unprovided, which is what a test host or a
    // headless entry point looks like - not an overlay that failed to measure a window it had. That
    // condition exhausts all MEASURE_ATTEMPTS by construction, so reporting it would reliably spend
    // the one-per-session flag that a real locationOnScreen failure later needs.
    if (parent == null) return
    if (!unmeasurableParentReported.compareAndSet(false, true)) return
    val pane = (parent as? RootPaneContainer)?.contentPane
    logger.warn(
        LogCategory.UI,
        "Corner overlay could not measure its parent content pane - placing at the screen origin",
        mapOf(
            "reason" to
                when {
                    pane == null -> "window has no content pane"
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
 * The measurement ceiling: the parent [region] itself, in dp - or [fallback] when the parent could
 * not be measured.
 *
 * The ceiling is a HARD clip on content (see [measuredAgainst]), so it must be as large as the
 * parent can actually show. It is deliberately NOT the overlay's first-frame size: tying the two
 * together clipped content at that size, and because the window is content-sized the clip pushed a
 * toast's dismiss button outside the window on the INDEFINITE path, where it was unclickable and the
 * toast was stuck (#154). It never consults the overlay's own current size - that dependency is the
 * ratchet [measuredAgainst] exists to break. A null [region] falls back to [fallback], since an
 * unmeasurable parent says nothing about how big the content may be - the choice the previous clamp
 * made too.
 *
 * [region] is `[x, y, width, height]` in AWT logical units, which map 1:1 to dp, so width and height
 * carry straight across with no density conversion - the same unit contract [cornerPosition] relies
 * on. A taller stack also owns a taller click-catching region. Content beyond the parent height
 * still requires a scrolling or bounded toast layout; raising this ceiling alone cannot expose it.
 * The null fallback is defensive: HeavyweightCorner returns before composing when bounds are absent.
 */
internal fun regionCeiling(
    region: IntArray?,
    fallback: DpSize,
): DpSize {
    if (region == null) return fallback
    return DpSize(region[2].dp, region[3].dp)
}
