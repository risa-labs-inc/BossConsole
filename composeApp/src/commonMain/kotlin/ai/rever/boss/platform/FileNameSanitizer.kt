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
    private val WINDOWS_RESERVED_NAMES = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )

    /**
     * Sanitizes a file name to be safe for file system operations.
     *
     * @param fileName The suggested file name from download
     * @param replacement Character to replace invalid characters with (default: underscore)
     * @return A sanitized file name safe for all platforms
     */
    fun sanitize(fileName: String, replacement: Char = '_'): String {
        if (fileName.isBlank()) {
            return "download"
        }

        var sanitized = fileName.trim()

        // 1. Prevent path traversal - reject absolute paths or path segments
        if (sanitized.startsWith("/") || sanitized.startsWith("\\") ||
            sanitized.contains("..") || sanitized.contains(":/")) {
            // Extract just the file name part
            sanitized = sanitized.extractFileName()
        }

        // 2. Remove control characters (0x00-0x1F, 0x7F-0x9F)
        sanitized = sanitized.filter { char ->
            char.code !in 0x00..0x1F && char.code !in 0x7F..0x9F
        }

        // 3. Replace path separators and other invalid characters
        // Invalid on Windows: < > : " / \ | ? *
        // For maximum compatibility, restrict to: a-z A-Z 0-9 _ - . ( ) [ ] space
        sanitized = sanitized.map { char ->
            when {
                char in 'a'..'z' -> char
                char in 'A'..'Z' -> char
                char in '0'..'9' -> char
                char in setOf('_', '-', '.', '(', ')', '[', ']', ' ') -> char
                else -> replacement
            }
        }.joinToString("")

        // 4. Handle Windows reserved names
        val nameWithoutExtension = sanitized.substringBeforeLast('.', sanitized)
        val extension = if (sanitized.contains('.')) {
            "." + sanitized.substringAfterLast('.')
        } else {
            ""
        }

        if (nameWithoutExtension.uppercase() in WINDOWS_RESERVED_NAMES) {
            sanitized = "${replacement}${nameWithoutExtension}${extension}"
        }

        // 5. Remove trailing dots and spaces (invalid on Windows)
        sanitized = sanitized.trimEnd('.', ' ')

        // 6. Ensure at least one character remains
        if (sanitized.isBlank() || sanitized == extension) {
            sanitized = "download${extension}"
        }

        // 7. Truncate to maximum length while preserving extension
        if (sanitized.length > MAX_FILENAME_LENGTH) {
            val ext = if (extension.isNotEmpty()) extension else ""
            val maxNameLength = MAX_FILENAME_LENGTH - ext.length
            val baseName = sanitized.substringBeforeLast('.').take(maxNameLength)
            sanitized = baseName + ext
        }

        return sanitized
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
    fun isValidFileName(fileName: String): Boolean {
        return !fileName.contains("/") &&
               !fileName.contains("\\") &&
               !fileName.contains("..") &&
               !fileName.startsWith(".") &&
               fileName.isNotBlank()
    }
}
