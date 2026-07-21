package ai.rever.boss.run

/**
 * Utility functions for shell command construction and escaping.
 * Handles cross-platform differences between Unix shells and Windows PowerShell.
 */
object ShellUtils {

    /**
     * Whether we're running on Windows.
     */
    val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("windows")

    /**
     * Command separator for chaining commands.
     * - Unix/macOS/Linux: && (run second command only if first succeeds)
     * - Windows PowerShell: ; (sequential execution)
     *
     * Note: PowerShell 7+ supports && but Windows PowerShell 5.x doesn't.
     * Using ; for broader compatibility on Windows.
     *
     * TRADE-OFF WARNING:
     * Using semicolon (;) on Windows means commands execute sequentially regardless of
     * success/failure. This is different from Unix && which stops on first failure.
     *
     * Examples:
     * - Unix: "cd /invalid && echo success" → cd fails, echo never runs
     * - Windows: "cd /invalid; echo success" → cd fails, echo still runs and prints "success"
     *
     * This trade-off is acceptable for BOSS because:
     * 1. Most command sequences are not critically dependent on failure propagation
     * 2. Terminal output shows errors from individual commands
     * 3. PowerShell 5.x is still widely used (Windows 10 default) and doesn't support &&
     *
     * For critical operations requiring error propagation, use separate commands
     * with explicit error handling rather than chaining.
     */
    val commandSeparator: String = if (isWindows) "; " else " && "

    /**
     * Escape a string for safe use inside double quotes in shell.
     * Platform-aware escaping for Unix shells vs Windows PowerShell.
     */
    fun escapeForDoubleQuotes(str: String): String {
        return if (isWindows) {
            // PowerShell escaping: backtick is the escape character
            str
                .replace("`", "``")     // Backtick must be escaped first
                .replace("\"", "`\"")   // Double quotes
                .replace("\$", "`\$")   // Dollar sign (prevents variable expansion)
        } else {
            // Unix shell escaping
            str
                .replace("\\", "\\\\")  // Backslash must be escaped first
                .replace("\"", "\\\"")  // Double quotes
                .replace("\$", "\\\$")  // Dollar sign (prevents variable expansion)
                .replace("`", "\\`")    // Backticks (prevents command substitution)
                .replace("!", "\\!")    // Exclamation (history expansion in interactive shells)
        }
    }

    /**
     * Build a command with cd to working directory.
     * Working directory is properly escaped for shell safety.
     * Uses platform-appropriate command separator.
     *
     * @param command The command to run
     * @param workingDirectory Optional working directory to cd into first
     * @return The full command with cd prefix if workingDirectory is provided
     */
    fun buildCommandWithWorkingDirectory(command: String, workingDirectory: String?): String {
        return if (!workingDirectory.isNullOrBlank()) {
            val escapedDir = escapeForDoubleQuotes(workingDirectory)
            "cd \"$escapedDir\"$commandSeparator$command"
        } else {
            command
        }
    }

    /**
     * Chain multiple commands together using platform-appropriate separator.
     *
     * @param commands The commands to chain
     * @return The chained command string
     */
    fun chainCommands(vararg commands: String): String {
        return commands.joinToString(commandSeparator)
    }
}
