package ai.rever.boss.profile

import ai.rever.boss.mcp.McpMutatingToolCatalog
import ai.rever.boss.mcp.sandbox.DefaultMcpRiskEvaluator
import ai.rever.boss.mcp.sandbox.McpRiskLevel
import ai.rever.boss.plugin.api.McpToolArgs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The whole approval gate on tools that open a window or start a process: pinned name by name. */
class BossProfileMcpPolicyTest {
    private val emptyArgs = McpToolArgs(emptyMap(), "{}")

    @Test
    fun `every profile tool is held for approval and rated HIGH`() {
        val evaluator = DefaultMcpRiskEvaluator()
        // profile_list changes nothing, but names every profile and its bound Spaces, so it is
        // held like get_workspace_context.
        for (name in listOf("profile_create", "profile_open", "profile_list")) {
            assertTrue(McpMutatingToolCatalog.isMutating(name, declaredReadOnly = name == "profile_list"), name)
            assertEquals(McpRiskLevel.HIGH, evaluator.evaluateRisk(name, emptyArgs).level, name)
        }
    }

    @Test
    fun `the provider declares the same read and write split`() {
        val readOnly = BossProfileMcpToolProvider.tools().associate { it.name to it.readOnly }
        assertEquals(mapOf("profile_list" to true, "profile_create" to false, "profile_open" to false), readOnly)
    }
}
