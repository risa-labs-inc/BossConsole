package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.FilePickerProvider

/**
 * Factory function to create platform-specific FilePickerProvider.
 * Desktop implementation provides native file open/save dialogs.
 * Returns null on platforms that don't support file pickers.
 */
expect fun createFilePickerProvider(): FilePickerProvider?
