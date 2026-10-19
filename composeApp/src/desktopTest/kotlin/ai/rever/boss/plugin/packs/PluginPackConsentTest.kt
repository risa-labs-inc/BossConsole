package ai.rever.boss.plugin.packs

import ai.rever.boss.mcp.McpPolicyAction
import ai.rever.boss.mcp.sandbox.DefaultMcpRiskEvaluator
import ai.rever.boss.mcp.sandbox.McpRiskLevel
import ai.rever.boss.plugin.api.McpToolArgs
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the operator is told, and what they are held to, when a pack is applied.
 *
 * A pack apply is one approved call that installs executable code and writes durable policy, and
 * these are the four ways the first version of it could promise the operator one thing and do
 * another: a risk level that skips the durable-grant review, a consent that names one plugin and
 * installs a closure, an authorization that outlives the reset meant to cancel it, and a rule
 * reported as applied while the invocation stays denied.
 */
class PluginPackConsentTest {
    private val published = StoreListing.Published(latest = "2.0.0", versions = setOf("1.0.0", "2.0.0"))

    private fun applyPack(
        effects: FakePackEffects,
        plugins: List<PackPlugin> = emptyList(),
        rules: List<PackRule> = emptyList(),
    ): PackApplyResult = runBlocking { PluginPackApplier(effects).apply(PluginPack("team", plugins, rules)) }

    private fun planOf(
        effects: FakePackEffects,
        plugins: List<PackPlugin> = emptyList(),
        rules: List<PackRule> = emptyList(),
    ): PackPlan {
        val pack = PluginPack("team", plugins, rules)
        return PluginPackPlanner.plan(pack, runBlocking { effects.snapshot(pack) })
    }

    // 1. Risk classification: a durable grant for pack_apply must go through Review-then-Confirm.

    @Test
    fun `pack_apply is high risk, not the unclassified default`() {
        val assessment = DefaultMcpRiskEvaluator().evaluateRisk("pack_apply", McpToolArgs(emptyMap()))

        assertEquals(
            McpRiskLevel.HIGH,
            assessment.level,
            "a tool that installs code and writes durable policy must not be gradable as LOW, " +
                "or one Always Allow grants it forever with no review step",
        )
        assertTrue(assessment.reason.contains("policy"), "the reason should say why: ${assessment.reason}")
    }

    @Test
    fun `the read-only pack tools stay low risk`() {
        val evaluator = DefaultMcpRiskEvaluator()

        assertEquals(McpRiskLevel.LOW, evaluator.evaluateRisk("pack_plan", McpToolArgs(emptyMap())).level)
        assertEquals(McpRiskLevel.LOW, evaluator.evaluateRisk("pack_status", McpToolArgs(emptyMap())).level)
    }

    // 2. Consent covers the closure, and the apply installs the approved one.

    @Test
    fun `a plan names every plugin the install would bring in`() {
        val effects = FakePackEffects(store = mutableMapOf("editor" to published))
        effects.closures["editor"] =
            InstallClosure(
                order = listOf("gateway", "fonts", "editor"),
                alsoInstalls = listOf("gateway", "fonts"),
                unresolved = emptySet(),
                cyclic = false,
                truncated = false,
            )

        val step = planOf(effects, plugins = listOf(PackPlugin("editor", null, false))).plugins.single()

        assertEquals(PluginStepKind.INSTALL, step.kind)
        assertEquals(
            listOf("gateway", "fonts"),
            step.closure?.alsoInstalls,
            "approving one plugin id must not hide the dependencies that arrive with it",
        )
    }

    @Test
    fun `an incomplete closure is reported as incomplete rather than as exact`() {
        val closure =
            InstallClosure(
                order = listOf("gateway", "editor"),
                alsoInstalls = listOf("gateway"),
                unresolved = setOf("mystery"),
                cyclic = false,
                truncated = true,
            )

        assertTrue(closure.partial, "unresolved or truncated means the shown closure is not the whole story")
    }

    @Test
    fun `apply installs the approved closure and never re-resolves it`() {
        val effects = FakePackEffects(store = mutableMapOf("editor" to published))
        effects.closures["editor"] =
            InstallClosure(
                order = listOf("gateway", "editor"),
                alsoInstalls = listOf("gateway"),
                unresolved = emptySet(),
                cyclic = false,
                truncated = false,
            )

        val result = applyPack(effects, plugins = listOf(PackPlugin("editor", null, false)))

        assertEquals(
            listOf("install editor 2.0.0 latest=true order=gateway+editor"),
            effects.calls,
            "the installer must be handed the order the plan resolved, not asked to walk the store again",
        )
        assertTrue(effects.installed.containsKey("gateway"), "the approved dependency should have arrived")
        val row = result.plugins.single()
        assertEquals(PluginResultKind.DONE, row.kind)
        assertTrue(
            row.message.contains("gateway"),
            "the result must say which dependencies arrived, not just that one plugin installed: ${row.message}",
        )
    }

    // 3. A reset between approval and the detached write refuses the write.

    @Test
    fun `a rule is refused when the operator reset its subject after approving the pack`() {
        val effects = FakePackEffects()
        effects.revokedAfterSnapshot += "codebase_write"

        val result =
            applyPack(
                effects,
                rules = listOf(PackRule(PackRuleScope.TOOL, "codebase_write", McpPolicyAction.ALLOW)),
            )

        assertEquals(
            RuleResultKind.REVOKED_SINCE_APPROVAL,
            result.rules.single().kind,
            "a reset after approval must cancel the queued grant, not be overwritten by it",
        )
        assertTrue(
            "codebase_write" !in effects.toolRules,
            "and nothing may be written for a subject the operator just reset",
        )
    }

    @Test
    fun `an unreset subject still writes normally`() {
        val effects = FakePackEffects()

        val result =
            applyPack(
                effects,
                rules = listOf(PackRule(PackRuleScope.TOOL, "codebase_read", McpPolicyAction.ALLOW)),
            )

        assertEquals(RuleResultKind.ADDED, result.rules.single().kind)
        assertEquals(McpPolicyAction.ALLOW, effects.toolRules["codebase_read"])
    }

    // 4. A provider DENY is not reported as an applied rule.

    @Test
    fun `a tool rule under a denied provider is planned as ineffective, not as added`() {
        val effects = FakePackEffects(providerRules = mutableMapOf("com.example" to McpPolicyAction.DENY))
        effects.stamps["example_tool"] = RuleStamp(revocation = 0L, providerId = "com.example")

        val step =
            planOf(
                effects,
                rules = listOf(PackRule(PackRuleScope.TOOL, "example_tool", McpPolicyAction.ALLOW)),
            ).rules.single()

        assertEquals(
            RuleStepKind.INEFFECTIVE_PROVIDER_DENY,
            step.kind,
            "a rule that cannot take effect must not be planned as one that would",
        )
    }

    @Test
    fun `applying that rule reports the provider denial rather than success`() {
        val effects = FakePackEffects(providerRules = mutableMapOf("com.example" to McpPolicyAction.DENY))
        effects.stamps["example_tool"] = RuleStamp(revocation = 0L, providerId = "com.example")

        val result =
            applyPack(
                effects,
                rules = listOf(PackRule(PackRuleScope.TOOL, "example_tool", McpPolicyAction.ALLOW)),
            )

        assertEquals(RuleResultKind.DENIED_BY_PROVIDER, result.rules.single().kind)
        assertTrue(
            "example_tool" !in effects.toolRules,
            "nothing should be written for a rule the provider's DENY makes inert",
        )
    }

    @Test
    fun `a tool whose provider is not denied is unaffected`() {
        val effects = FakePackEffects(providerRules = mutableMapOf("com.example" to McpPolicyAction.ASK))
        effects.stamps["example_tool"] = RuleStamp(revocation = 0L, providerId = "com.example")

        val result =
            applyPack(
                effects,
                rules = listOf(PackRule(PackRuleScope.TOOL, "example_tool", McpPolicyAction.ALLOW)),
            )

        assertEquals(RuleResultKind.ADDED, result.rules.single().kind)
    }
}
