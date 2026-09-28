package ai.rever.boss.mcp.sentinel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolDnaDiffEngineTest {
    @Test
    fun `detects description change`() {
        val diff =
            ToolDnaDiffEngine.computeDiff(
                oldDescription = "Original description",
                oldSchemaJson = """{"type":"object"}""",
                oldReadOnly = true,
                oldRequiresAdmin = false,
                newDefinition = AttackSimulationFixtures.POISONED_READ_FILE_DEF,
            )

        assertTrue(diff.hasChanges)
        assertTrue(diff.categories.contains(ChangeCategory.DESCRIPTION_CHANGED))
    }

    @Test
    fun `detects destructive parameter addition and capability expansion`() {
        val diff =
            ToolDnaDiffEngine.computeDiff(
                oldDescription = "Read a text file from the current workspace project directory.",
                oldSchemaJson = """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""",
                oldReadOnly = true,
                oldRequiresAdmin = false,
                newDefinition = AttackSimulationFixtures.SCHEMA_RUG_PULL_DEF,
            )

        assertTrue(diff.hasChanges)
        assertTrue(diff.categories.contains(ChangeCategory.DESTRUCTIVE_PARAMETER_ADDED))
        assertTrue(diff.categories.contains(ChangeCategory.CAPABILITY_EXPANSION))
    }

    @Test
    fun `identical definitions produce no diff`() {
        val diff =
            ToolDnaDiffEngine.computeDiff(
                oldDescription = "Read a text file from the current workspace project directory.",
                oldSchemaJson = """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""",
                oldReadOnly = true,
                oldRequiresAdmin = false,
                newDefinition = AttackSimulationFixtures.BENIGN_READ_FILE_DEF,
            )

        assertFalse(diff.hasChanges)
        assertTrue(diff.diffDetails.isEmpty())
    }

    @Test
    fun `benign parameter names do not trigger DESTRUCTIVE_PARAMETER_ADDED`() {
        val benignSchema =
            """{"type":"object","properties":{"output_format":{"type":"string"},""" +
                """"force_refresh":{"type":"boolean"},""" +
                """"clean_build":{"type":"boolean"},""" +
                """"dropdown_id":{"type":"string"}}}"""
        val def =
            ai.rever.boss.plugin.api.McpToolDefinition(
                name = "benign_tool",
                description = "Tool with benign formatting and cache refresh options",
                inputSchema = benignSchema,
                handler =
                    ai.rever.boss.plugin.api
                        .McpToolHandler {
                            ai.rever.boss.plugin.api
                                .McpToolResult("ok")
                        },
            )

        val diff =
            ToolDnaDiffEngine.computeDiff(
                oldDescription = "Tool with benign formatting and cache refresh options",
                oldSchemaJson = """{"type":"object"}""",
                oldReadOnly = false,
                oldRequiresAdmin = false,
                newDefinition = def,
            )

        assertFalse(
            diff.categories.contains(ChangeCategory.DESTRUCTIVE_PARAMETER_ADDED),
            "Benign parameters must not be flagged as DESTRUCTIVE",
        )
    }

    @Test
    fun `genuine destructive parameter names trigger DESTRUCTIVE_PARAMETER_ADDED`() {
        val destructiveSchema =
            """{"type":"object","properties":{"force_delete":{"type":"boolean"},"wipe_database":{"type":"boolean"}}}"""
        val def =
            ai.rever.boss.plugin.api.McpToolDefinition(
                name = "destructive_tool",
                description = "Tool with destructive parameters",
                inputSchema = destructiveSchema,
                handler =
                    ai.rever.boss.plugin.api
                        .McpToolHandler {
                            ai.rever.boss.plugin.api
                                .McpToolResult("ok")
                        },
            )

        val diff =
            ToolDnaDiffEngine.computeDiff(
                oldDescription = "Tool with destructive parameters",
                oldSchemaJson = """{"type":"object"}""",
                oldReadOnly = false,
                oldRequiresAdmin = false,
                newDefinition = def,
            )

        assertTrue(
            diff.categories.contains(ChangeCategory.DESTRUCTIVE_PARAMETER_ADDED),
            "Genuinely destructive parameters must be flagged as DESTRUCTIVE",
        )
    }
}
