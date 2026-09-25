package ai.rever.boss.components.workspaces

/**
 * Platform-aware command processing for terminal commands.
 *
 * This utility normalizes shell commands that use Unix-style && separators
 * to work correctly on Windows PowerShell, which requires semicolons (;).
 *
 * TRADE-OFF: Windows uses semicolon which doesn't propagate errors like && does.
 * See ShellUtils.commandSeparator documentation for detailed trade-off analysis.
 *
 * DESIGN NOTE: This is implemented as expect/actual to allow for future platform-specific
 * enhancements (e.g., special handling for Windows CMD vs PowerShell detection).
 */
expect object CommandProcessor {
    /**
     * Normalize shell command separators for the current platform.
     *
     * Replaces Unix-style " && " with platform-appropriate separators:
     * - Windows: Uses "; " for PowerShell/CMD compatibility (no error propagation)
     * - Unix/macOS/Linux: Keeps " && " (stops on first failure)
     *
     * @param command Command string (may contain placeholders)
     * @return Command with platform-appropriate separators
     */
    fun normalizeCommand(command: String): String

    /**
     * Quote a filesystem path so it survives as a single argument when
     * interpolated into a shell command (e.g. `cd {projectPath} && claude`).
     * Without this, a folder name containing spaces or quotes — like
     * `AI Workflow Tools' Exports` — splits into multiple words and the
     * stray apostrophe leaves the shell waiting at a `quote>` prompt.
     *
     * - Unix/macOS/Linux: POSIX single-quote literal (`'…'`, embedded `'` → `'\''`)
     * - Windows PowerShell: single-quote literal (embedded `'` → `''`)
     */
    fun quotePath(path: String): String

    /** Escape a value already inside a template's single- or double-quote region. */
    fun escapeInsideQuote(
        value: String,
        quote: Char,
    ): String

    /** The current shell's escape character, for parsing template quote regions. */
    fun quoteEscapeCharacter(): Char
}

/**
 * Pure, platform-specific path-quoting strategies behind [CommandProcessor.quotePath].
 * Extracted so both branches are unit-testable without depending on the host OS.
 *
 * Single-quote *literal* quoting (not [ai.rever.boss.run.ShellUtils.escapeForDoubleQuotes],
 * which escapes for embedding inside `"double quotes"`): literal quoting is the safer
 * choice for a whole-path argument — no `$`, backtick, or `!` expansion to worry about.
 */
internal object ShellPathQuoting {
    /** POSIX: close the quote, emit an escaped `'`, reopen — fully literal. */
    fun posix(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    /**
     * PowerShell: single-quoted literal; an embedded quote is doubled.
     *
     * "Quote" is not only the ASCII `'`. PowerShell's tokenizer treats the four typographic single
     * quotes (U+2018, U+2019, U+201A, U+201B) as string delimiters too, and a quote character followed
     * by another quote character is the escape, whichever pair they are. So each of the five is
     * doubled here. Doubling only `'` left `x` + U+2019 + `;calc;` + U+2019 + `y` as three statements,
     * and a git branch or a folder may legally carry those characters.
     */
    fun powershell(path: String): String =
        buildString(path.length + 2) {
            append('\'')
            for (c in path) {
                append(c)
                if (c in POWERSHELL_SINGLE_QUOTES) append(c)
            }
            append('\'')
        }

    private const val POWERSHELL_SINGLE_QUOTES = "'\u2018\u2019\u201A\u201B"

    fun posixInsideQuote(
        value: String,
        quote: Char,
    ): String =
        if (quote == '\'') {
            value.replace("'", "'\\''")
        } else {
            value
                .replace("\\", "\\\\")
                .replace("$", "\\$")
                .replace("`", "\\`")
                .replace("\"", "\\\"")
        }

    fun powershellInsideQuote(
        value: String,
        quote: Char,
    ): String =
        if (quote == '\'') {
            buildString(value.length) {
                for (character in value) {
                    append(character)
                    if (character in POWERSHELL_SINGLE_QUOTES) append(character)
                }
            }
        } else {
            value
                .replace("`", "``")
                .replace("$", "`$")
                .replace("\"", "`\"")
        }
}
