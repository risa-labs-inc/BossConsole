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
                // Named by the evaluator but found nowhere else in this repository and in
                // no terminal-tab definition, which by the elimination rule is evidence it
                // is served outside the registry rather than evidence it is inside. Now
                // recorded that way: if the name is dead the claim costs nothing, and if it
                // is live it is BossTerm's. Leaving it on the optimistic default was the one
                // verdict here that could overstate coverage.
                "terminal_exec" to false,
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

    /**
     * The same guard as above, on the table where the gap was actually found.
     *
     * This used to pin only the derived intersection, which cannot catch the failure it
     * exists for: add a new externally-served tool to [McpMutatingToolCatalog.KNOWN_MUTATING_TOOLS]
     * and forget [McpGovernanceCoverage.EXTERNALLY_SERVED_TOOLS], and `isEnforceable` answers
     * true, the inert set is unchanged, and the table again claims coverage it does not have.
     * That is the originating failure mode reproduced exactly, in the table that has four inert
     * entries today. A verdict per name forces the question instead.
     */
    @Test
    fun `every known mutating tool has an explicit enforceability verdict`() {
        val verdicts =
            mapOf(
                "k8s_delete" to true,
                "k8s_exec" to true,
                "k8s_apply" to true,
                "k8s_scale" to true,
                "k8s_rollout_restart" to true,
                "docker_rm" to true,
                "docker_stop" to true,
                "docker_compose_down" to true,
                "docker_compose_up" to true,
                "docker_build" to true,
                "docker_start" to true,
                "docker_restart" to true,
                "helm_install" to true,
                "helm_upgrade" to true,
                "helm_rollback" to true,
                "helm_uninstall" to true,
                "secret_get" to true,
                "codebase_write" to true,
                // The four the gate cannot receive. Shell execution, which is the whole
                // reason this file exists.
                "run_command" to false,
                "run_in_sidebar" to false,
                "run_in_panel" to false,
                "send_input" to false,
                "project_replace" to true,
            )
        assertEquals(
            verdicts.keys,
            McpMutatingToolCatalog.KNOWN_MUTATING_TOOLS,
            "a mutating tool was added or removed without recording whether the gate can receive it",
        )
        for ((tool, enforceable) in verdicts) {
            assertEquals(enforceable, McpGovernanceCoverage.isEnforceable(tool), tool)
        }
    }

    @Test
    fun `both agent-facing prefixes are stripped before the verdict`() {
        // mcp__bossterm__ is the standalone BossTerm app's namespace per AGENTS.md, which is
        // the server these tools come from, so it is the likeliest prefix to meet them under.
        assertFalse(McpGovernanceCoverage.isEnforceable("mcp__bossterm__run_command"))
        assertTrue(McpGovernanceCoverage.isEnforceable("mcp__bossterm__k8s_exec"))
    }
}
