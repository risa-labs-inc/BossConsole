package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.DirectoryPickerProvider

/**
 * WASM implementation of DirectoryPickerProvider.
 * No-op implementation as browser doesn't have direct file system access.
 */
actual class DirectoryPickerProviderImpl : DirectoryPickerProvider {

    override fun pickDirectory(onResult: (String?) -> Unit) {
        // Browser doesn't have direct file system access
        // Return null to indicate no directory was selected
        onResult(null)
    }
}
