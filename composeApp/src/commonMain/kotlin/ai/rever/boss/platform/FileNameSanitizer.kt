package ai.rever.boss.platform

import ai.rever.boss.utils.extractFileName

/**
 * Utility for sanitizing file names to prevent security issues and ensure cross-platform compatibility.
 *
 * Handles:
 * - Path traversal attacks (../, absolute paths)
 * - Windows reserved device names (CON, PRN, AUX, NUL, COM1-9, LPT1-9)
 * - Control characters and invalid characters
 * - Path separators (/, \)
 * - Trailing dots and spaces on Windows
 * - Empty or overly long file names
 */
object FileNameSanitizer {
    private const val MAX_FILENAME_LENGTH = 255

    // Windows reserved device names
    private val WINDOWS_RESERVED_NAMES =
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

    /**
     * Sanitizes a file name to be safe for file system operations.
     *
     * @param fileName The suggested file name from download
     * @param replacement Character to replace invalid characters with (default: underscore)
     * @return A sanitized file name safe for all platforms
     */
    fun sanitize(
        fileName: String,
        replacement: Char = '_',
    ): String {
        if (fileName.isBlank()) {
            return "download"
        }

        var sanitized = fileName.trim()

        // 1. Prevent path traversal - reject absolute paths or path segments
        if (sanitized.startsWith("/") || sanitized.startsWith("\\") ||
            sanitized.contains("..") || sanitized.contains(":/")
        ) {
            // Extract just the file name part
            sanitized = sanitized.extractFileName()
        }

        // 2. Remove control characters (0x00-0x1F, 0x7F-0x9F)
        sanitized =
            sanitized.filter { char ->
                char.code !in 0x00..0x1F && char.code !in 0x7F..0x9F
            }

        // 3. Replace path separators and other invalid characters
        // Invalid on Windows: < > : " / \ | ? *
        // For maximum compatibility, restrict to: a-z A-Z 0-9 _ - . ( ) [ ] space
        sanitized =
            sanitized
                .map { char ->
                    when {
                        char in 'a'..'z' -> char
                        char in 'A'..'Z' -> char
                        char in '0'..'9' -> char
                        char in setOf('_', '-', '.', '(', ')', '[', ']', ' ') -> char
                        else -> replacement
                    }
                }.joinToString("")

        // 4. Handle Windows reserved names. See [defuseDeviceName] for why the check is
        // against the segment before the FIRST dot. The extension is computed from the
        // LAST dot - "what kind of file is this" - and feeds only the fallback in step 6.
        val extension =
            if (sanitized.contains('.')) {
                "." + sanitized.substringAfterLast('.')
            } else {
                ""
            }

        sanitized = defuseDeviceName(sanitized, replacement)

        // 5. Remove trailing dots and spaces (invalid on Windows)
        sanitized = sanitized.trimEnd('.', ' ')

        // 6. Ensure at least one character remains.
        if (sanitized.isBlank() || sanitized == extension) {
            // The extension is trimmed again on the way in. For a suggested name of
            // "." or "..." the extension computed in step 4 is a lone ".", and
            // appending it raw handed back "download.", re-creating exactly the
            // trailing dot step 5 exists to remove.
            sanitized = "download" + extension.trimEnd('.', ' ')
        }

        // 7. Truncate to maximum length, keeping the extension when it fits.
        if (sanitized.length > MAX_FILENAME_LENGTH) {
            sanitized = truncate(sanitized, replacement)
        }

        return sanitized
    }

    /**
     * Cut [name] to [MAX_FILENAME_LENGTH], keeping the extension only if there is room.
     *
     * Nothing bounds how long an extension can be: it is whatever follows the last dot
     * in a name the download source chose. A suggested name of "a." followed by 300
     * characters produces an extension of 301, and subtracting that from the limit gave
     * a negative budget which then reached `take()`, which rejects a negative count and
     * throws out of the download handler.
     *
     * The extension is recomputed from [name] rather than taken from the caller. The
     * caller's copy was computed before the trailing-dot trim, and for exactly the names
     * this class exists for - ones ending in a dot - it is a stale lone "." that makes a
     * real extension (".pdf") look as if it did not fit.
     *
     * When the extension cannot fit, it is abandoned rather than shortened. A truncated
     * extension is not the file's type, so inventing one would be worse than having
     * none, and a name that is nothing but an extension was never worth preserving.
     *
     * The cut can also undo a step 4 decision: trimming can shrink the base onto a
     * reserved device name step 4 missed, because a space before the dot ("CON .") keeps
     * the checked segment out of the reserved set. Whatever the cut leaves is therefore
     * defused with [replacement], the way step 4 defuses.
     */
    private fun truncate(
        name: String,
        replacement: Char,
    ): String {
        val cut = cutTo(name, MAX_FILENAME_LENGTH, replacement)
        // Defusing prepends one character. Rather than let that push the result over the
        // limit, the cut is redone one character shorter. One redo is always enough:
        // every branch of cutTo returns at most limit + 1 (the defuse is the only thing
        // that can add a character), so the redo at limit - 1 lands at or under the
        // limit even when it defuses in turn.
        return if (cut.length <= MAX_FILENAME_LENGTH) {
            cut
        } else {
            cutTo(name, MAX_FILENAME_LENGTH - 1, replacement)
        }
    }

    /** One pass of the cut: shorten to [limit], tidy the tail, then defuse. */
    private fun cutTo(
        name: String,
        limit: Int,
        replacement: Char,
    ): String {
        val extension =
            if (name.contains('.')) {
                "." + name.substringAfterLast('.')
            } else {
                ""
            }
        val budget = limit - extension.length
        val truncated =
            if (budget <= 0) {
                // The extension alone would fill the whole limit, so there is no room
                // to keep it: preserve the base name only, cut to the limit.
                name.substringBeforeLast('.').take(limit)
            } else {
                name.substringBeforeLast('.').take(budget) + extension
            }
        // Cutting mid-name can expose a dot or space that step 5 had removed, so the
        // Windows rule is re-applied to whatever the cut produced.
        val tidied = truncated.trimEnd('.', ' ').ifBlank { "download" }
        return defuseDeviceName(tidied, replacement)
    }

    /**
     * Prefix [name] when Windows would resolve it to a character device rather than a file.
     *
     * The comparison is against the segment before the **first** dot, not the last. Win32
     * stops parsing a device name there, so `NUL.txt` and `CON.tar.gz` are both the device,
     * which Microsoft's own naming rules state in as many words. Comparing the segment
     * before the *last* dot missed every multi-dot case, and let truncation manufacture a
     * device out of a name that was not one: cutting `CONSOLE.` followed by 251 characters
     * to fit the limit leaves `CON.` and the rest, which is the console.
     *
     * Trailing spaces are trimmed before the comparison for the same reason: Windows
     * ignores them, so `CON .txt` is the device too.
     */
    private fun defuseDeviceName(
        name: String,
        replacement: Char,
    ): String {
        val device = name.substringBefore('.').trimEnd(' ')
        return if (device.uppercase() in WINDOWS_RESERVED_NAMES) "$replacement$name" else name
    }

    /**
     * Checks if a file name appears to be an executable that should trigger a security warning.
     *
     * @param fileName The file name to check
     * @return true if the file is a potentially dangerous executable type
     */
    fun isExecutableFile(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase()

        return when (extension) {
            // Windows executables
            "exe", "msi", "bat", "cmd", "com", "scr", "vbs", "ps1", "psm1" -> true

            // macOS executables
            "app", "pkg", "dmg", "command" -> true

            // Linux/Unix executables
            "sh", "run", "bin" -> true

            // Scripts
            "js", "jar", "py", "rb", "pl" -> true

            else -> false
        }
    }

    /**
     * Validates that a file path only contains a file name and no path components.
     *
     * @param fileName The file name to validate
     * @return true if the file name is safe (no path traversal attempts)
     */
    fun isValidFileName(fileName: String): Boolean =
        !fileName.contains("/") &&
            !fileName.contains("\\") &&
            !fileName.contains("..") &&
            !fileName.startsWith(".") &&
            fileName.isNotBlank()
}
