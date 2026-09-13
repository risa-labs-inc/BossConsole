package ai.rever.boss.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Bounded display previews, never replay arguments. Malformed structured input fails closed. */
internal object McpObservationPreview {
    const val MAX_CHARS = 4096
    private const val MAX_INPUT_CHARS = 16384
    private const val MAX_DEPTH = 8

    fun sanitize(
        raw: String,
        maxChars: Int = MAX_CHARS,
    ): String {
        val limit = if (maxChars > 0) minOf(maxChars, MAX_CHARS) else MAX_CHARS
        val preview =
            when {
                raw.length > MAX_INPUT_CHARS -> "[OMITTED: payload too large]"
                tooDeep(raw) -> "[OMITTED: payload too deeply nested]"
                else -> parsePreview(raw)
            }
        return if (preview.length <= limit) preview else "[OMITTED: sanitized payload too large]".take(limit)
    }

    @Suppress("TooGenericExceptionCaught") // Untrusted JSON is omitted on parse failure.
    private fun parsePreview(raw: String): String =
        try {
            sanitizeElement(Json.parseToJsonElement(raw)).toString()
        } catch (_: Exception) {
            // Do not run a weaker regex on broken or truncated JSON, including escaped keys.
            if (raw.trimStart().firstOrNull() in listOf('{', '[', '"')) {
                "[OMITTED: malformed JSON]"
            } else {
                sanitizeText(raw)
            }
        }

    private fun sanitizeElement(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> {
                JsonObject(
                    element.mapValues { (key, value) ->
                        if (McpArgumentSanitizer.sensitiveKeyWords.any { key.contains(it, ignoreCase = true) }) {
                            JsonPrimitive("[REDACTED]")
                        } else {
                            sanitizeElement(value)
                        }
                    },
                )
            }

            is JsonArray -> {
                JsonArray(element.map(::sanitizeElement))
            }

            is JsonPrimitive -> {
                val sanitized = sanitizeText(element.content)
                if (element.isString || sanitized != element.content) JsonPrimitive(sanitized) else element
            }
        }

    // Conservative prose redaction is observation-only: approval commands must stay readable.
    private val privateKeyBlock =
        Regex("(?is)-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?(?:-----END [A-Z0-9 ]*PRIVATE KEY-----|$)")
    private val sensitiveAssignment =
        Regex(
            """(?i)"?[A-Za-z0-9_-]*(?:${McpArgumentSanitizer.sensitiveKeyWords.joinToString("|")})[A-Za-z0-9_-]*"?""" +
                """\s*[:=]\s*(?:"[^"]*"|'[^']*'|[^\s&,;}]+)""",
        )

    private fun sanitizeText(raw: String): String =
        McpArgumentSanitizer.sanitizeMessage(
            raw.replace(privateKeyBlock, "[REDACTED PRIVATE KEY]").replace(sensitiveAssignment, "[REDACTED]"),
        )

    /** Bound nesting before recursive parsing, while ignoring brackets inside JSON strings. */
    private fun tooDeep(raw: String): Boolean {
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in raw) {
            if (quoted) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> quoted = false
                }
                continue
            }
            when (char) {
                '"' -> quoted = true
                '{', '[' -> depth++
                '}', ']' -> depth--
            }
            if (depth > MAX_DEPTH) return true
        }
        return false
    }
}
