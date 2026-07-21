package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.DirectoryPickerProvider

/**
 * Platform-specific implementation of DirectoryPickerProvider.
 * Uses native file dialogs on each platform.
 */
expect class DirectoryPickerProviderImpl() : DirectoryPickerProvider
