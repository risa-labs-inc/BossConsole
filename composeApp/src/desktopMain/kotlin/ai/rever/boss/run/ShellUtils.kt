package ai.rever.boss.run

/**
 * Utility functions for shell command construction and escaping.
 * Handles cross-platform differences between Unix shells and Windows PowerShell.
 */
object ShellUtils {
    /**
     * Every character PowerShell's tokenizer reads as a double quote: ASCII `"` and the three
     * typographic ones (U+201C, U+201D, U+201E). Escaping only the ASCII one let a folder name
     * carrying a typographic quote end the string early.
     */
    private const val POWERSHELL_DOUBLE_QUOTES = "\"\u201C\u201D\u201E"

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
    val commandSeparator: String = separatorFor(isWindows)

    /**
     * Platform-explicit form of [commandSeparator], for the same reason as the
     * [escapeForDoubleQuotes] overload below: [isWindows] is fixed at class-load, so
     * tests can only reach the host's own separator through the property.
     */
    internal fun separatorFor(forWindows: Boolean): String = if (forWindows) "; " else " && "

    /**
     * Escape a string for safe use inside double quotes in shell.
     * Platform-aware escaping for Unix shells vs Windows PowerShell.
     */
    fun escapeForDoubleQuotes(str: String): String = escapeForDoubleQuotes(str, isWindows)

    /**
     * Platform-explicit form of [escapeForDoubleQuotes].
     *
     * [isWindows] is fixed at class-load from the real OS, so the single-argument
     * overload can only ever reach one branch on a given host. This overload takes
     * the platform as a parameter so both the PowerShell and the POSIX branch are
     * exercised by tests on every host, instead of relying on the CI matrix
     * happening to include a Windows runner.
     *
     * @param str The string to escape
     * @param forWindows Escape for Windows PowerShell when true, POSIX shells otherwise
     */
    internal fun escapeForDoubleQuotes(
        str: String,
        forWindows: Boolean,
    ): String =
        if (forWindows) {
            // PowerShell escaping: backtick is the escape character
            str
                .replace("`", "``") // Backtick must be escaped first
                .map { if (it in POWERSHELL_DOUBLE_QUOTES) "`$it" else it.toString() } // Double quotes
                .joinToString("")
                .replace("\$", "`\$") // Dollar sign (prevents variable expansion)
        } else {
            // Unix shell escaping
            str
                .replace("\\", "\\\\") // Backslash must be escaped first
                .replace("\"", "\\\"") // Double quotes
                .replace("\$", "\\\$") // Dollar sign (prevents variable expansion)
                .replace("`", "\\`") // Backticks (prevents command substitution)
                // Exclamation: history expansion, which only happens in an INTERACTIVE
                // shell. Inside double quotes a backslash is literal before anything but
                // $ ` " \ and newline, so a non-interactive shell (`sh -c`) keeps the
                // backslash and a path containing "!" breaks. Today's callers all feed an
                // interactive terminal; check that before reusing this for `sh -c`.
                .replace("!", "\\!")
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
    fun buildCommandWithWorkingDirectory(
        command: String,
        workingDirectory: String?,
    ): String = buildCommandWithWorkingDirectory(command, workingDirectory, isWindows)

    /**
     * Platform-explicit form of [buildCommandWithWorkingDirectory]: it composes both
     * platform-dependent pieces (escaping and separator), so it needs the same seam to
     * be testable on either branch from any host.
     *
     * @param forWindows Build for Windows PowerShell when true, POSIX shells otherwise
     */
    internal fun buildCommandWithWorkingDirectory(
        command: String,
        workingDirectory: String?,
        forWindows: Boolean,
    ): String =
        if (!workingDirectory.isNullOrBlank()) {
            val escapedDir = escapeForDoubleQuotes(workingDirectory, forWindows)
            "cd \"$escapedDir\"${separatorFor(forWindows)}$command"
        } else {
            command
        }

    /**
     * Chain multiple commands together using platform-appropriate separator.
     *
     * Its only platform dependence is [separatorFor], which is covered on both branches
     * by tests, so this needs no platform-explicit overload of its own.
     *
     * @param commands The commands to chain
     * @return The chained command string
     */
    fun chainCommands(vararg commands: String): String = commands.joinToString(commandSeparator)
}
