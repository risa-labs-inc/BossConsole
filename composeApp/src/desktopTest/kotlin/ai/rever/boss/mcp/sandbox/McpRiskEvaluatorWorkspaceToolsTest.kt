package ai.rever.boss.mcp.sandbox

import ai.rever.boss.plugin.api.McpToolArgs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The v9.5.21 MCP workspace tool family, pinned name by name.
 *
 * McpMutatingToolCatalog has classified the workspace lifecycle tools as mutating
 * since the family shipped, but DefaultMcpRiskEvaluator let every one of them fall
 * through to the Unclassified LOW default - so a boss-prefixed call, whose catalog
 * match also fails because the catalog keys bare names, sailed through the
 * read-only default of McpPolicyEngine.policyFor. These tests pin the closed gap:
 * all ten family names, in both spellings, must classify explicitly and land in
 * the tier the approval flow leans on.
 */
class McpRiskEvaluatorWorkspaceToolsTest {
    private val evaluator = DefaultMcpRiskEvaluator()
    private val emptyArgs = McpToolArgs(emptyMap(), "{}")

    /** The family surface: tool name -> the tier an empty-args lookup must return. */
    private val expectedLevels =
        mapOf(
            // Pure reads of the workspace catalog.
            "list_workspaces" to McpRiskLevel.LOW,
            "workspace_list" to McpRiskLevel.LOW,
            // Lifecycle mutations: flat HIGH, no command argument to inspect.
            "open_workspace" to McpRiskLevel.HIGH,
            "workspace_open" to McpRiskLevel.HIGH,
            "create_workspace" to McpRiskLevel.HIGH,
            "workspace_create" to McpRiskLevel.HIGH,
            "close_workspace" to McpRiskLevel.HIGH,
            "workspace_close" to McpRiskLevel.HIGH,
            // The terminal pair keeps its shell classification: HIGH floor.
            "open_terminal" to McpRiskLevel.HIGH,
            "terminal_open" to McpRiskLevel.HIGH,
        )

    @Test
    fun `every workspace family tool classifies at its documented tier, never unclassified`() {
        for ((name, expected) in expectedLevels) {
            val assessment = evaluator.evaluateRisk(name, emptyArgs)
            assertEquals(expected, assessment.level, "$name: ${assessment.reason}")
            assertFalse(assessment.reason.contains("Unclassified"), "$name: ${assessment.reason}")
        }
    }

    @Test
    fun `boss-prefixed workspace family tools classify identically to their bare names`() {
        for ((name, expected) in expectedLevels) {
            val assessment = evaluator.evaluateRisk("mcp__boss__$name", emptyArgs)
            assertEquals(expected, assessment.level, "mcp__boss__$name: ${assessment.reason}")
            assertFalse(
                assessment.reason.contains("Unclassified"),
                "mcp__boss__$name: ${assessment.reason}",
            )
        }
    }

    @Test
    fun `workspace lifecycle tools read as workspace mutations, not shell execution`() {
        val lifecycleTools =
            listOf(
                "open_workspace",
                "workspace_open",
                "create_workspace",
                "workspace_create",
                "close_workspace",
                "workspace_close",
            )
        for (name in lifecycleTools) {
            val assessment = evaluator.evaluateRisk(name, emptyArgs)
            assertTrue(
                assessment.reason.contains("Workspace lifecycle mutation"),
                "$name: ${assessment.reason}",
            )
        }
    }

    @Test
    fun `workspace listing tools read as read-only`() {
        for (name in listOf("list_workspaces", "workspace_list")) {
            val assessment = evaluator.evaluateRisk(name, emptyArgs)
            assertTrue(assessment.reason.contains("Read-only"), "$name: ${assessment.reason}")
        }
    }

    @Test
    fun `the terminal pair keeps its command-wording escalation`() {
        val destructive =
            McpToolArgs(
                mapOf("command" to "rm -rf /tmp/cache"),
                """{"command":"rm -rf /tmp/cache"}""",
            )
        for (name in listOf("open_terminal", "terminal_open")) {
            assertEquals(McpRiskLevel.CRITICAL, evaluator.evaluateRisk(name, destructive).level, name)
        }
    }
}
