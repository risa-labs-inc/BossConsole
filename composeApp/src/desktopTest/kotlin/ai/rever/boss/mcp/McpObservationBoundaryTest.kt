package ai.rever.boss.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpObservationBoundaryTest {
    @Test
    fun `malformed sensitive and escaped keys never reach a preview`() {
        for (raw in listOf("{\"private_key\":\"PRIVATE_SENTINEL\"", "{\"\\u0074oken\":\"PRIVATE_SENTINEL\",")) {
            val preview = McpObservationPreview.sanitize(raw)
            assertFalse(preview.contains("PRIVATE_SENTINEL"))
            assertTrue(preview.startsWith("[OMITTED:"))
        }
    }

    @Test
    fun `nested arrays redact keys while preserving scalar types`() {
        val preview = McpObservationPreview.sanitize("{\"count\":3,\"items\":[{\"private_key\":\"PRIVATE_SENTINEL\"}]}")
        assertFalse(preview.contains("PRIVATE_SENTINEL"))
        assertFalse(
            Json
                .parseToJsonElement(preview)
                .jsonObject
                .getValue("count")
                .jsonPrimitive.isString,
        )
        assertTrue(preview.contains("[REDACTED]"))
    }

    @Test
    fun `oversize including whitespace and deeply nested payloads are omitted before parsing`() {
        val inputs =
            listOf(
                " ".repeat(20_000) + "{}",
                "[".repeat(10_000),
                "{\"key\":\"SECRET\",\"pad\":\"" + "x".repeat(20_000),
            )
        for (raw in inputs) {
            assertTrue(McpObservationPreview.sanitize(raw).startsWith("[OMITTED:"))
        }
    }

    @Test
    fun `redaction expansion cannot exceed the preview bound`() {
        val raw = (1..1000).joinToString(prefix = "{", postfix = "}") { "\"key$it\":0" }
        val preview = McpObservationPreview.sanitize(raw)
        assertTrue(preview.length <= McpObservationPreview.MAX_CHARS)
        assertTrue(preview.startsWith("[OMITTED:"))
    }

    @Test
    fun `omission markers and expansion respect smaller caller bounds`() {
        for (raw in listOf("x".repeat(20_000), "[".repeat(20), "{\"key\":0}")) {
            assertTrue(McpObservationPreview.sanitize(raw, maxChars = 3).length <= 3)
        }
    }

    @Test
    fun `brackets inside quoted values do not consume nesting budget`() {
        val raw = "{\"value\":\"" + "[".repeat(20) + "\"}"
        assertEquals(raw, McpObservationPreview.sanitize(raw))
    }

    @Test
    fun `private key prose and string values use the same sensitive vocabulary`() {
        val inputs =
            listOf("private_key: PRIVATE_SENTINEL", "ssh_key = PRIVATE_SENTINEL", "auth_token=PRIVATE_SENTINEL")
        for (raw in inputs) {
            assertFalse(McpObservationPreview.sanitize(raw).contains("PRIVATE_SENTINEL"))
            val json = JsonObject(mapOf("note" to JsonPrimitive(raw)))
            assertFalse(McpObservationPreview.sanitize(json.toString()).contains("PRIVATE_SENTINEL"))
        }
    }

    @Test
    fun `complete and truncated PEM private keys are redacted in prose and JSON strings`() {
        for (suffix in listOf("", "\n-----END RSA PRIVATE KEY-----")) {
            val raw = "-----BEGIN RSA PRIVATE KEY-----\nPRIVATE_SENTINEL" + suffix
            assertFalse(McpObservationPreview.sanitize(raw).contains("PRIVATE_SENTINEL"))
            val json = JsonObject(mapOf("note" to JsonPrimitive(raw)))
            assertFalse(McpObservationPreview.sanitize(json.toString()).contains("PRIVATE_SENTINEL"))
        }
    }

    @Test
    fun `conservative observer redaction does not hide operator command arguments`() {
        val command = "git log --author=jane --keyword=search --sortKey=name --tokenizer=bpe"
        assertEquals(command, McpArgumentSanitizer.sanitizeMessage(command))
        assertEquals(command, McpArgumentSanitizer.sanitize(mapOf("command" to command))["command"])
        val quotedHeader = "ssh reported -----BEGIN OPENSSH PRIVATE KEY----- followed by an ordinary diagnostic"
        assertEquals(quotedHeader, McpArgumentSanitizer.sanitizeMessage(quotedHeader))
        assertFalse(McpObservationPreview.sanitize("private_key=PRIVATE_SENTINEL").contains("PRIVATE_SENTINEL"))
    }
}
