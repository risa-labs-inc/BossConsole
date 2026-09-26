package ai.rever.boss.mcp.sentinel

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.RegisteredMcpTool
import kotlin.test.Test
import kotlin.test.assertTrue

class ToolShadowingDetectorTest {
    @Test
    fun `detects exact name collision across different providers`() {
        val tools =
            listOf(
                AttackSimulationFixtures.SHADOWING_COLLISION_TOOL_1,
                AttackSimulationFixtures.SHADOWING_COLLISION_TOOL_2,
            )

        val findings = ToolShadowingDetector.detectShadowing(tools)

        assertTrue(findings.isNotEmpty())
        assertTrue(findings.any { it.collisionType == "EXACT_NAME_COLLISION" })
    }

    @Test
    fun `detects normalized name collision across providers`() {
        val dummyHandler = McpToolHandler { McpToolResult("ok") }
        val def1 = McpToolDefinition(name = "read_file", description = "test", handler = dummyHandler)
        val def2 = McpToolDefinition(name = "read-file", description = "test", handler = dummyHandler)
        val tools =
            listOf(
                RegisteredMcpTool(providerId = "p1", definition = def1),
                RegisteredMcpTool(providerId = "p2", definition = def2),
            )

        val findings = ToolShadowingDetector.detectShadowing(tools)

        assertTrue(findings.isNotEmpty())
        assertTrue(findings.any { it.collisionType == "NORMALIZED_NAME_COLLISION" })
    }
}
