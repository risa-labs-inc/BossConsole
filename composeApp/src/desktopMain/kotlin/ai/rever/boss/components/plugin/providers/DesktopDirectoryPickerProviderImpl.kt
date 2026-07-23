package ai.rever.boss.components.plugin.providers

import ai.rever.boss.plugin.api.DirectoryPickerProvider
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * Desktop implementation of DirectoryPickerProvider.
 * Uses native file dialogs (AWT FileDialog on macOS, JFileChooser on Windows/Linux).
 */
actual class DirectoryPickerProviderImpl : DirectoryPickerProvider {

    private val logger = BossLogger.forComponent("DirectoryPickerProvider")

    override fun pickDirectory(onResult: (String?) -> Unit) {
        SwingUtilities.invokeLater {
            // Set system look and feel for native appearance
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
            } catch (e: Exception) {
                // If setting system L&F fails, continue with default
                logger.debug(LogCategory.UI, "Could not set system look and feel - using default", mapOf("error" to e.toString()))
            }

            // Use native file dialog for better macOS integration
            val isMacOS = System.getProperty("os.name").lowercase().contains("mac")

            if (isMacOS) {
                // Use AWT FileDialog for native macOS look
                System.setProperty("apple.awt.fileDialogForDirectories", "true")

                // Own the dialog to the app's active window and force it frontmost.
                // With a null owner the native dialog can open BEHIND the Compose
                // window (or without activating the app), so a click looks like it
                // did nothing. The active window here is the ComposeWindow (a Frame).
                val owner = java.awt.KeyboardFocusManager
                    .getCurrentKeyboardFocusManager().activeWindow as? Frame
                val dialog = FileDialog(owner, "Select Project Directory", FileDialog.LOAD)
                dialog.isAlwaysOnTop = true
                dialog.isVisible = true

                val directory = dialog.directory
                val file = dialog.file

                System.setProperty("apple.awt.fileDialogForDirectories", "false")

                if (directory != null && file != null) {
                    onResult("$directory$file")
                } else if (directory != null) {
                    onResult(directory)
                } else {
                    onResult(null)
                }
            } else {
                // For Windows/Linux, use JFileChooser
                val fileChooser = javax.swing.JFileChooser().apply {
                    fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                    dialogTitle = "Select Project Directory"
                    isAcceptAllFileFilterUsed = false
                    currentDirectory = File(System.getProperty("user.home"))
                }

                val owner = java.awt.KeyboardFocusManager
                    .getCurrentKeyboardFocusManager().activeWindow
                val result = fileChooser.showOpenDialog(owner)

                if (result == javax.swing.JFileChooser.APPROVE_OPTION) {
                    val selectedFile = fileChooser.selectedFile
                    onResult(selectedFile?.absolutePath)
                } else {
                    onResult(null)
                }
            }
        }
    }
}
