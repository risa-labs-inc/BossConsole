package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.GenericDialogProvider

/**
 * Factory function to create platform-specific GenericDialogProvider.
 * Desktop implementation uses Compose dialogs.
 *
 * @return GenericDialogProvider implementation, or null if not available
 */
expect fun createGenericDialogProvider(): GenericDialogProvider?
