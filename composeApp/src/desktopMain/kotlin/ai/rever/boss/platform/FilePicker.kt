package ai.rever.boss.platform

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.SwingUtilities

private val filePickerLogger = BossLogger.forComponent("FilePicker")

@Composable
actual fun rememberFilePicker(
    onFileSelected: (path: String?, content: String?) -> Unit,
    fileExtensions: List<String>
): FilePicker {
    return remember {
        DesktopFilePicker(onFileSelected, fileExtensions)
    }
}

class DesktopFilePicker(
    private val onFileSelected: (path: String?, content: String?) -> Unit,
    private val fileExtensions: List<String>
) : FilePicker {
    override fun pickFile() {
        try {
            val fileDialog = FileDialog(null as Frame?, "Select Configuration File", FileDialog.LOAD).apply {
                // Set file filter for JSON files
                if (fileExtensions.isNotEmpty()) {
                    setFilenameFilter { _, name ->
                        fileExtensions.any { name.endsWith(".$it", ignoreCase = true) }
                    }
                }
                isVisible = true
            }
            
            val selectedFile = fileDialog.file
            val selectedDir = fileDialog.directory
            
            if (selectedFile != null && selectedDir != null) {
                val file = File(selectedDir, selectedFile)
                val content = file.readText()
                onFileSelected(file.absolutePath, content)
            } else {
                onFileSelected(null, null)
            }
        } catch (e: Exception) {
            filePickerLogger.warn(LogCategory.FILE, "Failed to read picked file - reporting no selection", error = e)
            onFileSelected(null, null)
        }
    }
}

/**
 * Desktop implementation of pickSaveFile using AWT FileDialog.
 * Runs synchronously on the EDT (Event Dispatch Thread) as required by JxBrowser callbacks.
 */
actual fun pickSaveFile(
    suggestedFileName: String,
    initialDirectory: String?,
    allowedExtensions: List<String>
): String? {
    // Sanitize the suggested file name for security
    val sanitizedFileName = FileNameSanitizer.sanitize(suggestedFileName)

    var result: String? = null

    try {
        // Must run on EDT to avoid AWT threading issues
        SwingUtilities.invokeAndWait {
            val fileDialog = FileDialog(null as Frame?, "Save File", FileDialog.SAVE).apply {
                // Set suggested file name
                file = sanitizedFileName

                // Set initial directory if provided
                initialDirectory?.let { directory = it }

                // Set file filter if extensions specified
                if (allowedExtensions.isNotEmpty()) {
                    setFilenameFilter { _, name ->
                        allowedExtensions.any { ext ->
                            name.endsWith(".$ext", ignoreCase = true)
                        } || allowedExtensions.contains("*")
                    }
                }

                isVisible = true
            }

            val selectedFile = fileDialog.file
            val selectedDir = fileDialog.directory

            if (selectedFile != null && selectedDir != null) {
                result = File(selectedDir, selectedFile).absolutePath
            }
        }
    } catch (e: Exception) {
        filePickerLogger.warn(LogCategory.FILE, "Error showing save file dialog", error = e)
        result = null
    }

    return result
}
