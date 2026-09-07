package ai.rever.boss.mcp

import ai.rever.boss.plugin.logging.LogSanitizer

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

    /** Renders every value to its string form and applies the redaction rules above. */
    fun sanitize(args: Map<String, Any?>): Map<String, String> =
        args.mapValues { (key, value) ->
            val text = value?.toString() ?: "null"
            when {
                sensitiveKeyWords.any { key.contains(it, ignoreCase = true) } -> "[REDACTED]"
                credentialShapePattern.containsMatchIn(text) -> LogSanitizer.maskToken(text)
                else -> text
            }
        }
}
