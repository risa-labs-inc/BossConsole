package ai.rever.boss.components.bars.horizontal

import ai.rever.boss.mcp.McpPolicyAction
import ai.rever.boss.mcp.McpToolPolicyConfig
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.plugin.api.RegisteredMcpTool
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [mcpProactivePolicyCandidates] is the one thing standing between
 * [ai.rever.boss.components.dialogs.McpPolicyManagerDialog]'s proactive Allow/Deny buttons and
 * either overwriting an existing rule or offering a rule for a tool the kill switch has already
 * withheld - both call sites this filter alone protects (review on #636).
 *
 * [McpToolIdentity.expectedRevocation] is the write-time guard the same review asked for: a
 * candidate stamped with a stale generation refuses at [ai.rever.boss.mcp.McpPolicyEngine]'s own
 * lock rather than silently overwriting a DENY or reset that landed after this candidate was
 * offered. `NO_REVOCATION` is a stand-in for tests that don't care about the value itself.
 */
class McpProactivePolicyCandidatesTest {
    @Test
    fun `a tool with an existing rule is excluded`() {
        val tools = listOf(tool("run_command"), tool("k8s_delete"))
        val policy = McpToolPolicyConfig(rules = mapOf("run_command" to McpPolicyAction.ALLOW))

        val result = mcpProactivePolicyCandidates(tools, policy, disabledToolNames = emptySet(), NO_REVOCATION)

        assertEquals(listOf("k8s_delete"), result.map { it.toolName })
    }

    @Test
    fun `a kill-switch-disabled tool is excluded even with no rule`() {
        val tools = listOf(tool("run_command"), tool("k8s_delete"))

        val result =
            mcpProactivePolicyCandidates(
                tools,
                policy = McpToolPolicyConfig(),
                disabledToolNames = setOf("k8s_delete"),
                NO_REVOCATION,
            )

        assertEquals(listOf("run_command"), result.map { it.toolName })
    }

    @Test
    fun `results are sorted by tool name`() {
        val tools = listOf(tool("zzz_tool"), tool("aaa_tool"), tool("mmm_tool"))

        val result =
            mcpProactivePolicyCandidates(
                tools,
                policy = McpToolPolicyConfig(),
                disabledToolNames = emptySet(),
                NO_REVOCATION,
            )

        assertEquals(listOf("aaa_tool", "mmm_tool", "zzz_tool"), result.map { it.toolName })
    }

    @Test
    fun `a tool that is both ruled and disabled is still excluded exactly once`() {
        val tools = listOf(tool("run_command"))
        val policy = McpToolPolicyConfig(rules = mapOf("run_command" to McpPolicyAction.DENY))

        val result =
            mcpProactivePolicyCandidates(tools, policy, disabledToolNames = setOf("run_command"), NO_REVOCATION)

        assertEquals(emptyList(), result)
    }

    @Test
    fun `a same-named tool from a different provider is still offered as a candidate`() {
        // "run_command" has a rule, but it was decided for "terminal-tab" specifically. A second
        // provider shipping its own "run_command" has no rule of its own yet, so a name-only
        // exclusion would wrongly hide it from this list even though it is exactly the tool this
        // candidate list exists to offer a rule for.
        val tools =
            listOf(tool("run_command", providerId = "terminal-tab"), tool("run_command", providerId = "flow-tab"))
        val policy =
            McpToolPolicyConfig(
                providerToolRules = mapOf("terminal-tab" to mapOf("run_command" to McpPolicyAction.ALLOW)),
            )

        val result =
            mcpProactivePolicyCandidates(tools, policy, disabledToolNames = emptySet(), NO_REVOCATION)

        assertEquals(listOf("flow-tab"), result.map { it.providerId })
    }

    @Test
    fun `an unscoped rule excludes every provider's same-named tool`() {
        // The rule predates per-provider rules (or was hand-edited) and still answers for every
        // provider, so both registrations stay excluded.
        val tools =
            listOf(tool("run_command", providerId = "terminal-tab"), tool("run_command", providerId = "flow-tab"))
        val policy = McpToolPolicyConfig(rules = mapOf("run_command" to McpPolicyAction.ALLOW))

        val result =
            mcpProactivePolicyCandidates(tools, policy, disabledToolNames = emptySet(), NO_REVOCATION)

        assertEquals(emptyList(), result)
    }

    @Test
    fun `every tool available offers its provider id alongside the name`() {
        val tools = listOf(tool("run_command", providerId = "terminal-tab"))

        val result =
            mcpProactivePolicyCandidates(
                tools,
                policy = McpToolPolicyConfig(),
                disabledToolNames = emptySet(),
                NO_REVOCATION,
            )

        assertEquals("terminal-tab", result.single().providerId)
    }

    @Test
    fun `each candidate is stamped with its own tool-and-provider revocation generation`() {
        val tools = listOf(tool("run_command", providerId = "terminal-tab"), tool("k8s_delete", providerId = "k8s-tab"))
        val versions: Map<Pair<String, String?>, Long> =
            mapOf(("run_command" to "terminal-tab") to 3L, ("k8s_delete" to "k8s-tab") to 7L)

        val result =
            mcpProactivePolicyCandidates(
                tools,
                policy = McpToolPolicyConfig(),
                disabledToolNames = emptySet(),
                revocationVersion = { toolName, providerId -> versions.getValue(toolName to providerId) },
            )

        assertEquals(3L, result.single { it.toolName == "run_command" }.expectedRevocation)
        assertEquals(7L, result.single { it.toolName == "k8s_delete" }.expectedRevocation)
    }

    private fun tool(
        name: String,
        providerId: String = "test-provider",
    ): RegisteredMcpTool =
        RegisteredMcpTool(
            providerId,
            McpToolDefinition(
                name = name,
                description = "test",
                handler = McpToolHandler { McpToolResult("ok") },
            ),
        )

    private companion object {
        val NO_REVOCATION: (String, String?) -> Long = { _, _ -> 0L }
    }
}
