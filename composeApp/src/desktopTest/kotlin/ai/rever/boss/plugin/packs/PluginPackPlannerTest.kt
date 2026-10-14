package ai.rever.boss.plugin.packs

import ai.rever.boss.mcp.McpPolicyAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Guards [PluginPackPlanner]: every plugin and rule row kind, from a snapshot alone. */
class PluginPackPlannerTest {
    private val id = "com.example.tool"

    private fun snapshot(
        installed: Map<String, InstalledPlugin> = emptyMap(),
        store: Map<String, StoreListing> = emptyMap(),
        toolRules: Map<String, McpPolicyAction> = emptyMap(),
        providerRules: Map<String, McpPolicyAction> = emptyMap(),
        policyReadable: Boolean = true,
    ) = PackSnapshot(installed, store, toolRules, providerRules, policyReadable)

    private fun pluginStep(
        plugin: PackPlugin,
        snapshot: PackSnapshot,
    ): PluginStep = PluginPackPlanner.plan(PluginPack("p", listOf(plugin), emptyList()), snapshot).plugins.single()

    private val published = StoreListing.Published(latest = "2.0.0", versions = setOf("1.0.0", "2.0.0"))

    @Test
    fun `an installed enabled plugin satisfies an unversioned row without asking the store`() {
        val installed = snapshot(installed = mapOf(id to InstalledPlugin("1.0.0", true)))

        val step = pluginStep(PackPlugin(id, null, false), installed)

        assertEquals(PluginStepKind.SATISFIED, step.kind)
        assertFalse(step.needsWork)
    }

    @Test
    fun `an installed but disabled plugin is enabled, not reinstalled`() {
        val disabled = snapshot(installed = mapOf(id to InstalledPlugin("1.0.0", false)))

        val step = pluginStep(PackPlugin(id, "1.0.0", false), disabled)

        assertEquals(PluginStepKind.ENABLE, step.kind)
        assertEquals(null, step.targetVersion)
    }

    @Test
    fun `a missing plugin installs the store's latest release when no version is named`() {
        val step = pluginStep(PackPlugin(id, null, false), snapshot(store = mapOf(id to published)))

        assertEquals(PluginStepKind.INSTALL, step.kind)
        assertEquals("2.0.0", step.targetVersion)
        assertTrue(step.targetIsLatest)
    }

    @Test
    fun `a pinned older release installs that release and is marked as not the latest`() {
        val step = pluginStep(PackPlugin(id, "1.0.0", false), snapshot(store = mapOf(id to published)))

        assertEquals(PluginStepKind.INSTALL, step.kind)
        assertEquals("1.0.0", step.targetVersion)
        assertFalse(step.targetIsLatest)
    }

    @Test
    fun `a different installed version is changed to the pinned one`() {
        val step =
            pluginStep(
                PackPlugin(id, "2.0.0", false),
                snapshot(installed = mapOf(id to InstalledPlugin("1.0.0", true)), store = mapOf(id to published)),
            )

        assertEquals(PluginStepKind.CHANGE_VERSION, step.kind)
        assertEquals("1.0.0", step.installedVersion)
        assertEquals("2.0.0", step.targetVersion)
    }

    @Test
    fun `a version the store does not publish is unavailable, naming the latest`() {
        val step = pluginStep(PackPlugin(id, "9.9.9", false), snapshot(store = mapOf(id to published)))

        assertEquals(PluginStepKind.UNAVAILABLE, step.kind)
        assertTrue("2.0.0" in step.detail, step.detail)
    }

    @Test
    fun `not published and could not ask are different rows`() {
        val absent = pluginStep(PackPlugin(id, null, false), snapshot(store = mapOf(id to StoreListing.NotPublished)))
        val unreachable = snapshot(store = mapOf(id to StoreListing.Unreachable("offline")))
        val offline = pluginStep(PackPlugin(id, null, false), unreachable)

        assertEquals(PluginStepKind.UNAVAILABLE, absent.kind)
        assertEquals(PluginStepKind.STORE_UNREACHABLE, offline.kind)
        assertEquals("offline", offline.detail)
    }

    @Test
    fun `only required blocked rows count against the pack`() {
        val plan =
            PluginPackPlanner.plan(
                PluginPack(
                    "p",
                    listOf(PackPlugin("a.required", null, false), PackPlugin("b.optional", null, true)),
                    emptyList(),
                ),
                snapshot(),
            )

        assertEquals(listOf("a.required"), plan.requiredBlocked.map { it.plugin.pluginId })
    }

    @Test
    fun `a rule is added only where no rule exists, and an operator rule in either direction is kept`() {
        val pack =
            PluginPack(
                "p",
                emptyList(),
                listOf(
                    PackRule(PackRuleScope.TOOL, "new_tool", McpPolicyAction.ALLOW),
                    PackRule(PackRuleScope.TOOL, "same_tool", McpPolicyAction.ALLOW),
                    PackRule(PackRuleScope.TOOL, "denied_tool", McpPolicyAction.ALLOW),
                    PackRule(PackRuleScope.PROVIDER, "trusted.provider", McpPolicyAction.DENY),
                ),
            )
        val plan =
            PluginPackPlanner.plan(
                pack,
                snapshot(
                    toolRules = mapOf("same_tool" to McpPolicyAction.ALLOW, "denied_tool" to McpPolicyAction.DENY),
                    providerRules = mapOf("trusted.provider" to McpPolicyAction.ALLOW),
                ),
            )

        assertEquals(
            listOf(RuleStepKind.ADD, RuleStepKind.ALREADY_SET, RuleStepKind.KEPT_EXISTING, RuleStepKind.KEPT_EXISTING),
            plan.rules.map { it.kind },
        )
        assertEquals(McpPolicyAction.ALLOW, plan.rules.last().existing)
    }

    @Test
    fun `tool and provider rules are looked up in their own maps`() {
        val rule = PackRule(PackRuleScope.PROVIDER, "same.name", McpPolicyAction.ASK)
        val pack = PluginPack("p", emptyList(), listOf(rule))

        val plan = PluginPackPlanner.plan(pack, snapshot(toolRules = mapOf("same.name" to McpPolicyAction.DENY)))

        assertEquals(RuleStepKind.ADD, plan.rules.single().kind)
    }

    @Test
    fun `an unreadable policy plans no rule writes`() {
        val rule = PackRule(PackRuleScope.TOOL, "run_tests", McpPolicyAction.ALLOW)
        val pack = PluginPack("p", emptyList(), listOf(rule))

        val plan = PluginPackPlanner.plan(pack, snapshot(policyReadable = false))

        assertEquals(RuleStepKind.POLICY_UNREADABLE, plan.rules.single().kind)
        assertFalse(plan.satisfied, "a rule that cannot be written is not in effect")
    }
}
