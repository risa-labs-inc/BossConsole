package ai.rever.boss.git

import ai.rever.boss.services.terminal.TerminalAPIAccess
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory

/**
 * Desktop implementation of GitTerminalService.
 * Uses TerminalAPIAccess to open git commands in the sidebar terminal.
 */
actual object GitTerminalService {
    private val logger = BossLogger.forComponent("GitTerminalService")

    /**
     * Open a git command in the sidebar terminal panel.
     * Creates a new tab in the sidebar terminal with the given command.
     *
     * @param windowId The window ID for window-scoped terminal state
     * @param command The git command to run
     * @param workingDirectory The working directory for the terminal
     * @param operationName Human-readable name for the operation (used for tab title)
     * @return True if the sidebar terminal exists and tab was created
     */
    actual fun openInSidebarTerminal(
        windowId: String,
        command: String,
        workingDirectory: String,
        operationName: String,
    ): Boolean {
        // Generate a unique tab ID for this git operation
        val tabId = "git-${operationName.lowercase().replace(" ", "-")}-${System.currentTimeMillis()}"

        val success =
            TerminalAPIAccess.newSidebarTab(
                windowId = windowId,
                command = command,
                workingDirectory = workingDirectory,
                configId = tabId,
                isRerun = false,
            )

        if (success) {
            logger.debug(
                LogCategory.TERMINAL,
                "Opened command in sidebar terminal",
                mapOf(
                    "operationName" to operationName,
                    "windowId" to windowId,
                ),
            )
        } else {
            logger.warn(LogCategory.TERMINAL, "Failed to open in sidebar terminal - panel may not be open", mapOf("windowId" to windowId))
        }

        return success
    }
}
