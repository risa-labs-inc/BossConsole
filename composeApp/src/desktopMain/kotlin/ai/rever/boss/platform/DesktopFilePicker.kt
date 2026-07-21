package ai.rever.boss.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.SwingUtilities
import javax.swing.UIManager

@Composable
actual fun rememberDirectoryPicker(
    onDirectorySelected: (path: String?) -> Unit
): DirectoryPicker {
    return remember {
        DesktopDirectoryPicker(onDirectorySelected)
    }
}

class DesktopDirectoryPicker(
    private val onDirectorySelected: (path: String?) -> Unit
) : DirectoryPicker {
    
    override fun pickDirectory() {
        SwingUtilities.invokeLater {
            // Set system look and feel for native appearance
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
            } catch (e: Exception) {
                // If setting system L&F fails, continue with default
            }
            
            // Use native file dialog for better macOS integration
            val isMacOS = System.getProperty("os.name").lowercase().contains("mac")
            
            if (isMacOS) {
                // Use AWT FileDialog for native macOS look
                System.setProperty("apple.awt.fileDialogForDirectories", "true")
                
                val dialog = FileDialog(null as Frame?, "Select Project Directory", FileDialog.LOAD)
                dialog.isVisible = true
                
                val directory = dialog.directory
                val file = dialog.file
                
                System.setProperty("apple.awt.fileDialogForDirectories", "false")
                
                if (directory != null && file != null) {
                    onDirectorySelected("$directory$file")
                } else if (directory != null) {
                    onDirectorySelected(directory)
                } else {
                    onDirectorySelected(null)
                }
            } else {
                // For Windows/Linux, use JFileChooser
                val fileChooser = javax.swing.JFileChooser().apply {
                    fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                    dialogTitle = "Select Project Directory"
                    isAcceptAllFileFilterUsed = false
                    currentDirectory = File(System.getProperty("user.home"))
                }
                
                val result = fileChooser.showOpenDialog(null)
                
                if (result == javax.swing.JFileChooser.APPROVE_OPTION) {
                    val selectedFile = fileChooser.selectedFile
                    onDirectorySelected(selectedFile?.absolutePath)
                } else {
                    onDirectorySelected(null)
                }
            }
        }
    }
}
