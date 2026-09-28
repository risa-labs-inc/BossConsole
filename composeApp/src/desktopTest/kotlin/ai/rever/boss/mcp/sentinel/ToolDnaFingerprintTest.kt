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
        val schema = """{"type":"object","properties":{"a":{"type":"string"},"b":{"type":"number"}},"required":["a"]}"""
        val tool1 =
            RegisteredMcpTool(
                providerId = "provider_a",
                definition =
                    McpToolDefinition(
                        name = "test_tool",
                        description = "Tool description",
                        inputSchema = schema,
                        handler = dummyHandler,
                    ),
            )

        val tool2 =
            RegisteredMcpTool(
                providerId = "provider_a",
                definition =
                    McpToolDefinition(
                        name = "test_tool",
                        description = "Tool description",
                        inputSchema = schema,
                        handler = dummyHandler,
                    ),
            )

        val fp1 = ToolDnaFingerprinter.computeFingerprint(tool1)
        val fp2 = ToolDnaFingerprinter.computeFingerprint(tool2)

        assertEquals(fp1.fingerprint, fp2.fingerprint)
    }

    @Test
    fun `reordered JSON schema keys produce identical fingerprint`() {
        val schema1 =
            """{"type":"object","properties":{"a":{"type":"string"},"b":{"type":"number"}},""" +
                """"required":["a","b"]}"""
        val schema2 =
            """{"required":["b","a"],"properties":{"b":{"type":"number"},"a":{"type":"string"}},""" +
                """"type":"object"}"""

        val tool1 =
            RegisteredMcpTool(
                providerId = "provider_a",
                definition =
                    McpToolDefinition(
                        name = "test_tool",
                        description = "Tool description",
                        inputSchema = schema1,
                        handler = dummyHandler,
                    ),
            )

        val tool2 =
            RegisteredMcpTool(
                providerId = "provider_a",
                definition =
                    McpToolDefinition(
                        name = "test_tool",
                        description = "Tool description",
                        inputSchema = schema2,
                        handler = dummyHandler,
                    ),
            )

        val fp1 = ToolDnaFingerprinter.computeFingerprint(tool1)
        val fp2 = ToolDnaFingerprinter.computeFingerprint(tool2)

        assertEquals(fp1.fingerprint, fp2.fingerprint, "Key order invariance must produce identical fingerprint")
    }

    @Test
    fun `changed description produces different fingerprint`() {
        val tool1 =
            RegisteredMcpTool(
                providerId = "provider_a",
                definition =
                    McpToolDefinition(
                        name = "test_tool",
                        description = "Original description",
                        inputSchema = """{"type":"object"}""",
                        handler = dummyHandler,
                    ),
            )

        val tool2 =
            RegisteredMcpTool(
                providerId = "provider_a",
                definition =
                    McpToolDefinition(
                        name = "test_tool",
                        description = "Modified description with new text",
                        inputSchema = """{"type":"object"}""",
                        handler = dummyHandler,
                    ),
            )

        val fp1 = ToolDnaFingerprinter.computeFingerprint(tool1)
        val fp2 = ToolDnaFingerprinter.computeFingerprint(tool2)

        assertNotEquals(fp1.fingerprint, fp2.fingerprint)
    }

    @Test
    fun `changed schema produces different fingerprint`() {
        val tool1 =
            RegisteredMcpTool(
                providerId = "provider_a",
                definition =
                    McpToolDefinition(
                        name = "test_tool",
                        description = "Tool description",
                        inputSchema = """{"type":"object","properties":{"a":{"type":"string"}}}""",
                        handler = dummyHandler,
                    ),
            )

        val schema2 = """{"type":"object","properties":{"a":{"type":"string"},"new_param":{"type":"boolean"}}}"""
        val tool2 =
            RegisteredMcpTool(
                providerId = "provider_a",
                definition =
                    McpToolDefinition(
                        name = "test_tool",
                        description = "Tool description",
                        inputSchema = schema2,
                        handler = dummyHandler,
                    ),
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
        val tool =
            RegisteredMcpTool(
                providerId = "p",
                definition = McpToolDefinition(name = "t", description = "d", handler = dummyHandler),
            )

        val fpV1 = ToolDnaFingerprinter.computeFingerprint(tool, version = "v1")
        val fpV2 = ToolDnaFingerprinter.computeFingerprint(tool, version = "v2")

        assertNotEquals(fpV1.fingerprint, fpV2.fingerprint)
    }

    @Test
    fun `permissions list serialization is collision-safe`() {
        val def1 =
            McpToolDefinition(
                name = "t",
                description = "d",
                handler = dummyHandler,
            ).apply {
                requiredPermissions = listOf("read,write")
            }
        val def2 =
            McpToolDefinition(
                name = "t",
                description = "d",
                handler = dummyHandler,
            ).apply {
                requiredPermissions = listOf("read", "write")
            }

        val tool1 = RegisteredMcpTool(providerId = "p", definition = def1)
        val tool2 = RegisteredMcpTool(providerId = "p", definition = def2)

        val fp1 = ToolDnaFingerprinter.computeFingerprint(tool1)
        val fp2 = ToolDnaFingerprinter.computeFingerprint(tool2)

        val msg = "['read,write'] and ['read', 'write'] must produce different fingerprints"
        assertNotEquals(fp1.fingerprint, fp2.fingerprint, msg)
    }

    @Test
    fun `reordered permissions produce identical fingerprint`() {
        val def1 =
            McpToolDefinition(
                name = "t",
                description = "d",
                handler = dummyHandler,
            ).apply {
                requiredPermissions = listOf("write", "read")
            }
        val def2 =
            McpToolDefinition(
                name = "t",
                description = "d",
                handler = dummyHandler,
            ).apply {
                requiredPermissions = listOf("read", "write")
            }

        val tool1 = RegisteredMcpTool(providerId = "p", definition = def1)
        val tool2 = RegisteredMcpTool(providerId = "p", definition = def2)

        val fp1 = ToolDnaFingerprinter.computeFingerprint(tool1)
        val fp2 = ToolDnaFingerprinter.computeFingerprint(tool2)

        assertEquals(fp1.fingerprint, fp2.fingerprint, "Reordered permissions must produce identical fingerprint")
    }
}
