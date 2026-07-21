package ai.rever.boss.components.plugin.providers

import androidx.compose.runtime.Composable

/**
 * Composable function to render the generic dialog host.
 * Desktop implementation renders the actual dialog host.
 * Non-desktop platforms can provide a no-op implementation.
 */
@Composable
expect fun GenericDialogHostContent()
