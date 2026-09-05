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

            val maskedLocal =
                if (localPart.length <= 1) {
                    "*"
                } else {
                    "${localPart.first()}${"*".repeat(minOf(localPart.length - 1, 3))}"
                }

            val maskedDomain =
                if (domainParts.isEmpty()) {
                    "[invalid-domain]"
                } else {
                    val firstDomainPart = domainParts.first()
                    val maskedFirstPart =
                        if (firstDomainPart.length <= 1) {
                            "*"
                        } else {
                            "${firstDomainPart.first()}${"*".repeat(minOf(firstDomainPart.length - 1, 3))}"
                        }
                    (listOf(maskedFirstPart) + domainParts.drop(1)).joinToString(".")
                }

            "$maskedLocal@$maskedDomain"
        } catch (ignored: Exception) {
            // Deliberately unlogged: LogSanitizer runs inside the logging pipeline,
            // so logging from here could recurse. The placeholder marks the failure.
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

        val sensitiveParams =
            setOf(
                "token",
                "access_token",
                "refresh_token",
                "code",
                "error_description",
                "id_token",
                "session_token",
                "api_key",
                "key",
                "secret",
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
        sensitiveParams: Set<String>,
    ): String {
        val endIndex = if (endChar == '\u0000') uri.length else uri.indexOf(endChar).let { if (it < 0) uri.length else it }
        val segment = uri.substring(startIndex, endIndex)

        val maskedSegment =
            segment.split("&").joinToString("&") { param ->
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
            val suffix =
                when {
                    hasQuery && hasFragment -> " (with query and fragment)"
                    hasQuery -> " (with query params)"
                    hasFragment -> " (with fragment)"
                    else -> ""
                }

            base + suffix
        } catch (ignored: Exception) {
            // Deliberately unlogged: LogSanitizer runs inside the logging pipeline,
            // so logging from here could recurse. The placeholder marks the failure.
            "[uri-parse-error]"
        }
    }

    // -------------------------------------------------------------------------
    // Patterns and vocabularies for sanitization.
    //
    // All compiled/allocated once: every log line and every crash report is put
    // through them, so none of this may be rebuilt per call.
    // -------------------------------------------------------------------------

    private val filePathPattern = Regex("""(?:/[^\s:]+)+|(?:[A-Za-z]:\\[^\s:]+)+""")
    private val urlPattern = Regex("""https?://[^\s]+""")
    private val emailPattern = Regex("""[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}""")

    /**
     * Private DNS names that can reveal an organisation's internal topology in
     * network failures. Kept to the private-style suffixes measured in #109 so
     * ordinary dotted prose and package names remain diagnostic.
     */
    private val privateHostnamePattern =
        Regex(
            """(?<![A-Za-z0-9_.-])(?:[A-Za-z0-9-]+\.)+(?:internal|local)(?![A-Za-z0-9_.-])""",
            RegexOption.IGNORE_CASE,
        )

    /**
     * Runs of text that are a credential by their own structure, wherever they
     * appear: a JWT (three base64url segments — the first is the base64url of a
     * JSON header, which is why every JWT begins `eyJ`), a GitHub token prefix,
     * or a vendor `sk_`/`pk_` key prefix.
     *
     * Each alternative is anchored on the left by a boundary that rules out word
     * characters and `.`, so a name that merely *contains* one of these prefixes
     * keeps its text: `task_manager_configuration` is not a Stripe key, and
     * neither is `com.example.pk_utilities`. That precision is what makes the
     * pattern safe to run over message text and stack traces, which consist
     * mostly of long class and method names.
     */
    private val credentialShapePattern =
        Regex(
            "(?<![A-Za-z0-9_.])(?:" +
                """eyJ[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*""" +
                "|(?:gh[pousr]_|github_pat_)[A-Za-z0-9_]{8,}" +
                "|(?:sk|pk)[-_][A-Za-z0-9_-]{8,}" +
                ")",
        )

    /**
     * A `name=value` pair inside a message — the shape a quoted config line,
     * environment entry or command line arrives in. Group 1 is the name, group 2
     * the value.
     *
     * Only the value is ever masked. The name is what makes a report actionable
     * (it says *which* setting was wrong) and is not itself the secret.
     *
     * A value whose first character is `[` is excluded, so a value an earlier
     * pass already replaced keeps the more informative result: `token=/home/me/x`
     * becomes `token=[PATH]` rather than degrading further to `token=***`.
     */
    private val assignmentPattern = Regex("""(?<![A-Za-z0-9_.])([A-Za-z][A-Za-z0-9_.-]*)=([^\s\[][^\s]*)""")

    /** Inserts a word boundary into camelCase names, so `accessToken` splits like `access_token`. */
    private val camelCaseBoundary = Regex("""(?<=[a-z0-9])(?=[A-Z])""")

    /**
     * Names whose value is sensitive. Shared by [sanitizeMap] and the
     * `name=value` pass of [redactLocationsAndCredentials] so that the map path
     * and the free-text path cannot drift apart; [nameMarksSecret] explains why
     * free text matches this list more strictly than a map key does.
     */
    private val sensitiveValueNames =
        setOf(
            "token",
            "access_token",
            "refresh_token",
            "password",
            "secret",
            "api_key",
            "key",
            "credential",
            "credential_id",
        )

    /**
     * Values kept verbatim even under a sensitive name: they cannot be
     * credential material, and they answer the question a reader actually has.
     * `token=null` is a useful thing to read; `token=***` is not.
     */
    private val nonSecretValues = setOf("null", "true", "false")

    /**
     * Check if a string looks like it might be a token/secret.
     * Used for defensive logging to avoid accidentally logging secrets.
     *
     * This is the *value-position* test: the argument is a single datum a caller
     * chose to log, so mere length is reason enough to mask it here. Message text
     * is held to the stricter [credentialShapePattern] instead, because a run of
     * 20-plus characters inside a sentence or a stack frame is usually just a
     * long identifier.
     */
    fun looksLikeSecret(value: String?): Boolean {
        if (value.isNullOrBlank()) return false

        // Check for common patterns.
        //
        // A former `^[a-zA-Z0-9_-]{20,}$` alternative is gone: it required a
        // length of 20 or more, so the first condition below already covered
        // every string it could match, and it compiled a fresh Regex per call.
        return value.length >= 20 ||
            value.contains("eyJ") || // JWT prefix
            value.contains("sk_") ||
            value.contains("pk_") ||
            value.contains("ghp_") ||
            value.contains("gho_") ||
            credentialShapePattern.containsMatchIn(value)
    }

    /**
     * Safely format a map for logging, masking known sensitive keys.
     *
     * A key is matched against [sensitiveValueNames] by substring: the caller
     * named this field deliberately, so `userAccessTokenV2` redacts like `token`.
     * Message text is matched more narrowly — see [nameMarksSecret].
     */
    fun sanitizeMap(map: Map<String, Any?>?): Map<String, Any?> {
        if (map == null) return emptyMap()

        return map.mapValues { (key, value) ->
            when {
                sensitiveValueNames.any { key.contains(it, ignoreCase = true) } -> "[REDACTED]"
                value is String && looksLikeSecret(value) -> maskToken(value)
                else -> value
            }
        }
    }

    /**
     * Whether the name of a `name=value` pair marks its value as sensitive.
     *
     * Matched per word rather than by substring, which is the one deliberate
     * difference from [sanitizeMap]'s key test. A map key is a field the caller
     * named; a name lifted out of arbitrary message text is not, and substring
     * matching there would mask the value of `KEYBOARD_LAYOUT` for containing
     * "key". Words are split on the separators [assignmentPattern] admits, plus
     * camelCase boundaries, so `SUPABASE_ANON_KEY`, `api_key` and `apiKey` all
     * yield a "key" word while `KEYBOARD_LAYOUT` yields "keyboard".
     *
     * The multi-word entries of [sensitiveValueNames] ("access_token",
     * "credential_id", ...) can never equal a single word; their "token", "key"
     * and "credential" words do, so nothing is left uncovered.
     */
    private fun nameMarksSecret(name: String): Boolean =
        name
            .replace(camelCaseBoundary, "_")
            .split('_', '-', '.')
            .any { word -> word.isNotEmpty() && sensitiveValueNames.any { word.equals(it, ignoreCase = true) } }

    /**
     * The shared body of [sanitizeExceptionMessage] and [sanitizeStackTrace].
     *
     * The order of the passes is deliberate. Locations go first, and
     * [filePathPattern]'s `(?:/[^\s:]+)+` consumes everything after a scheme's
     * colon — so a value carried in a URL query string or fragment, which is the
     * usual way one reaches a message, is already `[PATH]` by the time the later
     * passes see the text. Those later passes exist for what that cannot reach:
     * a credential written into a message on its own, with no URL or path around
     * it.
     *
     * The passes compose in either order because [maskToken] is a fixed point on
     * its own output at these lengths (`ghp...345` masks to `ghp...345`), so a
     * value both of them match is masked once in effect.
     */
    private fun redactLocationsAndCredentials(text: String): String {
        val withoutLocations =
            text
                .replace(filePathPattern, "[PATH]")
                .replace(urlPattern, "[URL]")
                .replace(emailPattern, "[EMAIL]")
                .replace(privateHostnamePattern, "[HOST]")

        val withMaskedAssignments =
            assignmentPattern.replace(withoutLocations) { match ->
                val (name, value) = match.destructured
                if (nameMarksSecret(name) && value.lowercase() !in nonSecretValues) {
                    "$name=${maskToken(value)}"
                } else {
                    match.value
                }
            }

        return credentialShapePattern.replace(withMaskedAssignments) { match -> maskToken(match.value) }
    }

    /**
     * Sanitize an exception message by removing potentially sensitive data.
     *
     * Removes:
     * - File paths (Unix and Windows)
     * - URLs
     * - Email addresses
     * - Credentials recognisable by shape: JWTs, GitHub tokens, `sk_`/`pk_` keys
     * - The value of a `name=value` pair whose name marks it sensitive
     *
     * @param message The exception message to sanitize
     * @return The sanitized message
     */
    fun sanitizeExceptionMessage(message: String?): String {
        if (message.isNullOrBlank()) return "[no message]"

        return try {
            redactLocationsAndCredentials(message)
        } catch (ignored: Exception) {
            // Deliberately unlogged: LogSanitizer runs inside the logging pipeline,
            // so logging from here could recurse. The placeholder marks the failure.
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
    fun sanitizeLogMessage(message: String?): String = sanitizeExceptionMessage(message)

    /**
     * Sanitize a stack trace by removing file paths and other sensitive data.
     *
     * Applies the same rules as [sanitizeExceptionMessage], which a stack trace
     * needs too: the `Caused by:` lines of a trace are exception messages, and a
     * value quoted into one arrives here rather than there.
     *
     * @param stackTrace The stack trace string to sanitize
     * @return The sanitized stack trace
     */
    fun sanitizeStackTrace(stackTrace: String?): String {
        if (stackTrace.isNullOrBlank()) return "[no stack trace]"

        return try {
            redactLocationsAndCredentials(stackTrace)
        } catch (ignored: Exception) {
            // Deliberately unlogged: LogSanitizer runs inside the logging pipeline,
            // so logging from here could recurse. The placeholder marks the failure.
            "[sanitization-error]"
        }
    }
}
