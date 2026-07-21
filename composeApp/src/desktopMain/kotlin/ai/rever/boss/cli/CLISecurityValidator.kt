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
    fun isValidUrl(url: String): Boolean {
        return url.startsWith("http://") || url.startsWith("https://")
    }

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
     * Validates file path for security.
     * Prevents path traversal attacks and other malicious patterns.
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
     * Validates terminal command for security.
     */
    fun isValidCommand(command: String): Boolean {
        // Check for null bytes
        if (command.contains('\u0000')) {
            return false
        }

        // For now, allow all commands
        // Could add whitelist or blacklist in future
        return true
    }
}
