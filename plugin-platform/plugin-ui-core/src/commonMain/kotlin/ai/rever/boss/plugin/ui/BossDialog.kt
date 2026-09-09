package ai.rever.boss.plugin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Whether the window hosting this composition is one that needs heavyweight overlays.
 *
 * **This declaration must stay in a file named `BossDialog.kt`.** Kotlin derives a top-level
 * declaration's JVM facade class from the FILE name, so the api dump records it as
 * `BossDialogKt.getLocalHeavyweightOverlays`. Moving it to `BossOverlayHost.kt` - its conceptual
 * home, and where a maintainer would naturally put it - renames the symbol to
 * `BossOverlayHostKt.getLocalHeavyweightOverlays`, and plugins compiled against the old name fail
 * with NoClassDefFoundError at first use while `apiCheck` stays green in the api repo. The same trap
 * applies to `shouldRouteHeavyweight` and to both `BossAlertDialog` overloads, whose `Color`
 * parameters additionally mangle their JVM names.
 *
 * Default false, provided `true` by the host's main application window only. Secondary windows
 * (Settings, the first-run setup window) contain no browser surface, and routing their dialogs
 * through an always-on-top window sized to the *main* window would place them over the wrong
 * window and leave them floating above it, since a heavyweight modal deliberately does not dismiss
 * when focus moves to another window of the same application.
 */
val LocalHeavyweightOverlays = staticCompositionLocalOf { false }

/**
 * The routing decision for any overlay that can escape into its own window, as a pure function so
 * it can be pinned by a test.
 *
 * All three conditions are required and each rules out a different failure:
 *  - [useHeavyweightOverlays]: OFF_SCREEN installs keep the lightweight path untouched.
 *  - [hasRenderer]: nothing was injected, so there is nowhere to route to.
 *  - [hostNeedsHeavyweight]: this window has no browser surface to escape (see
 *    [LocalHeavyweightOverlays]).
 *
 * Public because the host routes its own non-modal overlays (context menus, the Ctrl+Tab HUD, drag
 * ghosts) through the same rule, and duplicating it is how the three would drift apart.
 */
fun shouldRouteHeavyweight(
    useHeavyweightOverlays: Boolean,
    hasRenderer: Boolean,
    hostNeedsHeavyweight: Boolean,
): Boolean = useHeavyweightOverlays && hasRenderer && hostNeedsHeavyweight

/**
 * Scrim opacity for a heavyweight modal.
 *
 * Matches what the new-tab dialog has shipped with, and it is load-bearing evidence elsewhere: the
 * host's `EnsureOverlayWindowTransparent` identifies an overlay window that came up opaque by the
 * mid-grey this alpha composites to over an opaque light background. Changing it invalidates that
 * diagnosis.
 */
private const val SCRIM_ALPHA = 0.4f

/**
 * A modal dialog that layers correctly above a GPU-composited browser surface.
 *
 * Drop-in replacement for `androidx.compose.ui.window.Dialog`: [content] is a self-contained,
 * intrinsically-sized card, and this composable supplies the scrim and the centering on both
 * paths. On the lightweight path Compose's own `Dialog` provides them; on the heavyweight path
 * they are drawn here, so the two look the same.
 *
 * The scrim is not decoration on the heavyweight path, it is the primary dismissal mechanism: a
 * click on the page does NOT produce an AWT focus transition (Chromium's native child window takes
 * focus without one), so focus loss alone never sees it. A modal without a scrim would not dismiss
 * on an in-page click.
 *
 * @param onDismissRequest Called on Escape, on a click outside the card, or when focus leaves the
 *   application. Not called when the caller's own buttons close the dialog.
 * @param properties `dismissOnClickOutside` is honoured on both paths; `dismissOnBackPress` maps
 *   to Escape, which the heavyweight window handles itself. Every OTHER property, including
 *   `usePlatformDefaultWidth`, is IGNORED on the heavyweight path - the card is intrinsically sized
 *   there and there is no platform dialog to configure.
 */
@Composable
fun BossDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    val renderer = BossOverlayHost.modalRenderer
    val missingRenderer = BossOverlayHost.useHeavyweightOverlays && renderer == null
    // SideEffect, not a bare call: composition can be re-run, skipped or abandoned, and a report
    // should describe a composition that actually committed.
    if (missingRenderer) {
        SideEffect { BossOverlayHost.reportMissingModalRenderer() }
    }
    val heavyweight =
        shouldRouteHeavyweight(
            useHeavyweightOverlays = BossOverlayHost.useHeavyweightOverlays,
            hasRenderer = renderer != null,
            hostNeedsHeavyweight = LocalHeavyweightOverlays.current,
        )
    if (heavyweight && renderer != null) {
        renderer(properties, onDismissRequest) {
            ScrimmedModalContent(
                dismissOnClickOutside = properties.dismissOnClickOutside,
                onDismissRequest = onDismissRequest,
                content = content,
            )
        }
    } else {
        Dialog(onDismissRequest = onDismissRequest, properties = properties, content = content)
    }
}

/**
 * How long a freshly-opened heavyweight modal refuses pointer input.
 *
 * Short enough to be imperceptible, long enough to outlast the release of the click that opened the
 * dialog. See [ScrimmedModalContent] for what goes wrong without it.
 */
internal const val INPUT_ARM_DELAY_MS = 200L

/**
 * Whether a freshly-opened modal should start accepting pointer input.
 *
 * One input, deliberately: **a held button vetoes arming, whichever signal is asking.** Both callers
 * - the pointer handler and the [INPUT_ARM_DELAY_MS] timer - ask the same question, and the timer
 * having elapsed is never a reason to override a press. Writing that as a named, tested function is
 * the point; an earlier version let the timer arm unconditionally and reinstated the very bug the
 * guard exists for, only intermittently.
 */
internal fun shouldArmModalInput(pointerDown: Boolean): Boolean = !pointerDown

/**
 * Full-window scrim with the card centered on it.
 *
 * The card carries a no-op click handler so a click inside it is consumed rather than falling
 * through to the scrim and dismissing the dialog the user is filling in.
 *
 * **Input is refused until the pointer is known to be idle**, which is not belt-and-braces: a
 * heavyweight modal is a new window that appears directly UNDER the cursor, and the click that
 * opened it may still be in flight. Cmd-clicking a link in a terminal opened the link dialog with
 * the mouse button still down, and the release then landed on whichever option happened to sit
 * beneath the pointer and chose it - the user picked an option they never aimed at. The lightweight
 * path cannot do this: it draws inside the existing window, whose press it already saw.
 *
 * Two arming rules, and the ORDER of precedence between them is the whole correctness argument:
 *  - a pointer event with no button held arms it. That is the real signal, and it covers both the
 *    in-flight release and any stray movement.
 *  - the [INPUT_ARM_DELAY_MS] timer arms it ONLY IF no button is held at that moment. It exists
 *    purely for a dialog opened from the keyboard or a menu, where no pointer event may ever arrive
 *    and the first real click would otherwise be eaten.
 *
 * The timer must not arm unconditionally. A deliberate modifier-click is easily held longer than
 * 200ms, and the new window's first-frame latency counts against that budget too, so an
 * unconditional timer can expire while the button is still down - reinstating the reported bug and
 * making it rarer, which is worse than leaving it. See [shouldArmModalInput].
 */
@Composable
internal fun ScrimmedModalContent(
    dismissOnClickOutside: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scrimInteraction = remember { MutableInteractionSource() }
    val cardInteraction = remember { MutableInteractionSource() }
    var armed by remember { mutableStateOf(false) }
    // Tracks the button state the timer has to consult. Not snapshot-observed for recomposition,
    // only read inside the effect, so a plain holder would do; kept as state so the pointer handler
    // and the timer share one obvious source of truth.
    val pointerDown = remember { mutableStateOf(false) }
    // Retries rather than asking once. A single shot has no recovery path: if this window sees a
    // press whose release is delivered somewhere else - pointer dragged out and released over another
    // window or another application - `armed` would stay false forever and the loop below would eat
    // every event, leaving the dialog permanently mouse-dead with only Escape as a way out.
    LaunchedEffect(Unit) {
        while (!armed) {
            delay(INPUT_ARM_DELAY_MS)
            if (shouldArmModalInput(pointerDown.value)) armed = true
        }
    }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = SCRIM_ALPHA))
                // Initial pass, so this runs BEFORE the scrim's and the card's own handlers and can
                // take the event away from them entirely.
                .pointerInput(armed) {
                    if (armed) return@pointerInput
                    awaitPointerEventScope {
                        while (!armed) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            // Exit clears a stale press: the release for it will be delivered to
                            // whatever the pointer moved onto, never to this window.
                            pointerDown.value =
                                event.type != PointerEventType.Exit && event.changes.any { it.pressed }
                            if (shouldArmModalInput(pointerDown.value)) armed = true
                            event.changes.forEach { it.consume() }
                        }
                    }
                }.then(
                    if (dismissOnClickOutside) {
                        // canFocus = false for the same reason as the card: `clickable` installs a
                        // focus target with Enter/Space semantics, so a full-window scrim would join
                        // the dialog's traversal order and dismiss it from the keyboard - surprising
                        // in a dialog with text fields - and announce itself to accessibility.
                        Modifier
                            .focusProperties { canFocus = false }
                            .clickable(
                                interactionSource = scrimInteraction,
                                indication = null,
                                onClick = onDismissRequest,
                            )
                    } else {
                        Modifier
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .focusProperties { canFocus = false }
                    .clickable(
                        interactionSource = cardInteraction,
                        indication = null,
                        onClick = {},
                    ),
        ) {
            content()
        }
    }
}

// ---------------------------------------------------------------------------
// Alert dialog
// ---------------------------------------------------------------------------

/**
 * A title/text/buttons dialog in the BOSS design system, layered above the browser surface.
 *
 * The parameter list is deliberately Material 2's `AlertDialog`, name for name and in the same
 * order, so migrating a call site is a rename and nothing else. Material could not simply be
 * wrapped: its `AlertDialogContent` and baseline layout are `internal`, and the desktop
 * `dialogProvider` hook that once allowed exactly this was removed. Since the body had to be
 * rebuilt, it is built from the design-system tokens rather than Material's metrics - the same
 * card as `ConfirmationDialog`, so the two read as one family.
 *
 * [title] and [text] stay caller-supplied composables. This wraps them in the default type and
 * content color, so a caller that styles its own `Text` keeps whatever it set.
 *
 * @param confirmButton The primary action. Rendered last, i.e. rightmost.
 * @param dismissButton The secondary action, rendered to the left of [confirmButton].
 * @param text The body. **When the card's parent bounds its height, the body is placed in a scroll
 * container and therefore measured with an UNBOUNDED height** - that is the bounded-parent branch,
 * not the unbounded one, and it is the branch most alerts take. A composable that refuses an
 * unbounded main axis - a `LazyColumn`, a `LazyVerticalGrid`, a nested `verticalScroll` - will not
 * lay out there unless it caps itself, so give any such content a `Modifier.heightIn(max = …)`.
 * This is the one thing about this card a caller has to know.
 * @param shape Card shape; null takes the design system's dialog radius.
 * @param backgroundColor Card fill; [Color.Unspecified] takes the theme's panel color.
 * @param contentColor Default content color; [Color.Unspecified] takes the theme's primary text.
 */
@Composable
fun BossAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape? = null,
    backgroundColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    properties: DialogProperties = DialogProperties(),
) {
    BossAlertDialog(
        onDismissRequest = onDismissRequest,
        buttons = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (dismissButton != null) {
                    dismissButton()
                    Spacer(Modifier.width(BossTheme.space.sm))
                }
                confirmButton()
            }
        },
        modifier = modifier,
        title = title,
        text = text,
        shape = shape,
        backgroundColor = backgroundColor,
        contentColor = contentColor,
        properties = properties,
    )
}

/**
 * [BossAlertDialog] with the button area fully under the caller's control.
 *
 * Mirrors Material 2's `buttons` overload. Use it when the actions are not a confirm/dismiss pair:
 * three buttons, a progress row, or no buttons at all.
 *
 * @param text The body. Measured with an unbounded height on the bounded-parent branch, so content
 * that refuses that must cap itself - see the `confirmButton` overload's `@param text`, which this
 * deliberately does not restate.
 */
@Composable
fun BossAlertDialog(
    onDismissRequest: () -> Unit,
    buttons: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape? = null,
    backgroundColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    properties: DialogProperties = DialogProperties(),
) {
    BossDialog(onDismissRequest = onDismissRequest, properties = properties) {
        BossAlertCard(
            buttons = buttons,
            modifier = modifier,
            title = title,
            text = text,
            shape = shape,
            backgroundColor = backgroundColor,
            contentColor = contentColor,
        )
    }
}

// ---------------------------------------------------------------------------
// Anchored popup
// ---------------------------------------------------------------------------

/**
 * An anchored, non-modal overlay that layers correctly above a GPU-composited browser surface.
 *
 * Same shape as `androidx.compose.ui.window.Popup` for the parameters it exposes, and the
 * counterpart to [BossDialog] for
 * everything that is not a modal: a context menu, a dropdown, an autocomplete list. `BossDialog`
 * cannot stand in for these - it centers its content, draws a scrim, and takes focus.
 *
 * The surface is deliberately narrower than `Popup`: no `alignment`, and `PopupProperties` is
 * constructed internally. That is a decision rather than an oversight, because adding a parameter to
 * this `@Composable` later is pinned by the binary-compatibility validator in two repos and costs a
 * coordinated host and api release.
 *
 * **[focusable] is the parameter to think about, and it defaults to `false` to match `Popup`.** A
 * suggestion list under a text field needs `false`, or the popup steals focus and the user cannot
 * keep typing. A menu that handles its own arrow keys wants `true` and must say so. The default
 * deliberately mirrors `PopupProperties()` rather than being chosen on merit: this is documented as a
 * rename-only migration, and a default that disagreed with `Popup` would make a mechanical rename
 * silently start stealing focus - the exact failure this parameter exists to prevent.
 *
 * **Dismissal with `focusable = false` is weaker on the lightweight path.** A non-focusable desktop
 * `Popup` does not receive key events, so Escape cannot reach it, and outside-click dismissal is tied
 * to focusability there. The heavyweight renderer owns dismissal itself and is unaffected. Since the
 * api jar's own body is the lightweight path on an older host, a `focusable = false` popup should not
 * rely on Escape - drive it from the anchoring control's own key handling, as the URL bar does.
 *
 * [offset] is relative to the anchoring layout. It is honoured exactly on the lightweight path; the
 * heavyweight renderer currently prefers the cursor, because converting a layout-relative offset to
 * screen coordinates has to go through the content pane rather than the window and is off by the
 * title-bar height otherwise. For a menu opened by a click the two coincide. For a control anchored
 * somewhere the pointer is not - a suggestion list under a text field being the case that exposes it
 * - the cursor currently wins, and that is a host-side limitation rather than part of this contract:
 * the parameter is already here, so a renderer that learns true window-space anchoring needs no api
 * change. Do not rely on cursor placement.
 *
 * @param onDismissRequest Called on a click outside, on Escape, or when focus leaves the
 *   application - subject to the `focusable = false` caveat above on the lightweight path.
 */
@Composable
fun BossPopup(
    onDismissRequest: () -> Unit,
    offset: IntOffset = IntOffset.Zero,
    focusable: Boolean = false,
    anchoring: BossPopupAnchoring = BossPopupAnchoring.Cursor,
    content: @Composable () -> Unit,
) {
    val renderer = BossOverlayHost.popupRenderer
    if (BossOverlayHost.useHeavyweightOverlays && renderer == null) {
        SideEffect { BossOverlayHost.reportMissingPopupRenderer() }
    }
    val heavyweight =
        shouldRouteHeavyweight(
            useHeavyweightOverlays = BossOverlayHost.useHeavyweightOverlays,
            hasRenderer = renderer != null,
            hostNeedsHeavyweight = LocalHeavyweightOverlays.current,
        )
    // A zero-size probe that reports where this popup sits in the window, so
    // BossPopupAnchoring.AnchorBounds has something real to anchor to. Measured here rather than
    // asked of the caller: a caller cannot convert its own layout position into window space without
    // reaching for LocalAwtWindow, which plugin code should not have to do. The Box contributes no
    // size, so it is layout-neutral wherever it is placed - see the layout modifier below, which is
    // what makes that true rather than merely intended.
    // Position and width arrive from two different callbacks with no guaranteed order between
    // them, so neither computes the rect. Both are state, and the rect is DERIVED during
    // composition - which also means a later measurement updates it, rather than whichever
    // callback happened to run last winning.
    val density = LocalDensity.current.density
    // NULL until measured, which is a distinct state from "measured at the origin". The renderer used
    // to be invoked on the very first composition, before onGloballyPositioned had run, so an anchored
    // popup was placed at the window origin with no width and then snapped into place - a visible
    // flash in the top-left corner for as long as the overlay window took to appear.
    var anchorPositionPx by remember { mutableStateOf<Offset?>(null) }
    var measuredWidthPx by remember { mutableStateOf(0) }
    val anchorInWindow =
        anchorPositionPx?.let { anchorRectInDp(it, IntSize(measuredWidthPx, 0), density) }
    Box(
        modifier =
            Modifier
                // Measure at the caller's full width, then report 0x0 to the parent.
                //
                // The width has to be ADOPTED rather than read back: walking up with
                // parentLayoutCoordinates does not reliably land on the caller's layout - modifier
                // nodes have coordinates of their own - and returned zero, which let the content
                // inherit the overlay window's width instead of the anchor's.
                //
                // But adopting it with fillMaxWidth() is NOT layout-neutral, despite reporting no
                // height: fillMaxWidth sets minWidth = maxWidth, so the probe claims the full width
                // from its parent. In a Row it starves later siblings; in a Column with
                // Arrangement.spacedBy it adds a phantom gap; and on the lightweight path it becomes
                // the Popup's anchor parent, changing placement even for OFF_SCREEN installs that
                // never route heavyweight. Compose's own Popup emits a genuine 0x0 node, and this is
                // documented as a drop-in for it, so it must too. Measuring and then reporting 0x0
                // gets the width without any of that.
                //
                // ORDER MATTERS. onGloballyPositioned must sit OUTSIDE the layout modifier: modifiers
                // wrap left to right, so placing it inside made it observe the collapsed content
                // rather than the placed node, and it reported the origin with zero size. Outside, it
                // sees the node as the PARENT sees it - correct position, zero size - and the width
                // comes from the measurement captured within.
                .onGloballyPositioned { coordinates ->
                    anchorPositionPx = coordinates.positionInWindow()
                }.layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    // The CONSTRAINT, not the placeable. On the heavyweight path this Box has no
                    // inline content - the renderer opens a window - so the placeable measures 0 and
                    // the anchor would report no width at all. maxWidth is what the caller is
                    // offering, which is the width the popup should adopt. Unbounded (a scrolling
                    // parent) has no such answer, so fall back to whatever the content measured.
                    measuredWidthPx =
                        if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
                    // Report 0x0 but still PLACE the child: the lightweight path nests a real Popup
                    // in here, and an unplaced subtree would never compose it.
                    layout(0, 0) { placeable.place(0, 0) }
                },
    ) {
        // An anchored popup waits for its anchor. Cursor anchoring never reads it, so that path must
        // NOT wait - it would delay every context menu by a frame for no reason.
        val placeable =
            anchoring != BossPopupAnchoring.AnchorBounds || anchorInWindow != null
        if (heavyweight && renderer != null && placeable) {
            renderer(
                onDismissRequest,
                anchorInWindow ?: IntRect.Zero,
                anchoring,
                offset,
                focusable,
                content,
            )
        } else if (!heavyweight || renderer == null) {
            Popup(
                onDismissRequest = onDismissRequest,
                offset = offset,
                properties = PopupProperties(focusable = focusable),
                content = content,
            )
        }
    }
}

/**
 * The anchor rect converted from Compose PIXELS to AWT logical units (dp).
 *
 * The unit change is the entire point and is easy to miss: layout coordinates are in pixels, while the
 * host places overlay content with `absoluteOffset(x.dp, y.dp)` in logical units. Passing pixels
 * straight through put the URL-bar suggestion list at roughly double its intended position on a 2x
 * display - correct on a 1x screen, visibly wrong on every Retina one, which is exactly the class of
 * bug the host's own popup code already carries a warning about.
 *
 * Takes position and size rather than a Rect because the caller must use `positionInWindow()`, which
 * is UNCLIPPED. `boundsInWindow()` clips, and a zero-size probe then collapses to an empty rect at the
 * origin - which placed the suggestion list in the screen's top-left corner rather than under the URL
 * bar.
 *
 * Pure, and separate from the composable, so the conversion is pinned by a test at more than one
 * scale factor rather than only by looking at a 1x screen.
 */
internal fun anchorRectInDp(
    positionPx: Offset,
    sizePx: IntSize,
    density: Float,
): IntRect {
    if (density <= 0f || !positionPx.isValid()) return IntRect.Zero
    val left = (positionPx.x / density).roundToInt()
    val top = (positionPx.y / density).roundToInt()
    return IntRect(
        left = left,
        top = top,
        right = left + (sizePx.width / density).roundToInt(),
        bottom = top + (sizePx.height / density).roundToInt(),
    )
}

/** Guards against the Unspecified/NaN offset a detached or not-yet-placed layout reports. */
private fun Offset.isValid(): Boolean = !x.isNaN() && !y.isNaN()

/**
 * Where a [BossPopup] places itself on the heavyweight path.
 *
 * The lightweight path always anchors to the calling layout, because that is what Compose's `Popup`
 * does; this only distinguishes the two on the heavyweight path, where the overlay is its own window
 * and something has to choose.
 */
enum class BossPopupAnchoring {
    /**
     * At the pointer. Correct for anything the user opened by clicking - a context menu, a
     * right-click menu - where the cursor IS the intended position and no coordinate conversion is
     * needed. This is the default because it is the common case and the safe one.
     */
    Cursor,

    /**
     * Below the calling layout, wherever that sits in the window.
     *
     * For a control anchored to something other than the pointer: a suggestion list under a URL bar
     * is the case that forced this to exist, since the user is typing and the cursor may be anywhere
     * on screen. Costs a window-space conversion the host has to get right - see the content-pane
     * note in `HeavyweightPopup` - which is why it is opt-in rather than the default.
     */
    AnchorBounds,
}

// Treat this enum as OPEN when matching on it: it crosses the plugin boundary and the host compiles
// its own copy, so a third constant added later reaches plugins built against two constants. Always
// include an else branch, for the same reason `LlmApiFormat` documents in the api repo.
