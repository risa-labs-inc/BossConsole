package ai.rever.boss.mcp

import ai.rever.boss.plugin.logging.LogSanitizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Sanitizes MCP tool arguments before they reach an operator (the approval dialog) or
 * disk (the operation ledger).
 *
 * Deliberately narrower than [LogSanitizer.sanitizeMap]: that function treats any string
 * of 20 or more characters as secret-shaped ([LogSanitizer.looksLikeSecret]) and masks it
 * via [LogSanitizer.maskToken] - which is exactly wrong here, since a long file path, URL,
 * or shell command is both longer than 20 characters and the thing an operator most needs
 * to read before approving a mutating tool call. A value is only masked here when its key
 * names it as sensitive, or its shape is unambiguously a credential (JWT, GitHub token,
 * sk_/pk_ vendor key) - never on length alone.
 */
object McpArgumentSanitizer {
    private val sensitiveKeyWords =
        setOf("token", "password", "secret", "api_key", "apikey", "key", "credential")

    /** Same credential shapes [LogSanitizer] recognizes: a JWT, a GitHub token, or a vendor sk_/pk_ key. */
    private val credentialShapePattern =
        Regex(
            "(?<![A-Za-z0-9_.])(?:" +
                """eyJ[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*""" +
                "|(?:gh[pousr]_|github_pat_)[A-Za-z0-9_]{8,}" +
                "|(?:sk|pk)[-_][A-Za-z0-9_-]{8,}" +
                ")",
        )

    /** Parse only for audit/approval; malformed input must never reach those surfaces verbatim. */
    @Suppress("TooGenericExceptionCaught") // Invalid nested JSON must not enter the audit surface verbatim.
    fun parseArguments(raw: String): Map<String, Any?> =
        try {
            if (raw.length > 16_384) {
                mapOf("arguments" to "[OMITTED: too large]")
            } else {
                (Json.parseToJsonElement(raw) as? JsonObject)?.toMap()
                    ?: mapOf("arguments" to "[OMITTED: invalid JSON object]")
            }
        } catch (_: Exception) {
            mapOf("arguments" to "[OMITTED: invalid JSON]")
        }

    fun sanitize(args: Map<String, Any?>): Map<String, String> = sanitizeMap(args, 0)

    private fun sanitizeMap(
        args: Map<String, Any?>,
        depth: Int,
    ): Map<String, String> =
        args.mapValues { (key, value) ->
            if (sensitiveKeyWords.any { key.contains(it, ignoreCase = true) } || key.contains("auth", true)) {
                "[REDACTED]"
            } else {
                sanitizeValue(value, depth).take(4096)
            }
        }

    private fun sanitizeValue(
        value: Any?,
        depth: Int,
    ): String =
        if (depth >= 8) {
            "[OMITTED: too deeply nested]"
        } else {
            when (value) {
                is JsonObject -> {
                    sanitizeMap(value.toMap(), depth + 1).toString()
                }

                is JsonArray -> {
                    value.joinToString(prefix = "[", postfix = "]") { sanitizeValue(it, depth + 1) }
                }

                is JsonPrimitive -> {
                    sanitizeMessage(value.content)
                }

                is Map<*, *> -> {
                    val nested = value.entries.associate { it.key.toString() to it.value }
                    sanitizeMap(nested, depth + 1).toString()
                }

                is Iterable<*> -> {
                    value.joinToString(prefix = "[", postfix = "]") { sanitizeValue(it, depth + 1) }
                }

                else -> {
                    sanitizeMessage(value?.toString() ?: "null")
                }
            }
        }

    /** Authorization is special: consume generic scheme words before the credential value. */
    private val authorizationHeader =
        Regex(
            """(?i)authorization[ \t]*[:=][ \t]*""" +
                """(?:[A-Za-z][A-Za-z0-9._~+/-]*[ \t]+){0,3}(?:"[^"]*"|'[^']*'|[^\s&,;}]+)""",
        )

    private val sensitiveAssignment =
        Regex(
            """(?i)(?:password|token|secret|api[_-]?key|credential)""" +
                """\s*[:=]\s*(?:"[^"]*"|'[^']*'|[^\s&,;}]+)""",
        )

    /**
     * `curl -u user:secret` / `--user user:secret`: the credential follows a flag, not a key.
     *
     * `(?!//)` is load-bearing. `redis-cli -u` takes a URL, not `user:pass`, so without it
     * `-u redis://cache.example.invalid:6379` matches with `redis` as the user and `//cache.internal:6379`
     * as the secret, and a line carrying no credential at all is blanked. A URL passed this way is
     * left to [LogSanitizer.redactUrlUserInfo], which masks it only when there is userinfo to mask.
     */
    private val basicAuthFlag =
        Regex("""(?i)(--user|-u)([ \t]+)[^\s:/@]+:(?!//)[^\s]+""")

    private val bearer = Regex("""(?i)Bearer\s+[^\s"',;}]+""")

    /**
     * URI userinfo is redacted by [LogSanitizer.redactUrlUserInfo], the helper merged for the
     * logging path in #640, rather than by a rule of this object's own. A second implementation
     * would be a second dialect of URI parsing to keep in step, and this one already handles what
     * a regex here got wrong: the authority ends at the first `/`, `?` or `#`, and the LAST `@` in
     * it is the delimiter, so a password containing an `@` (`user:p@ss@host`) is removed whole
     * instead of leaving its tail behind.
     */
    fun sanitizeMessage(text: String): String =
        LogSanitizer
            .redactUrlUserInfo(text)
            .replace(credentialShapePattern, "[REDACTED]")
            .replace(sensitiveAssignment, "[REDACTED]")
            .replace(authorizationHeader, "[REDACTED]")
            .replace(bearer, "Bearer [REDACTED]")
            .replace(basicAuthFlag, "$1$2[REDACTED]")
}
