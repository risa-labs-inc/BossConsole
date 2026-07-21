package ai.rever.boss.plugin.logging

import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Utilities for sanitizing sensitive data before logging.
 *
 * SECURITY: These functions MUST be used when logging any potentially sensitive data:
 * - Email addresses
 * - Tokens (access, refresh, magic link, etc.)
 * - Credential IDs
 * - URIs with authentication parameters
 * - User IDs
 *
 * ## Usage
 * ```kotlin
 * logger.info(LogCategory.AUTH, "Processing login",
 *     data = mapOf("email" to LogSanitizer.maskEmail(email)))
 * ```
 */
object LogSanitizer {
    // Use SLF4J directly to avoid recursive logging through BossLogger
    private val logger = LoggerFactory.getLogger("LogSanitizer")

    /**
     * Mask email address for logging.
     * Example: "user@example.com" -> "u***@e***.com"
     */
    fun maskEmail(email: String?): String {
        if (email.isNullOrBlank()) return "[empty]"

        return try {
            val parts = email.split("@")
            if (parts.size != 2) return "[invalid-email]"

            val localPart = parts[0]
            val domainParts = parts[1].split(".")

            val maskedLocal = if (localPart.length <= 1) {
                "*"
            } else {
                "${localPart.first()}${"*".repeat(minOf(localPart.length - 1, 3))}"
            }

            val maskedDomain = if (domainParts.isEmpty()) {
                "[invalid-domain]"
            } else {
                val firstDomainPart = domainParts.first()
                val maskedFirstPart = if (firstDomainPart.length <= 1) {
                    "*"
                } else {
                    "${firstDomainPart.first()}${"*".repeat(minOf(firstDomainPart.length - 1, 3))}"
                }
                (listOf(maskedFirstPart) + domainParts.drop(1)).joinToString(".")
            }

            "$maskedLocal@$maskedDomain"
        } catch (e: Exception) {
            "[email-mask-error]"
        }
    }

    /**
     * Mask a token for logging.
     * Shows first 3 and last 3 characters only.
     * Example: "abc123def456ghi789" -> "abc...789"
     */
    fun maskToken(token: String?): String {
        if (token.isNullOrBlank()) return "[empty]"

        return if (token.length <= 6) {
            "***"
        } else {
            "${token.take(3)}...${token.takeLast(3)}"
        }
    }

    /**
     * Mask a credential ID for logging.
     * Replaces the entire ID with a placeholder.
     */
    fun maskCredentialId(credentialId: String?): String {
        if (credentialId.isNullOrBlank()) return "[empty]"
        return "[CREDENTIAL_ID:${credentialId.length}chars]"
    }

    /**
     * Mask a user ID for logging.
     * Shows first 4 characters only.
     */
    fun maskUserId(userId: String?): String {
        if (userId.isNullOrBlank()) return "[empty]"

        return if (userId.length <= 4) {
            "****"
        } else {
            "${userId.take(4)}..."
        }
    }

    /**
     * Mask sensitive parameters in URIs.
     * Redacts: token, access_token, refresh_token, code, error_description
     *
     * Example:
     * "boss://auth?token=abc123&type=signup" -> "boss://auth?token=[REDACTED]&type=signup"
     */
    fun maskUriParams(uri: String?): String {
        if (uri.isNullOrBlank()) return "[empty]"

        val sensitiveParams = setOf(
            "token",
            "access_token",
            "refresh_token",
            "code",
            "error_description",
            "id_token",
            "session_token",
            "api_key",
            "key",
            "secret"
        )

        return try {
            // Handle both query params (?) and fragment params (#)
            var result = uri

            // Mask query parameters
            val queryStart = uri.indexOf('?')
            if (queryStart >= 0) {
                result = maskParamsInSegment(result, queryStart + 1, '#', sensitiveParams)
            }

            // Mask fragment parameters
            val fragmentStart = result.indexOf('#')
            if (fragmentStart >= 0) {
                result = maskParamsInSegment(result, fragmentStart + 1, '\u0000', sensitiveParams)
            }

            result
        } catch (e: Exception) {
            logger.warn("URI masking failed: ${e.message}")
            "[uri-mask-error]"
        }
    }

    private fun maskParamsInSegment(
        uri: String,
        startIndex: Int,
        endChar: Char,
        sensitiveParams: Set<String>
    ): String {
        val endIndex = if (endChar == '\u0000') uri.length else uri.indexOf(endChar).let { if (it < 0) uri.length else it }
        val segment = uri.substring(startIndex, endIndex)

        val maskedSegment = segment.split("&").joinToString("&") { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2 && sensitiveParams.any { parts[0].equals(it, ignoreCase = true) }) {
                "${parts[0]}=[REDACTED]"
            } else {
                param
            }
        }

        return uri.substring(0, startIndex) + maskedSegment + uri.substring(endIndex)
    }

    /**
     * Mask a session ID for logging.
     * Shows first 8 characters only.
     */
    fun maskSessionId(sessionId: String?): String {
        if (sessionId.isNullOrBlank()) return "[empty]"

        return if (sessionId.length <= 8) {
            "****"
        } else {
            "${sessionId.take(8)}..."
        }
    }

    /**
     * Describe a URI safely without exposing sensitive parameters.
     * Returns the scheme and host only for auth URIs.
     *
     * Example: "boss://auth/verify?token=abc" -> "boss://auth/verify (with query params)"
     */
    fun describeUri(uri: String?): String {
        if (uri.isNullOrBlank()) return "[empty]"

        return try {
            val parsed = URI(uri)
            val hasQuery = !parsed.rawQuery.isNullOrBlank()
            val hasFragment = !parsed.rawFragment.isNullOrBlank()

            val base = "${parsed.scheme}://${parsed.host ?: ""}${parsed.path ?: ""}"
            val suffix = when {
                hasQuery && hasFragment -> " (with query and fragment)"
                hasQuery -> " (with query params)"
                hasFragment -> " (with fragment)"
                else -> ""
            }

            base + suffix
        } catch (e: Exception) {
            "[uri-parse-error]"
        }
    }

    /**
     * Check if a string looks like it might be a token/secret.
     * Used for defensive logging to avoid accidentally logging secrets.
     */
    fun looksLikeSecret(value: String?): Boolean {
        if (value.isNullOrBlank()) return false

        // Check for common patterns
        return value.length >= 20 ||
                value.contains("eyJ") ||  // JWT prefix
                value.matches(Regex("^[a-zA-Z0-9_-]{20,}$")) ||
                value.contains("sk_") ||
                value.contains("pk_") ||
                value.contains("ghp_") ||
                value.contains("gho_")
    }

    /**
     * Safely format a map for logging, masking known sensitive keys.
     */
    fun sanitizeMap(map: Map<String, Any?>?): Map<String, Any?> {
        if (map == null) return emptyMap()

        val sensitiveKeys = setOf(
            "token", "access_token", "refresh_token", "password",
            "secret", "api_key", "key", "credential", "credential_id"
        )

        return map.mapValues { (key, value) ->
            when {
                sensitiveKeys.any { key.contains(it, ignoreCase = true) } -> "[REDACTED]"
                value is String && looksLikeSecret(value) -> maskToken(value)
                else -> value
            }
        }
    }

    // Regex patterns for sanitization (compiled once for performance)
    private val filePathPattern = Regex("""(?:/[^\s:]+)+|(?:[A-Za-z]:\\[^\s:]+)+""")
    private val urlPattern = Regex("""https?://[^\s]+""")
    private val emailPattern = Regex("""[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}""")

    /**
     * Sanitize an exception message by removing potentially sensitive data.
     *
     * Removes:
     * - File paths (Unix and Windows)
     * - URLs
     * - Email addresses
     *
     * @param message The exception message to sanitize
     * @return The sanitized message
     */
    fun sanitizeExceptionMessage(message: String?): String {
        if (message.isNullOrBlank()) return "[no message]"

        return try {
            message
                .replace(filePathPattern, "[PATH]")
                .replace(urlPattern, "[URL]")
                .replace(emailPattern, "[EMAIL]")
        } catch (e: Exception) {
            "[sanitization-error]"
        }
    }

    /**
     * Sanitize a log message by removing potentially sensitive data.
     * Uses the same rules as sanitizeExceptionMessage.
     *
     * @param message The log message to sanitize
     * @return The sanitized message
     */
    fun sanitizeLogMessage(message: String?): String {
        return sanitizeExceptionMessage(message)
    }

    /**
     * Sanitize a stack trace by removing file paths and other sensitive data.
     *
     * @param stackTrace The stack trace string to sanitize
     * @return The sanitized stack trace
     */
    fun sanitizeStackTrace(stackTrace: String?): String {
        if (stackTrace.isNullOrBlank()) return "[no stack trace]"

        return try {
            stackTrace
                .replace(filePathPattern, "[PATH]")
                .replace(urlPattern, "[URL]")
                .replace(emailPattern, "[EMAIL]")
        } catch (e: Exception) {
            "[sanitization-error]"
        }
    }
}
