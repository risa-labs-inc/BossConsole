package ai.rever.boss.components.workspaces

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.awt.Desktop
import java.io.File

private val logger = BossLogger.forComponent("WorkspaceButton")

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
        // Non-fatal: the button just does nothing if the OS file manager refuses
        logger.warn(
            LogCategory.WORKSPACE,
            "Failed to open workspace directory in file manager",
            mapOf("path" to path),
            error = e,
        )
    }
}
