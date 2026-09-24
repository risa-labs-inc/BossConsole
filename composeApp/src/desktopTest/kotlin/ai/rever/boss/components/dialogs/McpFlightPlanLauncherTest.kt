package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpFlightCheckpointState
import ai.rever.boss.mcp.McpFlightOutcome
import ai.rever.boss.mcp.McpPolicyAction
import ai.rever.boss.mcp.McpToolRegistryCore
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drives the launcher's real derivation — [mcpFlightPlanViews], the same function the
 * composable calls — against a real [McpToolRegistryCore], so the disabled path is
 * exercised through the registry's own permission and kill-switch state instead of
 * hand-picked input values (review on #1380).
 */
class McpFlightPlanLauncherTest {
    private fun provider(
        id: String,
        vararg defs: McpToolDefinition,
    ) = object : McpToolProvider {
        override val providerId = id

        override fun tools() = defs.toList()
    }

    private fun echoTool(
        name: String,
        requiresAdmin: Boolean = false,
    ) = McpToolDefinition(
        name = name,
        description = "test tool $name",
        handler = McpToolHandler { McpToolResult("ok:$name") },
        readOnly = true,
    ).apply { this.requiresAdmin = requiresAdmin }

    /** The launcher's derivation over a real core's live flows — the composable's own call. */
    private fun plansFor(core: McpToolRegistryCore) =
        mcpFlightPlanViews(
            allTools = core.allTools.value,
            disabledToolNames = core.disabledToolNames.value,
            permittedToolNames = core.permittedToolNames.value,
            policyFault = core.policyEngine.fault.value,
            resolvePolicy = { tool ->
                core.policyEngine.policyFor(
                    tool.definition.name,
                    tool.providerId,
                    tool.definition.readOnly,
                )
            },
        )

    @Test
    fun `a disabled tool the operator may run is blamed on the kill-switch, not on permissions`() {
        val core = McpToolRegistryCore(disabledFile = null)
        core.registerProvider(provider("p1", echoTool("git_status")))
        core.setToolEnabled("git_status", enabled = false)

        // The exposed set is empty — the registry is withholding the tool...
        assertTrue(core.tools.value.isEmpty())
        // ...while the permission set still contains it, which is what the launcher reads.
        assertTrue(core.permittedToolNames.value.contains("p1/git_status"))

        val plan = plansFor(core).single()
        assertFalse(plan.plan.input.isEnabled)
        assertTrue(plan.plan.input.isPermitted)
        assertEquals(McpFlightOutcome.WITHHELD, plan.plan.outcome)
        assertEquals(
            McpFlightCheckpointState.BLOCKED,
            plan.plan.checkpoints
                .first()
                .state,
        )
    }

    @Test
    fun `permission failures come from registry RBAC state and clear with admin access`() {
        val core = McpToolRegistryCore(disabledFile = null)
        core.registerProvider(provider("p1", echoTool("admin_tool", requiresAdmin = true)))

        val denied = plansFor(core).single()
        assertTrue(denied.plan.input.isEnabled)
        assertFalse(denied.plan.input.isPermitted)
        assertEquals(McpFlightOutcome.WITHHELD, denied.plan.outcome)

        core.updateAccess(isAdmin = true, permissions = emptySet())
        val granted = plansFor(core).single()
        assertTrue(granted.plan.input.isPermitted)
    }

    @Test
    fun `re-derivation after a policy change reflects the new rule`() {
        val core = McpToolRegistryCore(disabledFile = null)
        core.registerProvider(provider("p1", echoTool("git_status")))

        assertEquals(McpFlightOutcome.READY_TO_RUN, plansFor(core).single().plan.outcome)

        core.policyEngine.setToolPolicy("git_status", McpPolicyAction.DENY)
        val after = plansFor(core).single()
        assertEquals(McpFlightOutcome.WITHHELD, after.plan.outcome)
        assertTrue(
            after.plan.checkpoints.any {
                it.label == "Policy" && it.state == McpFlightCheckpointState.BLOCKED
            },
        )
    }
}
