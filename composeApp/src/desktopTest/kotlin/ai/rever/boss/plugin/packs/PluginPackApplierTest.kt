package ai.rever.boss.plugin.packs

import ai.rever.boss.mcp.McpPolicyAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Guards [PluginPackApplier]: which host effect each plan row reaches, and how row outcomes add up
 * to the pack's status.
 */
class PluginPackApplierTest {
    private val published = StoreListing.Published(latest = "2.0.0", versions = setOf("1.0.0", "2.0.0"))

    private fun apply(
        effects: FakePackEffects,
        vararg plugins: PackPlugin,
        rules: List<PackRule> = emptyList(),
    ): PackApplyResult = applyAll(effects, plugins.toList(), rules)

    private fun applyAll(
        effects: FakePackEffects,
        plugins: List<PackPlugin>,
        rules: List<PackRule> = emptyList(),
    ): PackApplyResult = runBlocking { PluginPackApplier(effects).apply(PluginPack("team", plugins, rules)) }

    private fun plugin(id: String) = PackPlugin(id, null, false)

    @Test
    fun `each row kind reaches exactly its own host effect`() {
        val effects =
            FakePackEffects(
                installed =
                    mutableMapOf(
                        "disabled" to InstalledPlugin("1.0.0", enabled = false),
                        "old" to InstalledPlugin("1.0.0", true),
                    ),
                store = mutableMapOf("fresh" to published, "pinned" to published, "old" to published),
            )

        val result =
            apply(
                effects,
                PackPlugin("fresh", null, false),
                PackPlugin("pinned", "1.0.0", false),
                PackPlugin("old", "2.0.0", false),
                PackPlugin("disabled", null, false),
                rules = listOf(PackRule(PackRuleScope.PROVIDER, "com.example", McpPolicyAction.ASK)),
            )

        assertEquals(
            listOf(
                "install fresh 2.0.0 latest=true",
                "install pinned 1.0.0 latest=false",
                "changeVersion old 2.0.0",
                "enable disabled",
                "addRule PROVIDER com.example ASK",
            ),
            effects.calls,
        )
        assertEquals(PackApplyStatus.APPLIED, result.status)
    }

    @Test
    fun `a satisfied pack touches nothing and says so`() {
        val effects =
            FakePackEffects(
                installed = mutableMapOf("a" to InstalledPlugin("1.0.0", true)),
                toolRules = mutableMapOf("run_tests" to McpPolicyAction.DENY),
            )

        val allow = PackRule(PackRuleScope.TOOL, "run_tests", McpPolicyAction.ALLOW)

        val result = apply(effects, plugin("a"), rules = listOf(allow))

        assertEquals(PackApplyStatus.ALREADY_SATISFIED, result.status)
        assertEquals(emptyList(), effects.calls, "an operator DENY is kept without even attempting a write")
        assertEquals(RuleResultKind.KEPT_EXISTING, result.rules.single().kind)
    }

    @Test
    fun `applying twice does on the second pass only what the first left undone`() {
        val effects = FakePackEffects(store = mutableMapOf("a" to published, "b" to published))
        effects.failures["b"] = IllegalStateException("download failed")
        val pack = listOf(plugin("a"), plugin("b"))

        val first = applyAll(effects, pack)
        effects.failures.clear()
        effects.calls.clear()
        val second = applyAll(effects, pack)

        assertEquals(PackApplyStatus.PARTIAL, first.status)
        assertEquals(listOf("install b 2.0.0 latest=true"), effects.calls)
        assertEquals(PackApplyStatus.APPLIED, second.status)
    }

    @Test
    fun `a thrown installer failure is one failed row and later rows still run`() {
        val effects = FakePackEffects(store = mutableMapOf("a" to published, "b" to published))
        effects.failures["a"] = IllegalStateException("could not verify the signature")

        val result = apply(effects, PackPlugin("a", null, false), PackPlugin("b", null, false))

        assertEquals(listOf(PluginResultKind.FAILED, PluginResultKind.DONE), result.plugins.map { it.kind })
        assertEquals("could not verify the signature", result.plugins.first().message)
        assertEquals(PackApplyStatus.PARTIAL, result.status)
    }

    @Test
    fun `nothing landing is FAILED, not PARTIAL`() {
        val effects = FakePackEffects(store = mutableMapOf("a" to StoreListing.Unreachable("offline")))

        val result = apply(effects, PackPlugin("a", null, false))

        assertEquals(PluginResultKind.BLOCKED, result.plugins.single().kind)
        assertEquals(PackApplyStatus.FAILED, result.status)
    }

    @Test
    fun `an optional plugin that cannot be had does not make the pack partial`() {
        val effects = FakePackEffects(store = mutableMapOf("a" to published))

        val result = apply(effects, PackPlugin("a", null, false), PackPlugin("extra", null, true))

        assertEquals(PluginResultKind.BLOCKED, result.plugins.last().kind)
        assertEquals(PackApplyStatus.APPLIED, result.status)
    }

    @Test
    fun `a rule refused because one appeared since planning is kept, not a failure`() {
        val effects = FakePackEffects()
        effects.ruleWrites["run_tests"] = RuleWrite.KEPT_EXISTING
        val rule = PackRule(PackRuleScope.TOOL, "run_tests", McpPolicyAction.ALLOW)

        val result = apply(effects, rules = listOf(rule))

        assertEquals(RuleResultKind.KEPT_EXISTING, result.rules.single().kind)
        assertEquals(PackApplyStatus.APPLIED, result.status)
    }

    @Test
    fun `a rule that could not be saved, or an unreadable policy, is a miss`() {
        val notSaved = FakePackEffects(store = mutableMapOf("a" to published))
        notSaved.ruleWrites["run_tests"] = RuleWrite.NOT_SAVED
        val rule = PackRule(PackRuleScope.TOOL, "run_tests", McpPolicyAction.ALLOW)

        assertEquals(PackApplyStatus.PARTIAL, apply(notSaved, plugin("a"), rules = listOf(rule)).status)

        val unreadable = FakePackEffects(policyReadable = false)
        val result = apply(unreadable, rules = listOf(rule))
        assertEquals(PackApplyStatus.FAILED, result.status)
        assertTrue(unreadable.calls.isEmpty(), "nothing is written while the policy is failing closed")
    }

    @Test
    fun `the plan is recomputed from a fresh snapshot when the apply starts`() {
        val effects = FakePackEffects(store = mutableMapOf("a" to published))
        // Installed by someone else between a caller's pack_plan and this apply.
        effects.beforeSnapshot = { effects.installed["a"] = InstalledPlugin("2.0.0", true) }

        val result = apply(effects, PackPlugin("a", null, false))

        assertEquals(PackApplyStatus.ALREADY_SATISFIED, result.status)
        assertEquals(emptyList(), effects.calls)
        assertEquals(1, effects.snapshots)
    }

    @Test
    fun `cancellation stops the apply instead of becoming a failed row`() {
        val effects = FakePackEffects(store = mutableMapOf("a" to published, "b" to published))
        effects.failures["a"] = CancellationException("window closed")

        assertFailsWith<CancellationException> { apply(effects, plugin("a"), plugin("b")) }
        assertEquals(listOf("install a 2.0.0 latest=true"), effects.calls)
    }

    @Test
    fun `progress counts every row and ends complete`() {
        val effects = FakePackEffects(store = mutableMapOf("a" to published))
        val seen = mutableListOf<Pair<Int, Int>>()

        runBlocking {
            PluginPackApplier(effects).apply(
                PluginPack("team", listOf(plugin("a")), listOf(PackRule(PackRuleScope.TOOL, "t", McpPolicyAction.ASK))),
            ) { done, total, _ -> seen += done to total }
        }

        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), seen)
    }
}
