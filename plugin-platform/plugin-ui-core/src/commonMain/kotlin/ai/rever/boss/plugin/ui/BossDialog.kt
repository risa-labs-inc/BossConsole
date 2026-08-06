package ai.rever.boss.plugin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.LocalContentColor
import androidx.compose.material.ProvideTextStyle
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

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
 *   to Escape, which the heavyweight window handles itself.
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
    LaunchedEffect(Unit) {
        delay(INPUT_ARM_DELAY_MS)
        if (shouldArmModalInput(pointerDown.value)) armed = true
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
                            pointerDown.value = event.changes.any { it.pressed }
                            if (shouldArmModalInput(pointerDown.value)) armed = true
                            event.changes.forEach { it.consume() }
                        }
                    }
                }.then(
                    if (dismissOnClickOutside) {
                        Modifier.clickable(
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

/** Width of a BOSS alert card, matching the house confirmation dialog. */
private val AlertWidth: Dp = 400.dp

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
    val colors = BossTheme.colors
    val space = BossTheme.space
    BossDialog(onDismissRequest = onDismissRequest, properties = properties) {
        Surface(
            modifier =
                modifier
                    .width(AlertWidth)
                    .wrapContentHeight(),
            shape = shape ?: BossTheme.radius.dialogShape,
            color = backgroundColor.takeOrElse { colors.panel },
            contentColor = contentColor.takeOrElse { colors.textPrimary },
        ) {
            Column(modifier = Modifier.padding(space.xl)) {
                if (title != null) {
                    CompositionLocalProvider(LocalContentColor provides colors.textPrimary) {
                        ProvideTextStyle(BossTheme.type.title, title)
                    }
                }
                if (title != null && text != null) {
                    Spacer(Modifier.height(space.md))
                }
                if (text != null) {
                    CompositionLocalProvider(LocalContentColor provides colors.textSecondary) {
                        ProvideTextStyle(BossTheme.type.body, text)
                    }
                }
                Spacer(Modifier.height(space.xl))
                buttons()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Anchored popup
// ---------------------------------------------------------------------------

/**
 * An anchored, non-modal overlay that layers correctly above a GPU-composited browser surface.
 *
 * Drop-in replacement for `androidx.compose.ui.window.Popup`, and the counterpart to [BossDialog] for
 * everything that is not a modal: a context menu, a dropdown, an autocomplete list. `BossDialog`
 * cannot stand in for these - it centers its content, draws a scrim, and takes focus.
 *
 * **[focusable] is the parameter to think about.** A suggestion list under a text field must pass
 * `false`, or the popup steals focus from the field and the user cannot keep typing. A menu that
 * handles its own arrow keys wants `true`.
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
 * @param onDismissRequest Called on a click outside, on Escape, or when focus leaves the application.
 */
@Composable
fun BossPopup(
    onDismissRequest: () -> Unit,
    offset: IntOffset = IntOffset.Zero,
    focusable: Boolean = true,
    content: @Composable () -> Unit,
) {
    val renderer = BossOverlayHost.popupRenderer
    val heavyweight =
        shouldRouteHeavyweight(
            useHeavyweightOverlays = BossOverlayHost.useHeavyweightOverlays,
            hasRenderer = renderer != null,
            hostNeedsHeavyweight = LocalHeavyweightOverlays.current,
        )
    if (heavyweight && renderer != null) {
        renderer(onDismissRequest, offset, focusable, content)
    } else {
        Popup(
            onDismissRequest = onDismissRequest,
            offset = offset,
            properties = PopupProperties(focusable = focusable),
            content = content,
        )
    }
}
