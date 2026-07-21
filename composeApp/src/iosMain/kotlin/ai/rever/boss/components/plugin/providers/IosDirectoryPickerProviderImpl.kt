package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.DirectoryPickerProvider

/**
 * iOS implementation of DirectoryPickerProvider.
 * No-op implementation as iOS has restricted file system access.
 */
actual class DirectoryPickerProviderImpl : DirectoryPickerProvider {

    override fun pickDirectory(onResult: (String?) -> Unit) {
        // iOS has restricted file system access
        // Return null to indicate no directory was selected
        onResult(null)
    }
}
