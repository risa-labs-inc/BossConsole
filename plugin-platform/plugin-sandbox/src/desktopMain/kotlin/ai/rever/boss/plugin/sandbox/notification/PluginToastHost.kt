package ai.rever.boss.plugin.sandbox.notification

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val toastLogger = BossLogger.forComponent("PluginToastHost")

/**
 * Whether the "Clear all" control belongs on screen for [toastCount] visible toasts.
 *
 * Only past a single toast: with one toast its own dismiss button already clears everything, so a
 * second control saying the same thing would be noise.
 */
internal fun shouldShowClearAllControl(toastCount: Int): Boolean = toastCount >= 2

/**
 * The line caps on a single toast's plugin-controlled text.
 *
 * A toast's `title`, `message`, and optional action label are plugin-controlled strings of unbounded
 * length. Without caps, a single verbose toast - or, worse, a stack of [PluginToastState]'s `maxToasts`
 * (3) INDEFINITE ones, which are dismissed only by hand - can grow past the parent content pane. The
 * overflow is not cosmetic: a dismiss button pushed off-window cannot be clicked (BossConsole#154).
 *
 * These caps bound each toast's contribution to the stack independently of its width. The parent pane
 * remains the actual measurement ceiling, so an unusually short pane can still require a broader
 * scrolling or stack-layout solution. Detail text gets more lines than labels; all overflow ends in
 * an ellipsis rather than growing the toast indefinitely.
 */
internal const val TOAST_TITLE_MAX_LINES = 2
internal const val TOAST_MESSAGE_MAX_LINES = 6
internal const val TOAST_ACTION_MAX_LINES = 1

/**
 * Host composable for displaying plugin toast notifications.
 *
 * Place this at the root of your composition (e.g., in a Box with alignment)
 * to display toast notifications from the plugin sandbox system.
 *
 * Toasts use the shared BOSS theme ([BossThemeColors]) so they match the
 * Settings window and other BOSS dialogs: a single dark surface with a thin
 * border and 12.dp corners. The toast *type* is conveyed by the icon color
 * rather than a fully-colored background.
 *
 * @param toastState The toast state manager
 * @param modifier Modifier for the host container
 */
@Composable
fun PluginToastHost(
    toastState: PluginToastState,
    modifier: Modifier = Modifier,
) {
    val toasts by toastState.toasts.collectAsState()

    Column(
        modifier =
            modifier
                .padding(16.dp)
                .widthIn(max = 400.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        // A single control to clear the whole stack. Every toast already has its own dismiss button,
        // but INDEFINITE toasts clear only by hand and PluginToastState stacks up to maxToasts (3) of
        // them - so once there is more than one, dismissing them one at a time is the only option a
        // user has. dismissAll() has existed on the controller all along with no surface that calls
        // it; this is that surface. Shown only past a single toast, where "all" means more than the
        // lone dismiss button beside it already does.
        AnimatedVisibility(
            visible = shouldShowClearAllControl(toasts.size),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = BossThemeColors.SurfaceColor,
                modifier = Modifier.border(1.dp, BossThemeColors.BorderColor, RoundedCornerShape(12.dp)),
            ) {
                TextButton(
                    onClick = { toastState.dismissAll() },
                ) {
                    Text(
                        text = "Clear all",
                        color = BossThemeColors.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        toasts.forEach { toast ->
            AnimatedVisibility(
                visible = true,
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
            ) {
                PluginToast(
                    message = toast,
                    onDismiss = { toastState.dismiss(toast.id) },
                )
            }
        }
    }
}

/**
 * The text copied by a toast's copy button: the title and message on their own lines, or whichever
 * is present. A plugin's toast is often where the user first sees an error or an id worth keeping,
 * and it may be dismissed before they can act on it, so the whole toast is copyable in one click.
 */
internal fun toastClipboardText(message: ToastMessage): String =
    listOf(message.title, message.message)
        .filter { it.isNotBlank() }
        .joinToString("\n")

/**
 * Individual toast message composable.
 *
 * @param message The toast message to display
 * @param onDismiss Callback when the toast is dismissed
 */
@Composable
fun PluginToast(
    message: ToastMessage,
    onDismiss: () -> Unit,
) {
    val (accentColor, icon) = toastAccent(message.type)
    val clipboard = LocalClipboardManager.current

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .border(1.dp, BossThemeColors.BorderColor, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        color = BossThemeColors.SurfaceColor,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier =
                Modifier
                    .padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // Type icon (the only colored element)
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(20.dp),
            )

            Spacer(Modifier.width(12.dp))

            // Content
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = message.title,
                    color = BossThemeColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = TOAST_TITLE_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = message.message,
                    color = BossThemeColors.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = TOAST_MESSAGE_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                )

                // Action button
                message.action?.let { action ->
                    TextButton(
                        onClick = {
                            action.onClick()
                            onDismiss()
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Text(
                            text = action.label,
                            color = BossThemeColors.AccentColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = TOAST_ACTION_MAX_LINES,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Do not offer a destructive no-op: writing an empty string would erase the clipboard.
            val clipboardText = toastClipboardText(message)
            if (clipboardText.isNotEmpty()) {
                IconButton(
                    onClick = {
                        runCatching { clipboard.setText(AnnotatedString(clipboardText)) }
                            .onFailure { toastLogger.warn(LogCategory.UI, "Could not copy toast text", error = it) }
                    },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy notification text",
                        tint = BossThemeColors.TextMuted,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            // Dismiss button
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Dismiss",
                    tint = BossThemeColors.TextMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * Accent color + icon for a toast type. The surface and text colors are uniform
 * (BOSS dialog palette); only the icon is tinted to signal the type.
 */
private fun toastAccent(type: ToastType): Pair<Color, ImageVector> =
    when (type) {
        ToastType.INFO -> BossThemeColors.AccentColor to Icons.Outlined.Info
        ToastType.SUCCESS -> BossThemeColors.SuccessColor to Icons.Outlined.CheckCircle
        ToastType.WARNING -> BossThemeColors.WarningColor to Icons.Outlined.Warning
        ToastType.ERROR -> BossThemeColors.ErrorColor to Icons.Outlined.Error
    }
