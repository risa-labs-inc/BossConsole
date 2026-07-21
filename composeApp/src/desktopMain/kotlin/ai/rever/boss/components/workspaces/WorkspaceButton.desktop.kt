package ai.rever.boss.components.workspaces

import java.awt.Desktop
import java.io.File

/**
 * Desktop implementation to open workspace directory
 */
actual fun openWorkspaceDirectory(path: String) {
    try {
        val directory = File(path)
        if (directory.exists() && directory.isDirectory) {
            Desktop.getDesktop().open(directory)
        }
    } catch (e: Exception) {
    }
}
