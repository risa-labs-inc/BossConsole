package ai.rever.boss.platform

import androidx.compose.runtime.Composable

// Platform-specific file picker for selecting directories
@Composable
expect fun rememberDirectoryPicker(onDirectorySelected: (path: String?) -> Unit): DirectoryPicker

interface DirectoryPicker {
    fun pickDirectory()
}

// Platform-specific file picker for selecting files

/**
 * @param onFileSelected receives the chosen path and its content, or nulls when
 *   the user cancelled. [tooLarge] distinguishes "refused to read it" from
 *   "cancelled", which would otherwise look identical to the caller.
 */
@Composable
expect fun rememberFilePicker(
    onFileSelected: (path: String?, content: String?, tooLarge: Boolean) -> Unit,
    fileExtensions: List<String> = listOf("json"),
    title: String = "Select File",
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
    allowedExtensions: List<String> = emptyList(),
): String?

/**
 * Synchronously asks the user to confirm downloading a file recognized as executable
 * (see [ai.rever.boss.platform.FileNameSanitizer.isExecutableFile]). Used for the
 * download-start handler, which currently waits for consent before answering the
 * asynchronous JxBrowser callback, just as [pickSaveFile] waits for a save location.
 *
 * @param fileName The name of the file being downloaded, shown in the prompt
 * @return true if the user chose to proceed, false to cancel the download (including
 *   when the confirmation dialog itself could not be shown - the safer default for a
 *   warning that exists to stop an unwanted executable landing on disk)
 */
expect fun confirmExecutableDownload(fileName: String): Boolean
