package ai.rever.boss.utils

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.RegisteredMcpTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class ToolSchemaCacheTest {
    @BeforeTest
    fun resetCache() = clearToolSchemaCache()

    @Test
    fun `cache reuses equal schema content`() {
        val schema = """{"type":"object","properties":{"a":{"type":"string"}}}"""

        assertSame(parseToolSchema(schema), parseToolSchema(String(schema.toCharArray())))
    }

    @Test
    fun `encoding preserves changed and malformed schema semantics`() {
        val schemaA = """{"type":"object","properties":{"a":{"type":"string"}}}"""
        val schemaB = """{"type":"object","properties":{"b":{"type":"string"}}}"""
        val malformed = "{malformed json"

        val encoded =
            Json.parseToJsonElement(
                encodeMcpTools(
                    listOf(
                        createTool("tool-a", schemaA),
                        createTool("tool-b", schemaB),
                        createTool("tool-invalid", malformed),
                    ),
                ),
            ) as JsonArray

        assertEquals(Json.parseToJsonElement(schemaA), encoded[0].jsonObject["inputSchema"])
        assertEquals(Json.parseToJsonElement(schemaB), encoded[1].jsonObject["inputSchema"])
        assertNotEquals(encoded[0].jsonObject["inputSchema"], encoded[1].jsonObject["inputSchema"])
        assertEquals(malformed, encoded[2].jsonObject["inputSchema"]?.jsonPrimitive?.content)
    }

    @Test
    fun `cache evicts least recently used schema after reaching its bound`() {
        val firstSchema = """{"type":"object","title":"first"}"""
        val firstParse = parseToolSchema(firstSchema)
        repeat(128) { index ->
            parseToolSchema("""{"type":"object","title":"schema-$index"}""")
        }

        assertNotSame(firstParse, parseToolSchema(firstSchema))
    }

    @Test
    fun `oversized schemas bypass the cache`() {
        val oversizedSchema = """{"type":"object","description":"${"x".repeat(64 * 1024)}"}"""

        assertNotSame(parseToolSchema(oversizedSchema), parseToolSchema(oversizedSchema))
    }

    private fun createTool(
        name: String,
        schema: String,
    ) = RegisteredMcpTool(
        "pluginId",
        McpToolDefinition(
            name = name,
            description = "desc",
            inputSchema = schema,
            handler = { McpToolResult("ok") },
        ),
    )
}
