package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.FilePickerProvider
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.awt.Dialog
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

private val logger = BossLogger.forComponent("FilePickerProvider")

/**
 * Desktop implementation of FilePickerProvider factory.
 */
actual fun createFilePickerProvider(): FilePickerProvider? = DesktopFilePickerProvider()

/**
 * Desktop file picker provider using AWT/Swing dialogs.
 */
private class DesktopFilePickerProvider : FilePickerProvider {
    private val isMacOS = System.getProperty("os.name").lowercase().contains("mac")

    override fun pickFile(
        title: String?,
        filters: List<String>?,
        onResult: (String?) -> Unit,
    ) {
        SwingUtilities.invokeLater {
            try {
                if (isMacOS) {
                    val dialog = createFileDialog(title ?: "Open File", FileDialog.LOAD)
                    if (!filters.isNullOrEmpty()) {
                        dialog.setFilenameFilter { _, name ->
                            filters.any { ext -> name.endsWith(".$ext", ignoreCase = true) }
                        }
                    }
                    dialog.isVisible = true
                    val dir = dialog.directory
                    val file = dialog.file
                    if (dir != null && file != null) {
                        onResult("$dir$file")
                    } else {
                        onResult(null)
                    }
                    dialog.dispose()
                } else {
                    val chooser =
                        JFileChooser().apply {
                            dialogTitle = title ?: "Open File"
                            fileSelectionMode = JFileChooser.FILES_ONLY
                            currentDirectory = File(System.getProperty("user.home"))
                            if (!filters.isNullOrEmpty()) {
                                isAcceptAllFileFilterUsed = false
                                addChoosableFileFilter(
                                    FileNameExtensionFilter(
                                        filters.joinToString(", ") { "*.$it" },
                                        *filters.toTypedArray(),
                                    ),
                                )
                            }
                        }
                    val result = chooser.showOpenDialog(activeWindow())
                    if (result == JFileChooser.APPROVE_OPTION) {
                        onResult(chooser.selectedFile?.absolutePath)
                    } else {
                        onResult(null)
                    }
                }
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "File picker failed", error = e)
                onResult(null)
            }
        }
    }

    override fun pickSaveFile(
        suggestedFileName: String?,
        filters: List<String>?,
        onResult: (String?) -> Unit,
    ) {
        SwingUtilities.invokeLater {
            try {
                if (isMacOS) {
                    val dialog = createFileDialog("Save File", FileDialog.SAVE)
                    if (suggestedFileName != null) {
                        dialog.file = suggestedFileName
                    }
                    if (!filters.isNullOrEmpty()) {
                        dialog.setFilenameFilter { _, name ->
                            filters.any { ext -> name.endsWith(".$ext", ignoreCase = true) }
                        }
                    }
                    dialog.isVisible = true
                    val dir = dialog.directory
                    val file = dialog.file
                    if (dir != null && file != null) {
                        onResult("$dir$file")
                    } else {
                        onResult(null)
                    }
                    dialog.dispose()
                } else {
                    val chooser =
                        JFileChooser().apply {
                            dialogTitle = "Save File"
                            fileSelectionMode = JFileChooser.FILES_ONLY
                            currentDirectory = File(System.getProperty("user.home"))
                            if (suggestedFileName != null) {
                                selectedFile = File(suggestedFileName)
                            }
                            if (!filters.isNullOrEmpty()) {
                                isAcceptAllFileFilterUsed = false
                                addChoosableFileFilter(
                                    FileNameExtensionFilter(
                                        filters.joinToString(", ") { "*.$it" },
                                        *filters.toTypedArray(),
                                    ),
                                )
                            }
                        }
                    val result = chooser.showSaveDialog(activeWindow())
                    if (result == JFileChooser.APPROVE_OPTION) {
                        onResult(chooser.selectedFile?.absolutePath)
                    } else {
                        onResult(null)
                    }
                }
            } catch (e: Exception) {
                logger.warn(LogCategory.SYSTEM, "Save file picker failed", error = e)
                onResult(null)
            }
        }
    }
}

/**
 * Returns the AWT window currently receiving keyboard focus.
 *
 * Using the active window as the owner keeps native file dialogs attached to the
 * BOSS window that initiated the request instead of allowing them to appear behind it.
 */
private fun activeWindow(): java.awt.Window? =
    KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow

/**
 * FileDialog has separate constructors for Frame and Dialog owners.
 */
private fun createFileDialog(
    title: String,
    mode: Int,
): FileDialog =
    when (val window = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow) {
        is Frame -> FileDialog(window, title, mode)
        is Dialog -> FileDialog(window, title, mode)
        else -> FileDialog(null as Frame?, title, mode)
    }