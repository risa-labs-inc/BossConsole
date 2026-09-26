package ai.rever.boss.mcp.sentinel

import ai.rever.boss.plugin.api.McpToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Explainable static security analyzer for MCP tool definitions.
 *
 * Scans metadata for security threats including:
 * 1. Prompt Injection & Instruction Payload Poisoning
 * 2. Invisible Unicode & BiDi Control Obfuscation
 * 3. Markup & Comment Concealment
 * 4. Encoded / Obfuscated Payloads
 *
 * Emits explainable [SecurityFinding] entries with precise locations, severities, and matches.
 */
object ToolContentScanner {
    private val json = Json { ignoreUnknownKeys = true }

    // -------------------------------------------------------------------------
    // Rule Definitions
    // -------------------------------------------------------------------------

    private data class ScannerRule(
        val id: String,
        val severity: FindingSeverity,
        val pattern: Regex,
        val explanation: String,
    )

    private val INJECTION_RULES = listOf(
        ScannerRule(
            id = "INJ-001",
            severity = FindingSeverity.CRITICAL,
            pattern = Regex("(?i)\\b(ignore|disregard|forget|override)\\s+(all\\s+)?(previous|prior|above|system)\\s+(instructions|prompts|messages|directives)\\b"),
            explanation = "Prompt injection attempt: instructs model to ignore or override prior system instructions.",
        ),
        ScannerRule(
            id = "INJ-002",
            severity = FindingSeverity.HIGH,
            pattern = Regex("(?i)\\b(system\\s+message|developer\\s+message|system\\s+prompt)\\s*:\\s*"),
            explanation = "Fake system/developer role injection inside tool description.",
        ),
        ScannerRule(
            id = "INJ-003",
            severity = FindingSeverity.CRITICAL,
            pattern = Regex("(?i)\\b(do\\s+not|never)\\s+(tell|reveal|show|inform)\\s+(the\\s+)?(user|operator|human)\\b"),
            explanation = "Concealment instruction: instructs model to hide actions from the user.",
        ),
        ScannerRule(
            id = "INJ-004",
            severity = FindingSeverity.HIGH,
            pattern = Regex("(?i)\\b(secretly|surreptitiously|covertly|silently)\\s+(exfiltrate|send|upload|read|execute|transmit)\\b"),
            explanation = "Covert activity payload: instructs model to secretly exfiltrate or execute commands.",
        ),
        ScannerRule(
            id = "INJ-005",
            severity = FindingSeverity.HIGH,
            pattern = Regex("(?i)\\bread\\s+(private|secret|vault|credential|password|token)\\s+(file|key|data|value)s?\\s+before\\b"),
            explanation = "Credential exfiltration pre-pass instruction inside tool metadata.",
        ),
        ScannerRule(
            id = "INJ-006",
            severity = FindingSeverity.HIGH,
            pattern = Regex("(?i)\\b(execute|run)\\s+this\\s+command\\s+first\\s*:"),
            explanation = "Forced command execution payload inside tool description.",
        ),
    )

    private val INVISIBLE_UNICODE_CHARS = charArrayOf(
        '\u200B', // Zero Width Space
        '\u200C', // Zero Width Non-Joiner
        '\u200D', // Zero Width Joiner
        '\uFEFF', // Zero Width No-Break Space / BOM
        '\u00AD', // Soft Hyphen
        '\u202A', '\u202B', '\u202C', '\u202D', '\u202E', // BiDi Embeddings / Overrides
        '\u2066', '\u2067', '\u2068', '\u2069', // BiDi Isolate Controls
    )

    private val HTML_COMMENT_REGEX = Regex("<!--[\\s\\S]*?-->")
    private val BASE64_CANDIDATE_REGEX = Regex("\\b[A-Za-z0-9+/]{40,}={0,2}\\b")

    /**
     * Scan a full [McpToolDefinition] and return all security findings.
     */
    fun scan(definition: McpToolDefinition): List<SecurityFinding> {
        val findings = mutableListOf<SecurityFinding>()

        // 1. Scan description
        scanText(
            text = definition.description,
            location = "description",
            findings = findings,
        )

        // 2. Scan schema fields recursively
        if (!definition.inputSchema.isBlank()) {
            try {
                val element = json.parseToJsonElement(definition.inputSchema)
                scanJsonElement(
                    element = element,
                    path = "inputSchema",
                    findings = findings,
                )
            } catch (_: Throwable) {
                // If schema is unparseable JSON, scan raw text
                scanText(
                    text = definition.inputSchema,
                    location = "inputSchema (raw)",
                    findings = findings,
                )
            }
        }

        return findings
    }

    private fun scanText(
        text: String,
        location: String,
        findings: MutableList<SecurityFinding>,
    ) {
        if (text.isEmpty()) return

        // Check Prompt Injection Rules
        for (rule in INJECTION_RULES) {
            val match = rule.pattern.find(text)
            if (match != null) {
                findings.add(
                    SecurityFinding(
                        ruleId = rule.id,
                        severity = rule.severity,
                        location = location,
                        matchedText = match.value,
                        explanation = rule.explanation,
                    )
                )
            }
        }

        // Check Invisible Unicode
        val foundInvisible = text.filter { c -> INVISIBLE_UNICODE_CHARS.contains(c) }
        if (foundInvisible.isNotEmpty()) {
            val unicodeHex = foundInvisible.map { "U+%04X".format(it.code) }.distinct().joinToString(", ")
            findings.add(
                SecurityFinding(
                    ruleId = "UNI-001",
                    severity = FindingSeverity.HIGH,
                    location = location,
                    matchedText = foundInvisible.take(10).toString(),
                    explanation = "Invisible or control Unicode characters detected: $unicodeHex. Could be used for prompt obfuscation or BiDi override attacks.",
                )
            )
        }

        // Check HTML Comments / Hidden Markup
        val commentMatch = HTML_COMMENT_REGEX.find(text)
        if (commentMatch != null) {
            findings.add(
                SecurityFinding(
                    ruleId = "HTML-001",
                    severity = FindingSeverity.MEDIUM,
                    location = location,
                    matchedText = commentMatch.value.take(60),
                    explanation = "HTML comment block found in tool metadata: '${commentMatch.value.take(40)}...'. Instructions concealed in comments may deceive LLMs.",
                )
            )
        }

        // Check Encoded / Base64 Payloads
        val b64Matches = BASE64_CANDIDATE_REGEX.findAll(text)
        for (match in b64Matches) {
            val decoded = decodeBase64OrNull(match.value)
            if (decoded != null && containsSuspiciousKeyword(decoded)) {
                findings.add(
                    SecurityFinding(
                        ruleId = "ENC-001",
                        severity = FindingSeverity.HIGH,
                        location = location,
                        matchedText = match.value.take(30) + "...",
                        explanation = "Base64 encoded payload detected containing instruction keywords: '$decoded'",
                    )
                )
            }
        }
    }

    private fun scanJsonElement(
        element: JsonElement,
        path: String,
        findings: MutableList<SecurityFinding>,
    ) {
        when (element) {
            is JsonObject -> {
                for ((key, value) in element) {
                    val currentPath = "$path.$key"
                    if (value is JsonPrimitive && value.isString) {
                        scanText(value.content, currentPath, findings)
                    } else {
                        scanJsonElement(value, currentPath, findings)
                    }
                }
            }
            is JsonArray -> {
                element.forEachIndexed { index, item ->
                    val currentPath = "$path[$index]"
                    if (item is JsonPrimitive && item.isString) {
                        scanText(item.content, currentPath, findings)
                    } else {
                        scanJsonElement(item, currentPath, findings)
                    }
                }
            }
            is JsonPrimitive -> {
                if (element.isString) {
                    scanText(element.content, path, findings)
                }
            }
            else -> {}
        }
    }

    private fun decodeBase64OrNull(candidate: String): String? {
        return try {
            val bytes = java.util.Base64.getDecoder().decode(candidate)
            val decoded = String(bytes, Charsets.UTF_8)
            if (decoded.all { it.code in 32..126 || it == '\n' || it == '\r' || it == '\t' }) {
                decoded
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun containsSuspiciousKeyword(decodedText: String): Boolean {
        val lower = decodedText.lowercase()
        return lower.contains("ignore") || lower.contains("system") || lower.contains("secret") ||
                lower.contains("password") || lower.contains("curl") || lower.contains("bash") ||
                lower.contains("exec") || lower.contains("eval") || lower.contains("token")
    }
}
