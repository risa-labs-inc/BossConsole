package ai.rever.boss.profile

import ai.rever.boss.mcp.McpMutatingToolCatalog
import ai.rever.boss.mcp.sandbox.DefaultMcpRiskEvaluator
import ai.rever.boss.mcp.sandbox.McpRiskLevel
import ai.rever.boss.plugin.api.McpToolArgs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The whole approval gate on tools that open a window or start a process: pinned name by name. */
class BossProfileMcpPolicyTest {
    private val emptyArgs = McpToolArgs(emptyMap(), "{}")

    @Test
    fun `profile_create and profile_open are mutating and HIGH, profile_list is a read`() {
        val evaluator = DefaultMcpRiskEvaluator()
        for (name in listOf("profile_create", "profile_open")) {
            assertTrue(McpMutatingToolCatalog.isMutating(name), name)
            assertEquals(McpRiskLevel.HIGH, evaluator.evaluateRisk(name, emptyArgs).level, name)
        }
        assertFalse(McpMutatingToolCatalog.isMutating("profile_list", declaredReadOnly = true))
        assertEquals(McpRiskLevel.LOW, evaluator.evaluateRisk("profile_list", emptyArgs).level)
    }

    @Test
    fun `the provider declares the same read and write split`() {
        val readOnly = BossProfileMcpToolProvider.tools().associate { it.name to it.readOnly }
        assertEquals(mapOf("profile_list" to true, "profile_create" to false, "profile_open" to false), readOnly)
    }
}
