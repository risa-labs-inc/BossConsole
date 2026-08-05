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
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Whether the window hosting this composition is one that needs heavyweight overlays.
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
    if (BossOverlayHost.useHeavyweightOverlays && renderer == null) {
        BossOverlayHost.reportMissingModalRenderer()
    }
    val heavyweight =
        shouldRouteHeavyweight(
            useHeavyweightOverlays = BossOverlayHost.useHeavyweightOverlays,
            hasRenderer = renderer != null,
            hostNeedsHeavyweight = LocalHeavyweightOverlays.current,
        )
    if (heavyweight && renderer != null) {
        renderer(onDismissRequest) {
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
 * Full-window scrim with the card centered on it.
 *
 * The card carries a no-op click handler so a click inside it is consumed rather than falling
 * through to the scrim and dismissing the dialog the user is filling in.
 */
@Composable
private fun ScrimmedModalContent(
    dismissOnClickOutside: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scrimInteraction = remember { MutableInteractionSource() }
    val cardInteraction = remember { MutableInteractionSource() }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = SCRIM_ALPHA))
                .then(
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
                Modifier.clickable(
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
