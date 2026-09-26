package ai.rever.boss.mcp.sentinel

import ai.rever.boss.plugin.api.McpToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Explainable static security analyzer for MCP tool definitions.
 *
 * Scans metadata for security threats including:
 * 1. Prompt Injection & Instruction Payload Poisoning
 * 2. Invisible Unicode, BiDi Controls & Tag Character Obfuscation
 * 3. Markup & Comment Concealment
 * 4. Encoded / Obfuscated Payloads
 *
 * Emits explainable [SecurityFinding] entries with precise locations, severities, and matches.
 */
object ToolContentScanner {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Scan a full [McpToolDefinition] and return all security findings.
     */
    fun scan(definition: McpToolDefinition): List<SecurityFinding> {
        val findings = mutableListOf<SecurityFinding>()

        // 1. Scan tool name (attacker-controlled)
        scanText(
            text = definition.name,
            location = "name",
            findings = findings,
        )

        // 2. Scan description
        scanText(
            text = definition.description,
            location = "description",
            findings = findings,
        )

        // 3. Scan schema fields recursively
        if (definition.inputSchema.isNotBlank()) {
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
        ScannerHelpers.scanPromptInjections(text, location, findings)
        ScannerHelpers.scanUnicodeObfuscation(text, location, findings)
        ScannerHelpers.scanHtmlComments(text, location, findings)
        ScannerHelpers.scanEncodedPayloads(text, location, findings)
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
                    scanText(key, "$currentPath (key)", findings)
                    scanJsonElement(value, currentPath, findings)
                }
            }

            is JsonArray -> {
                element.forEachIndexed { index, item ->
                    scanJsonElement(item, "$path[$index]", findings)
                }
            }

            is JsonPrimitive -> {
                if (element.isString) {
                    scanText(element.content, path, findings)
                }
            }
        }
    }
}

private object ScannerHelpers {
    private data class ScannerRule(
        val id: String,
        val severity: FindingSeverity,
        val pattern: Regex,
        val explanation: String,
    )

    private val INJECTION_RULES =
        listOf(
            ScannerRule(
                id = "INJ-001",
                severity = FindingSeverity.CRITICAL,
                pattern =
                    Regex(
                        "(?i)\\b(ignore|disregard|forget|override)\\s+" +
                            "(all\\s+)?(previous|prior|above|system)\\s+" +
                            "(instructions|prompts|messages|directives)\\b",
                    ),
                explanation =
                    "Prompt injection attempt: instructs model to ignore or override prior " +
                        "system instructions.",
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
                pattern =
                    Regex(
                        "(?i)\\b(do\\s+not|never)\\s+(tell|reveal|show|inform)\\s+(the\\s+)?(user|operator|human)\\b",
                    ),
                explanation = "Concealment instruction: instructs model to hide actions from the user.",
            ),
            ScannerRule(
                id = "INJ-004",
                severity = FindingSeverity.HIGH,
                pattern =
                    Regex(
                        "(?i)\\b(secretly|surreptitiously|covertly|silently)\\s+" +
                            "(exfiltrate|send|upload|read|execute|transmit)\\b",
                    ),
                explanation = "Covert activity payload: instructs model to secretly exfiltrate or execute commands.",
            ),
            ScannerRule(
                id = "INJ-005",
                severity = FindingSeverity.HIGH,
                pattern =
                    Regex(
                        "(?i)\\bread\\s+(private|secret|vault|credential|password|token)\\s+" +
                            "(file|key|data|value)s?\\s+before\\b",
                    ),
                explanation = "Credential exfiltration pre-pass instruction inside tool metadata.",
            ),
            ScannerRule(
                id = "INJ-006",
                severity = FindingSeverity.HIGH,
                pattern = Regex("(?i)\\b(execute|run)\\s+this\\s+command\\s+first\\s*:"),
                explanation = "Forced command execution payload inside tool description.",
            ),
        )

    private val HTML_COMMENT_REGEX = Regex("<!--[\\s\\S]*?-->")
    private val BASE64_CANDIDATE_REGEX = Regex("\\b[A-Za-z0-9+/]{40,}={0,2}\\b")

    fun scanPromptInjections(
        text: String,
        location: String,
        findings: MutableList<SecurityFinding>,
    ) {
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
                    ),
                )
            }
        }
    }

    fun scanUnicodeObfuscation(
        text: String,
        location: String,
        findings: MutableList<SecurityFinding>,
    ) {
        val detectedHex = mutableListOf<String>()
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            if (isSuspiciousCodePoint(codePoint)) {
                detectedHex.add(formatCodePoint(codePoint))
            }
            offset += Character.charCount(codePoint)
        }

        if (detectedHex.isNotEmpty()) {
            val unicodeSummary = detectedHex.distinct().take(10).joinToString(", ")
            findings.add(
                SecurityFinding(
                    ruleId = "UNI-001",
                    severity = FindingSeverity.HIGH,
                    location = location,
                    matchedText = unicodeSummary,
                    explanation = "Invisible, control, or tag Unicode characters detected: $unicodeSummary.",
                ),
            )
        }
    }

    fun scanHtmlComments(
        text: String,
        location: String,
        findings: MutableList<SecurityFinding>,
    ) {
        val commentMatch = HTML_COMMENT_REGEX.find(text)
        if (commentMatch != null) {
            val snippet = commentMatch.value.take(40)
            findings.add(
                SecurityFinding(
                    ruleId = "HTML-001",
                    severity = FindingSeverity.MEDIUM,
                    location = location,
                    matchedText = commentMatch.value.take(60),
                    explanation = "HTML comment block found in tool metadata: '$snippet...'.",
                ),
            )
        }
    }

    fun scanEncodedPayloads(
        text: String,
        location: String,
        findings: MutableList<SecurityFinding>,
    ) {
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
                    ),
                )
            }
        }
    }

    private fun isSuspiciousCodePoint(cp: Int): Boolean =
        (cp in 0x200B..0x200D) || cp == 0xFEFF || cp == 0x00AD ||
            (cp in 0x202A..0x202E) || (cp in 0x2066..0x2069) ||
            cp == 0x200E || cp == 0x200F || cp == 0x061C ||
            (cp in 0xE0000..0xE007F) || (cp in 0xFE00..0xFE0F) || (cp in 0xE0100..0xE01EF)

    private fun formatCodePoint(cp: Int): String = if (cp <= 0xFFFF) "U+%04X".format(cp) else "U+%05X".format(cp)

    private fun isPrintableAscii(c: Char): Boolean {
        val code = c.code
        return code in 32..126 || c == '\n' || c == '\r' || c == '\t'
    }

    private fun decodeBase64OrNull(candidate: String): String? =
        try {
            val bytes =
                java.util.Base64
                    .getDecoder()
                    .decode(candidate)
            val decoded = String(bytes, Charsets.UTF_8)
            if (decoded.all { isPrintableAscii(it) }) decoded else null
        } catch (_: Throwable) {
            null
        }

    private fun containsSuspiciousKeyword(decodedText: String): Boolean {
        val lower = decodedText.lowercase()
        return lower.contains("ignore") || lower.contains("system") || lower.contains("secret") ||
            lower.contains("password") || lower.contains("curl") || lower.contains("bash") ||
            lower.contains("exec") || lower.contains("eval") || lower.contains("token")
    }
}
