package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.DirectoryPickerProvider

/**
 * Android implementation of DirectoryPickerProvider.
 * No-op implementation - would need Activity context for proper implementation.
 */
actual class DirectoryPickerProviderImpl : DirectoryPickerProvider {

    override fun pickDirectory(onResult: (String?) -> Unit) {
        // Android needs Activity context to show file picker
        // Return null to indicate no directory was selected
        onResult(null)
    }
}
