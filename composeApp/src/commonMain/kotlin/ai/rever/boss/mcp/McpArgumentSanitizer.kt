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
    private val bearer = Regex("""(?i)Bearer\s+[^\s"',;}]+""")

    /**
     * A credential handed to a command-line client as basic auth, `curl -u admin:hunter2` or
     * `--user admin:hunter2`. The value has no sensitive key, is not an assignment and has no
     * vendor prefix, so nothing above sees it. The value must carry the `user:password` colon:
     * `-u` is also `git push -u origin` and `python -u`, and an operator has to be able to read
     * those. A URL after `-u` (`redis-cli -u redis://...`) is not basic auth either: its userinfo
     * was redacted by the pass before this one and its host must stay readable.
     */
    private val basicAuthFlag =
        Regex(
            """(?<![A-Za-z0-9_-])(-u|--user)(?:[ \t]+|=)(?!["']?[A-Za-z][A-Za-z0-9+.-]*://)""" +
                """(?:"[^"]*:[^"]*"|'[^']*:[^']*'|[^\s&,;}"']+:[^\s&,;}"']*)""",
        )

    /**
     * Shapes the issue measured leaking that the vendor-prefix rule above does not cover: an AWS
     * access key id (`AKIA` or `ASIA` plus 16 upper-case alphanumerics, the documented format) and
     * a PEM private-key block, whose base64 body follows the BEGIN line.
     */
    private val awsAccessKeyId = Regex("""(?<![A-Z0-9])(?:AKIA|ASIA)[A-Z0-9]{16}(?![A-Z0-9])""")
    private val pemPrivateKey =
        Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[A-Za-z0-9+/=\s]*(?:-----END [A-Z ]*PRIVATE KEY-----)?""")

    /**
     * Ordered so each rule sees the text the ones before it produced. The URI userinfo pass runs
     * first: `postgres://admin:hunter2@host` is the commonest way a real credential reaches a
     * terminal command, and it is neither an assignment nor a known shape. #640 added the helper
     * for the logging path; the MCP path is where the same value reaches the approval dialog and
     * the ledger on disk (#886).
     */
    fun sanitizeMessage(text: String): String =
        LogSanitizer
            .redactUrlUserInfo(text)
            .replace(pemPrivateKey, "[REDACTED]")
            .replace(awsAccessKeyId, "[REDACTED]")
            .replace(basicAuthFlag, "$1 [REDACTED]")
            .replace(credentialShapePattern, "[REDACTED]")
            .replace(sensitiveAssignment, "[REDACTED]")
            .replace(authorizationHeader, "[REDACTED]")
            .replace(bearer, "Bearer [REDACTED]")
}
