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
     * no Windows-shaped dangerous input (which a Windows parser or filesystem
     * would silently rewrite or open against a device rather than the file the
     * string names), and a path that resolves.
     *
     * Callers still check `exists()`, `isFile()` and `canRead()` afterwards;
     * this decides only whether the string is a usable path at all.
     */
    fun isValidOpenTargetPath(path: String): Boolean {
        if (path.isBlank()) return false
        if (path.length > MAX_OPEN_TARGET_PATH_LENGTH) return false
        // Run BEFORE any path parsing/canonicalisation so a Windows-shaped
        // dangerous input cannot slip past a parser that silently strips or
        // rewrites the offending bytes on one platform only. See #832.
        if (hasWindowsDangerousShape(path)) return false

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
     * The Windows device names the filesystem treats as reserved regardless of
     * extension. They are not real directories on any Windows filesystem, so
     * `C:\CON`, `\\?\C:\foo\NUL\bar` and `C:\Users\me\COM1\notes.md` all open
     * something other than what the string names - the console, the null device
     * and the first serial port respectively. The check is path-component-wise
     * (matched case-insensitively against the component with any trailing dots
     * or spaces stripped - Windows does that before the lookup, so `con.`
     * resolves to `con`).
     */
    private val WINDOWS_RESERVED_NAMES: Set<String> =
        setOf(
            "CON",
            "PRN",
            "AUX",
            "NUL",
            "COM1",
            "COM2",
            "COM3",
            "COM4",
            "COM5",
            "COM6",
            "COM7",
            "COM8",
            "COM9",
            "LPT1",
            "LPT2",
            "LPT3",
            "LPT4",
            "LPT5",
            "LPT6",
            "LPT7",
            "LPT8",
            "LPT9",
        )

    private val DRIVE_ROOT_REGEX = Regex("^[A-Za-z]:[\\\\/]?$")

    /**
     * Returns true when [path] has a Windows-shaped dangerous pattern that the
     * filesystem or a parser would handle differently from the bytes shown.
     *
     * Applied platform-independently (see #832: a check that fires on macOS and
     * Linux must also fire on Windows for the same input). The categories:
     *
     * - NUL or any ISO control character - truncates the path in any native
     *   call underneath.
     * - UNC path at start (`\\` or `//`) - the canonicalised local path is not
     *   the path the OS will read; the share is named on a remote host.
     * - Trailing dot or space - Windows strips both before resolving, so the
     *   checked string and the resolved file are different bytes.
     * - Drive root (`C:`, `C:\`, `C:/`) - never a project directory; the
     *   existence probe would otherwise succeed for `isDirectory` on the root.
     * - Reserved-name path component (CON, PRN, AUX, NUL, COM1-9, LPT1-9) -
     *   opens a device, not the directory the string names.
     */
    @Suppress("ReturnCount")
    internal fun hasWindowsDangerousShape(path: String): Boolean {
        if (path.isEmpty()) return false
        if (path.any { it.isISOControl() }) return true
        if (path.startsWith("\\\\") || path.startsWith("//")) return true

        // Trailing dot or space (Windows strips both before resolving). Skip when the
        // path is nothing but dots and spaces - there is nothing left to strip TO, so
        // `." and `..` (caught by the `..` check in callers) are not Windows bypasses.
        val trimmed = path.trimEnd('.', ' ')
        if (trimmed.isNotEmpty() && trimmed.length < path.length) return true

        // Drive root: "C:", "C:\", "C:/" and case-insensitive variants. The
        // regex matches the whole string so a drive letter buried inside a
        // longer path does not match.
        if (DRIVE_ROOT_REGEX.matches(path)) return true

        val components = path.split('\\', '/')
        for (component in components) {
            if (component.isEmpty()) continue
            val normalized = component.trimEnd('.', ' ').uppercase()
            if (normalized in WINDOWS_RESERVED_NAMES) return true
        }
        return false
    }

    /**
     * Validates file path for security.
     * Prevents path traversal attacks and other malicious patterns.
     *
     * For a path that is only going to be **read** (opening a file in the
     * editor), use [isValidOpenTargetPath]: these rules assume the path may
     * reach a shell and reject legal filenames that never would.
     *
     * This is the strict gate used for paths whose value will become a terminal
     * working directory (`open_workspace` `projectPath`, `open_terminal`
     * `workingDirectory`, the `boss://folder` deep link). It is therefore run
     * BEFORE any path parsing/canonicalisation on every platform, so a check
     * that fires on macOS and Linux fires on Windows for the same input. The
     * Windows-shaped cases that would otherwise slip through - reserved names
     * like CON/PRN, drive roots, UNC paths, trailing dot/space, control
     * characters - are caught by [hasWindowsDangerousShape].
     */
    fun isValidPath(path: String): Boolean {
        // Check for null bytes
        if (path.contains(' ')) {
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

        // Windows-shaped dangerous patterns must fire on every platform: the
        // strict gate is the only defence between a caller-supplied path and
        // a terminal working directory, so it cannot depend on a Windows
        // parser or filesystem to bail out for us. See #832.
        if (hasWindowsDangerousShape(path)) {
            return false
        }

        return true
    }

    /**
     * Longest terminal command BOSS will type into a shell - well past anything
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
     * allow-list of commands would rule out the legitimate use - `boss terminal
     * -c` exists precisely to run whatever the operator types - without ruling
     * out much else, so **who asked** is decided separately by
     * [ai.rever.boss.utils.DeepLinkOrigin]: only a request the operator made
     * themselves runs without a prompt.
     *
     * Control characters are rejected because the command is written into a
     * shell followed by a single Enter - an embedded line break would submit
     * further lines that nothing ever displayed, so keeping the command to one
     * line is what makes the text shown equal to the text that runs. The NUL
     * byte the previous check looked for is one of them.
     */
    fun isValidCommand(command: String): Boolean =
        command.isNotBlank() &&
            command.length <= MAX_COMMAND_LENGTH &&
            command.none { it.isISOControl() }
}
