package ai.rever.boss.platform

import androidx.compose.runtime.Composable

// Platform-specific file picker for selecting directories
@Composable
expect fun rememberDirectoryPicker(
    onDirectorySelected: (path: String?) -> Unit
): DirectoryPicker

interface DirectoryPicker {
    fun pickDirectory()
}

// Platform-specific file picker for selecting files
@Composable
expect fun rememberFilePicker(
    onFileSelected: (path: String?, content: String?) -> Unit,
    fileExtensions: List<String> = listOf("json")
): FilePicker

interface FilePicker {
    fun pickFile()
}

/**
 * Synchronously shows a save file dialog.
 * Used for download save location selection (must block until user responds).
 *
 * @param suggestedFileName The default file name to suggest
 * @param initialDirectory Optional initial directory to open (null for system default)
 * @param allowedExtensions List of allowed file extensions (empty for all files)
 * @return The absolute path where the file should be saved, or null if user cancelled
 */
expect fun pickSaveFile(
    suggestedFileName: String,
    initialDirectory: String? = null,
    allowedExtensions: List<String> = emptyList()
): String?

