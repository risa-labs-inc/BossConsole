package ai.rever.boss.cli

/**
 * Security validation utilities for CLI operations.
 *
 * Prevents path traversal attacks, command injection, and other security issues.
 * Based on security validation from UpdateScriptGenerator.kt:72-104
 */
object CLISecurityValidator {
    /**
     * Validates URL format.
     * For backward compatibility - use normalizeAndValidateUrl() for new code.
     */
    fun isValidUrl(url: String): Boolean = url.startsWith("http://") || url.startsWith("https://")

    /**
     * Normalizes and validates a URL.
     * Adds https:// prefix if missing for domain-like strings.
     *
     * @param url The URL to normalize and validate
     * @return The normalized URL with proper protocol, or null if invalid
     *
     * Examples:
     * - "google.com" -> "https://google.com"
     * - "https://google.com" -> "https://google.com"
     * - "http://example.com" -> "http://example.com"
     * - "invalidurl" -> null (no domain detected)
     */
    fun normalizeAndValidateUrl(url: String): String? {
        val trimmed = url.trim()

        // Empty URL is invalid
        if (trimmed.isEmpty()) {
            return null
        }

        // Already has protocol - validate and return as-is
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }

        // Check if it looks like a domain (contains at least one dot)
        // This prevents random strings from being treated as URLs
        if (!trimmed.contains('.')) {
            return null
        }

        // Basic validation: shouldn't contain spaces or most special chars
        if (trimmed.contains(' ') || trimmed.contains('\n') || trimmed.contains('\r')) {
            return null
        }

        // Add https:// prefix (modern standard)
        return "https://$trimmed"
    }

    /**
     * Longest path BOSS will try to open.
     *
     * Well past any real filesystem limit (4096 on Linux, 1024 on macOS, 32767
     * for a Windows extended path) and a bound on what a caller can make the app
     * hold and canonicalise, since `boss://file` is reachable by any program that
     * can ask the OS to open a URL.
     */
    private const val MAX_OPEN_TARGET_PATH_LENGTH = 32_768

    /**
     * Validates a path that will be **read**, never handed to a shell.
     *
     * Separate from [isValidPath] because that one's rules are the right rules
     * for a path that ends up inside a command line and the wrong rules for a
     * file the user just double-clicked in Finder. `isValidPath` rejects any
     * path containing `..`, `$`, `&`, `;`, `|` or a backtick, so a real file
     * called `Q&A notes.md`, `pay$.sh` or anything under a directory with an
     * ampersand in its name could not be opened at all - it failed the check and
     * the open was dropped with a log line and no window.
     *
     * The dropped rules bought nothing on this path. There is no shell, so shell
     * metacharacters are ordinary filename characters; and `..` cannot be a
     * traversal defence when every caller may pass an absolute path anyway, so
     * canonicalising is both stricter and correct. What remains is what actually
     * matters: no NUL (which truncates the path in any native call underneath),
     * and a path that resolves.
     *
     * Callers still check `exists()`, `isFile()` and `canRead()` afterwards;
     * this decides only whether the string is a usable path at all.
     */
    fun isValidOpenTargetPath(path: String): Boolean {
        if (path.isBlank()) return false
        if (path.length > MAX_OPEN_TARGET_PATH_LENGTH) return false
        if (path.contains('\u0000')) return false

        // canonicalFile resolves `..` and symlinks and throws on a path the
        // filesystem cannot represent, which is the honest way to reject the
        // shapes the `..` test was reaching for.
        return try {
            java.io
                .File(path)
                .canonicalFile
            true
        } catch (_: java.io.IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * Validates file path for security.
     * Prevents path traversal attacks and other malicious patterns.
     *
     * For a path that is only going to be **read** (opening a file in the
     * editor), use [isValidOpenTargetPath]: these rules assume the path may
     * reach a shell and reject legal filenames that never would.
     */
    fun isValidPath(path: String): Boolean {
        // Check for null bytes
        if (path.contains('\u0000')) {
            return false
        }

        // Check for path traversal
        if (path.contains("..")) {
            return false
        }

        // Check for shell metacharacters
        val dangerousChars = listOf(';', '&', '|', '`', '$', '\n', '\r')
        if (dangerousChars.any { path.contains(it) }) {
            return false
        }

        return true
    }

    /**
     * Longest terminal command BOSS will type into a shell — well past anything
     * a person writes by hand, and a bound on what a caller can make the app
     * hold. Commands that must be confirmed are held to the tighter
     * [TERMINAL_CONFIRM_MAX_COMMAND_LENGTH], which is what the prompt can show in
     * full.
     */
    private const val MAX_COMMAND_LENGTH = 4096

    /**
     * Checks that a terminal command is well formed: one non-empty line of
     * printable text, no longer than [MAX_COMMAND_LENGTH].
     *
     * This is a shape check, not a judgement about what the command does. An
     * allow-list of commands would rule out the legitimate use — `boss terminal
     * -c` exists precisely to run whatever the operator types — without ruling
     * out much else, so **who asked** is decided separately by
     * [ai.rever.boss.utils.DeepLinkOrigin]: only a request the operator made
     * themselves runs without a prompt.
     *
     * Control, format, and Unicode line-separator characters are rejected
     * because the command is written into a shell followed by a single Enter — an embedded line break would submit
     * further lines that nothing ever displayed, so keeping the command to one
     * line is what makes the text shown equal to the text that runs. The NUL
     * byte the previous check looked for is one of them.
     */
    fun isValidCommand(command: String): Boolean =
        command.isNotBlank() &&
            command.length <= MAX_COMMAND_LENGTH &&
            !command.hasHiddenDisplayCharacter()
}

internal val HIDDEN_DISPLAY_CHARACTERS =
    setOf(
        CharCategory.CONTROL,
        CharCategory.FORMAT,
        CharCategory.LINE_SEPARATOR,
        CharCategory.PARAGRAPH_SEPARATOR,
    )

/** True for characters that can make reviewed one-line text differ from its underlying value. */
internal fun String.hasHiddenDisplayCharacter(): Boolean {
    var index = 0
    var hidden = false
    while (index < length && !hidden) {
        val character = this[index]
        hidden =
            character.category in HIDDEN_DISPLAY_CHARACTERS ||
            (index + 1 < length && isSupplementaryFormatCharacter(character, this[index + 1]))
        index += if (character.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) 2 else 1
    }
    return hidden
}

/** Supplementary-plane `Cf` ranges in the Unicode tables supported by this Kotlin target. */
internal fun isSupplementaryFormatCharacter(
    high: Char,
    low: Char,
): Boolean {
    if (!high.isHighSurrogate() || !low.isLowSurrogate()) return false
    val codePoint = 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
    return codePoint == 0x110BD ||
        codePoint == 0x110CD ||
        codePoint in 0x13430..0x1343F ||
        codePoint in 0x1BCA0..0x1BCA3 ||
        codePoint in 0x1D173..0x1D17A ||
        codePoint == 0xE0001 ||
        codePoint in 0xE0020..0xE007F
}
