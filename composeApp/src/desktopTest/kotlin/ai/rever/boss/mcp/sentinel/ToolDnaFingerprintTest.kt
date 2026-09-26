package ai.rever.boss.mcp.sentinel

import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.RegisteredMcpTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ToolDnaFingerprintTest {
    private val dummyHandler = McpToolHandler { McpToolResult("ok") }

    @Test
    fun `same tool definition produces identical fingerprint`() {
        val tool1 = RegisteredMcpTool(
            providerId = "provider_a",
            definition = McpToolDefinition(
                name = "test_tool",
                description = "Tool description",
                inputSchema = """{"type":"object","properties":{"a":{"type":"string"},"b":{"type":"number"}},"required":["a"]}""",
                handler = dummyHandler,
            )
        )

        val tool2 = RegisteredMcpTool(
            providerId = "provider_a",
            definition = McpToolDefinition(
                name = "test_tool",
                description = "Tool description",
                inputSchema = """{"type":"object","properties":{"a":{"type":"string"},"b":{"type":"number"}},"required":["a"]}""",
                handler = dummyHandler,
            )
        )

        val fp1 = ToolDnaFingerprinter.computeFingerprint(tool1)
        val fp2 = ToolDnaFingerprinter.computeFingerprint(tool2)

        assertEquals(fp1.fingerprint, fp2.fingerprint)
    }

    @Test
    fun `reordered JSON schema keys produce identical fingerprint`() {
        val tool1 = RegisteredMcpTool(
            providerId = "provider_a",
            definition = McpToolDefinition(
                name = "test_tool",
                description = "Tool description",
                inputSchema = """{"type":"object","properties":{"a":{"type":"string"},"b":{"type":"number"}},"required":["a","b"]}""",
                handler = dummyHandler,
            )
        )

        val tool2 = RegisteredMcpTool(
            providerId = "provider_a",
            definition = McpToolDefinition(
                name = "test_tool",
                description = "Tool description",
                inputSchema = """{"required":["b","a"],"properties":{"b":{"type":"number"},"a":{"type":"string"}},"type":"object"}""",
                handler = dummyHandler,
            )
        )

        val fp1 = ToolDnaFingerprinter.computeFingerprint(tool1)
        val fp2 = ToolDnaFingerprinter.computeFingerprint(tool2)

        assertEquals(fp1.fingerprint, fp2.fingerprint, "Key order invariance must produce identical fingerprint")
    }

    @Test
    fun `changed description produces different fingerprint`() {
        val tool1 = RegisteredMcpTool(
            providerId = "provider_a",
            definition = McpToolDefinition(
                name = "test_tool",
                description = "Original description",
                inputSchema = """{"type":"object"}""",
                handler = dummyHandler,
            )
        )

        val tool2 = RegisteredMcpTool(
            providerId = "provider_a",
            definition = McpToolDefinition(
                name = "test_tool",
                description = "Modified description with new text",
                inputSchema = """{"type":"object"}""",
                handler = dummyHandler,
            )
        )

        val fp1 = ToolDnaFingerprinter.computeFingerprint(tool1)
        val fp2 = ToolDnaFingerprinter.computeFingerprint(tool2)

        assertNotEquals(fp1.fingerprint, fp2.fingerprint)
    }

    @Test
    fun `changed schema produces different fingerprint`() {
        val tool1 = RegisteredMcpTool(
            providerId = "provider_a",
            definition = McpToolDefinition(
                name = "test_tool",
                description = "Tool description",
                inputSchema = """{"type":"object","properties":{"a":{"type":"string"}}}""",
                handler = dummyHandler,
            )
        )

        val tool2 = RegisteredMcpTool(
            providerId = "provider_a",
            definition = McpToolDefinition(
                name = "test_tool",
                description = "Tool description",
                inputSchema = """{"type":"object","properties":{"a":{"type":"string"},"new_param":{"type":"boolean"}}}""",
                handler = dummyHandler,
            )
        )

        val fp1 = ToolDnaFingerprinter.computeFingerprint(tool1)
        val fp2 = ToolDnaFingerprinter.computeFingerprint(tool2)

        assertNotEquals(fp1.fingerprint, fp2.fingerprint)
    }

    @Test
    fun `different provider ID produces different fingerprint`() {
        val def = McpToolDefinition(name = "tool", description = "desc", handler = dummyHandler)
        val tool1 = RegisteredMcpTool(providerId = "provider_1", definition = def)
        val tool2 = RegisteredMcpTool(providerId = "provider_2", definition = def)

        val fp1 = ToolDnaFingerprinter.computeFingerprint(tool1)
        val fp2 = ToolDnaFingerprinter.computeFingerprint(tool2)

        assertNotEquals(fp1.fingerprint, fp2.fingerprint)
    }

    @Test
    fun `algorithm version string is included in computation`() {
        val tool = RegisteredMcpTool(
            providerId = "p",
            definition = McpToolDefinition(name = "t", description = "d", handler = dummyHandler)
        )

        val fpV1 = ToolDnaFingerprinter.computeFingerprint(tool, version = "v1")
        val fpV2 = ToolDnaFingerprinter.computeFingerprint(tool, version = "v2")

        assertNotEquals(fpV1.fingerprint, fpV2.fingerprint)
    }
}
