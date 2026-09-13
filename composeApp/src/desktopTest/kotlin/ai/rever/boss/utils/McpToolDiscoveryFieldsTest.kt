package ai.rever.boss.utils

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.RegisteredMcpTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * What the discovery channel says about each tool.
 *
 * Separate from `SingleInstanceChannelTest`, which is about the channel itself: its
 * descriptor, its wire format and its live behaviour. These are about the shape of
 * one response, and the split keeps that class under detekt's `LargeClass` threshold
 * rather than adding a suppression to carry the difference.
 */
class McpToolDiscoveryFieldsTest {
    private fun tool(
        name: String = "t",
        providerId: String = "test",
        readOnly: Boolean = true,
    ): RegisteredMcpTool =
        RegisteredMcpTool(
            providerId,
            McpToolDefinition(
                name = name,
                description = "test",
                readOnly = readOnly,
                handler = { McpToolResult("ok") },
            ),
        )

    private fun encode(tools: List<RegisteredMcpTool>) = Json.parseToJsonElement(encodeMcpTools(tools)).jsonArray

    private fun readOnlyOf(entry: JsonObject): Boolean? = entry["readOnly"]?.jsonPrimitive?.booleanOrNull

    /**
     * `readOnly` sits beside `requiresAdmin` and `requiredPermissions` in
     * `McpToolDefinition` and was the only one of the three the channel dropped, so a
     * client could learn who may call a tool but not whether calling it changes
     * anything.
     */
    @Test
    fun `tool discovery carries each tool's own readOnly declaration`() {
        val encoded = encode(listOf(tool(name = "t0", readOnly = true), tool(name = "t1", readOnly = false)))
        assertEquals(true, readOnlyOf(encoded[0].jsonObject))
        assertEquals(false, readOnlyOf(encoded[1].jsonObject))
    }

    /**
     * The api defaults `readOnly` to `true`, so a tool whose author never considered
     * the question publishes itself as read-only. That is the fail-open direction and
     * it is why the field is documented as a hint rather than a guarantee.
     *
     * This pins the default at the boundary where it becomes visible to clients. If
     * the api ever flips it, this test fails by name instead of BOSS quietly
     * publishing the opposite claim about every undeclared tool.
     */
    @Test
    fun `an undeclared tool publishes the api's read-only default`() {
        val declaredNothing =
            RegisteredMcpTool(
                "test",
                McpToolDefinition(
                    name = "undeclared",
                    description = "test",
                    handler = { McpToolResult("ok") },
                ),
            )
        assertEquals(true, readOnlyOf(encode(listOf(declaredNothing)).single().jsonObject))
    }

    /**
     * Discovery is additive: adding a field must not disturb the five a client already
     * reads, and `boss mcp list --json` is a documented output that scripts parse.
     */
    @Test
    fun `adding readOnly leaves the other discovery fields intact`() {
        val guarded =
            RegisteredMcpTool(
                "provider-id",
                McpToolDefinition.withRbac(
                    name = "guarded",
                    description = "Reads a file",
                    readOnly = false,
                    requiredPermissions = listOf("files.read"),
                    requiresAdmin = true,
                    handler = { McpToolResult("ok") },
                ),
            )
        val entry = encode(listOf(guarded)).single().jsonObject
        assertEquals("guarded", entry["name"]?.jsonPrimitive?.content)
        assertEquals("Reads a file", entry["description"]?.jsonPrimitive?.content)
        assertEquals("provider-id", entry["pluginId"]?.jsonPrimitive?.content)
        assertEquals(true, entry["requiresAdmin"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, readOnlyOf(entry))
        assertNotNull(entry["requiredPermissions"])
        assertNotNull(entry["inputSchema"])
    }
}
