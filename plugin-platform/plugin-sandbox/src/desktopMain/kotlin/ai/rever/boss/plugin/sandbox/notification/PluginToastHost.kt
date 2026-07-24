package ai.rever.boss.plugin.sandbox.notification

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = message.message,
                    color = BossThemeColors.TextSecondary,
                    fontSize = 12.sp,
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
                        )
                    }
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
