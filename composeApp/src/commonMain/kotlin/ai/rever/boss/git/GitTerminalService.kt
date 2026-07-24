package ai.rever.boss.git

/**
 * Service for running Git commands in the terminal.
 * Uses expect/actual pattern for platform-specific terminal implementations.
 */
expect object GitTerminalService {
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
    fun openInSidebarTerminal(
        windowId: String,
        command: String,
        workingDirectory: String,
        operationName: String,
    ): Boolean
}
