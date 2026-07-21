package ai.rever.boss.plugin.sandbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Fallback UI displayed when a plugin crashes.
 *
 * Shows the error information and provides options to:
 * - Restart the plugin (for recoverable errors)
 * - Update the plugin (for binary incompatibility errors)
 * - Dismiss the error (keep the fallback visible)
 *
 * @param pluginId The ID of the crashed plugin
 * @param error The error that caused the crash
 * @param isIncompatible Whether the error is a binary incompatibility (plugin needs update)
 * @param onRestart Callback when the user clicks "Restart Plugin"
 * @param onDismiss Optional callback when the user dismisses the error without restarting
 */
@Composable
fun PluginErrorFallback(
    pluginId: String,
    error: Throwable,
    isIncompatible: Boolean = false,
    onRestart: () -> Unit,
    onDismiss: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Header with dismiss button
        if (onDismiss != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Icon — update icon for incompatibility, warning for general errors
        Icon(
            imageVector = if (isIncompatible) Icons.Outlined.SystemUpdate else Icons.Outlined.Warning,
            contentDescription = if (isIncompatible) "Update Required" else "Error",
            modifier = Modifier.size(48.dp),
            tint = if (isIncompatible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Title
        Text(
            text = if (isIncompatible) "Plugin Update Required" else "Plugin Error",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Plugin ID
        Text(
            text = pluginId,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Error message in a styled container
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(12.dp)
        ) {
            Text(
                text = error.javaClass.simpleName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isIncompatible) {
                    "This plugin is incompatible with the current version of BOSS. Please update it."
                } else {
                    error.message ?: "Unknown error"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (isIncompatible) {
            // Dismiss button — restart won't help for incompatible plugins
            OutlinedButton(onClick = onDismiss ?: {}) {
                Text("Dismiss")
            }
        } else {
            // Restart button for recoverable errors
            Button(
                onClick = onRestart,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Restart Plugin")
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Help text
        Text(
            text = if (isIncompatible) {
                "Update this plugin to a version compatible with the current BOSS release."
            } else {
                "If this problem persists, try restarting the application."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}
