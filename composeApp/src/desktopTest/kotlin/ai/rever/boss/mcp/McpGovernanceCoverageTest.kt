package ai.rever.boss.mcp

import ai.rever.boss.mcp.sandbox.DefaultMcpRiskEvaluator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins which tools the governance path can actually act on.
 *
 * The gate, the policy engine and the ledger all sit inside
 * [McpToolRegistryCore.invoke], which begins with a registry lookup. BossTerm serves
 * the terminal tools directly, so they never reach it. Both governance tables name
 * those tools anyway, which is correct as classification and inert as coverage.
 *
 * Nothing in the suite said so before, and the fixtures actively obscured it:
 * `McpGovernedInvocationTest` registers a synthetic provider tool named
 * `run_command` and drives the gate with it, which proves the gate works and reads
 * as proof that shell execution is covered. These assertions are the counterweight.
 *
 * See BossConsole#495.
 */
class McpGovernanceCoverageTest {
    @Test
    fun `the shell tools both tables name cannot be enforced`() {
        // The four that appear in KNOWN_MUTATING_TOOLS and SHELL_TOOLS alike. Every
        // one is served by BossTerm or by terminal-tab's additionalTools, so a policy
        // decision about it is advisory.
        for (tool in listOf("run_command", "run_in_sidebar", "run_in_panel", "send_input")) {
            assertFalse(
                McpGovernanceCoverage.isEnforceable(tool),
                "$tool is recorded as enforceable, but nothing on its actual path consults the policy",
            )
        }
    }

    @Test
    fun `a registry-provided tool is enforceable`() {
        // k8s_exec is the one member of SHELL_TOOLS that comes from a plugin through
        // the registry, so it is the control: without it, "nothing is enforceable"
        // would pass the test above just as well.
        assertTrue(McpGovernanceCoverage.isEnforceable("k8s_exec"))
        assertTrue(McpGovernanceCoverage.isEnforceable("browser_run_js"))
        assertTrue(McpGovernanceCoverage.isEnforceable("secret_get"))
    }

    @Test
    fun `an unknown tool is assumed enforceable`() {
        // The registry is the only source this repository can enumerate, so anything
        // unrecognised is far more likely to be a plugin's tool than a BossTerm one.
        assertTrue(McpGovernanceCoverage.isEnforceable("some_new_plugin_tool"))
    }

    @Test
    fun `the client-side prefix is stripped before the verdict`() {
        // DefaultMcpRiskEvaluator normalises the same way. Two answers for one tool,
        // depending on whether the caller had already stripped the prefix, would be
        // worse than no answer.
        assertFalse(McpGovernanceCoverage.isEnforceable("mcp__boss__run_command"))
        assertTrue(McpGovernanceCoverage.isEnforceable("mcp__boss__k8s_exec"))
    }

    /**
     * The drift guard, and the reason `SHELL_TOOLS` was widened to internal.
     *
     * Adding a shell tool to the risk evaluator without deciding which side of the
     * registry it is served from is exactly how this gap appeared. This fails on the
     * next such addition rather than three releases later.
     */
    @Test
    fun `every shell tool has an explicit enforceability verdict`() {
        val verdicts =
            mapOf(
                "run_command" to false,
                "run_in_sidebar" to false,
                "run_in_panel" to false,
                "send_input" to false,
                "k8s_exec" to true,
                // Named by the evaluator but not found anywhere else in this
                // repository or in terminal-tab. Recorded as enforceable because an
                // unrecognised name resolves that way; if it is ever a real BossTerm
                // tool, it belongs in EXTERNALLY_SERVED_TOOLS.
                "terminal_exec" to true,
            )
        assertEquals(
            verdicts.keys,
            DefaultMcpRiskEvaluator.SHELL_TOOLS,
            "a shell tool was added or removed without recording whether the gate can receive it",
        )
        for ((tool, enforceable) in verdicts) {
            assertEquals(enforceable, McpGovernanceCoverage.isEnforceable(tool), tool)
        }
    }

    @Test
    fun `the mutating catalog knows which of its entries are inert`() {
        val inert =
            McpMutatingToolCatalog.KNOWN_MUTATING_TOOLS
                .filterNot { McpGovernanceCoverage.isEnforceable(it) }
                .toSet()
        assertEquals(
            setOf("run_command", "run_in_sidebar", "run_in_panel", "send_input"),
            inert,
            "the set of mutating tools the gate cannot receive has changed",
        )
    }
}
