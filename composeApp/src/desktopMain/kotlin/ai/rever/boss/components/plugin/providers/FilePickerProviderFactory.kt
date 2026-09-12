package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.FilePickerProvider
import ai.rever.boss.plugin.browser.activeDialogOwner
import ai.rever.boss.plugin.browser.ownedFileDialog
import ai.rever.boss.plugin.browser.showModal
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.awt.FileDialog
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
 *
 * Every panel here is owned by the window the user is looking at and states its own directory
 * mode, through [ownedFileDialog] and [showModal]. Both matter for a panel a PLUGIN opens:
 *
 *  - An ownerless panel has no window to be modal to and none to come forward over, so it can open
 *    behind the Compose window. `onResult(null)` then arrives looking exactly like a cancel, and
 *    the plugin reports that the user changed their mind.
 *  - `apple.awt.fileDialogForDirectories` is process-wide and read when the peer is created, and a
 *    modal panel runs a nested event loop that keeps dispatching. A plugin picker opened while the
 *    browser's folder panel is up therefore came up as a DIRECTORY chooser, which
 *    `NativeFileDialogs` already recorded as a live consequence of this file not stating its mode.
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
                    val dialog = ownedFileDialog(title ?: "Open File", FileDialog.LOAD)
                    if (!filters.isNullOrEmpty()) {
                        dialog.setFilenameFilter { _, name ->
                            filters.any { ext -> name.endsWith(".$ext", ignoreCase = true) }
                        }
                    }
                    dialog.showModal(directories = false)
                    val dir = dialog.directory
                    val file = dialog.file
                    if (dir != null && file != null) {
                        onResult("$dir$file")
                    } else {
                        onResult(null)
                    }
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
                    val result = chooser.showOpenDialog(activeDialogOwner())
                    if (result == JFileChooser.APPROVE_OPTION) {
                        onResult(chooser.selectedFile?.absolutePath)
                    } else {
                        onResult(null)
                    }
                }
            } catch (e: Exception) {
                // onResult(null) is the only channel this API has, and the plugin cannot tell it
                // apart from a cancel. The log is therefore the only place the difference exists,
                // so it is an error rather than a warning and says so explicitly.
                logger.error(
                    LogCategory.SYSTEM,
                    "File picker failed; the plugin will see this as a user cancel",
                    error = e,
                )
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
                    val dialog = ownedFileDialog("Save File", FileDialog.SAVE)
                    if (suggestedFileName != null) {
                        dialog.file = suggestedFileName
                    }
                    dialog.showModal(directories = false)
                    val dir = dialog.directory
                    val file = dialog.file
                    if (dir != null && file != null) {
                        onResult("$dir$file")
                    } else {
                        onResult(null)
                    }
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
                    val result = chooser.showSaveDialog(activeDialogOwner())
                    if (result == JFileChooser.APPROVE_OPTION) {
                        onResult(chooser.selectedFile?.absolutePath)
                    } else {
                        onResult(null)
                    }
                }
            } catch (e: Exception) {
                logger.error(
                    LogCategory.SYSTEM,
                    "Save file picker failed; the plugin will see this as a user cancel",
                    error = e,
                )
                onResult(null)
            }
        }
    }
}
