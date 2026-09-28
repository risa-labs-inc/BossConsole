package ai.rever.boss.plugin.packs

import ai.rever.boss.mcp.McpPolicyAction
import ai.rever.boss.mcp.McpPolicyEngine
import ai.rever.boss.mcp.ruleFor
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The production decision behind a pack's rule writes: the stamp [DesktopPluginPackEffects] takes at
 * snapshot, and what [DesktopPluginPackEffects.addRule] does with it against a real
 * [McpPolicyEngine]. The applier tests run against a fake that is handed its stamps, so they cannot
 * see this.
 *
 * With no window and no store the snapshot is the policy part alone, which is all a rule needs.
 */
class DesktopPluginPackEffectsRuleTest {
    private val dirs = mutableListOf<File>()

    /** Which provider contributes each tool right now. Mutable, since a pack's install changes it. */
    private val registered = mutableMapOf<String, String>()

    private fun engine(policyJson: String? = null): McpPolicyEngine {
        val dir =
            kotlin.io.path
                .createTempDirectory("pack-effects-rule-test")
                .toFile()
                .also { dirs += it }
        val file = File(dir, "mcp-tool-policy.json")
        policyJson?.let { file.writeText(it) }
        return McpPolicyEngine(policyFile = file)
    }

    private fun effects(policy: McpPolicyEngine) =
        DesktopPluginPackEffects(
            manager = { null },
            store = { null },
            policy = policy,
            providerOf = { registered[it] },
        )

    private val allowTool = PackRule(PackRuleScope.TOOL, "example_tool", McpPolicyAction.ALLOW)
    private val allowProvider = PackRule(PackRuleScope.PROVIDER, "com.example", McpPolicyAction.ALLOW)

    private fun stampOf(
        effects: DesktopPluginPackEffects,
        rule: PackRule,
    ): RuleStamp {
        val snapshot = runBlocking { effects.snapshot(PluginPack("team", emptyList(), listOf(rule))) }
        return snapshot.stamps.getValue(rule.subject)
    }

    @AfterTest
    fun cleanup() {
        dirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `a tool the pack installs is checked against its provider's DENY once it exists`() {
        val policy = engine()
        policy.setProviderPolicy("com.example", McpPolicyAction.DENY)
        val effects = effects(policy)

        // Snapshot: the pack has not installed the plugin yet, so no provider contributes the tool.
        val stamp = stampOf(effects, allowTool)
        assertNull(stamp.providerId)

        // The pack's install registers the tool before its rules are written.
        registered["example_tool"] = "com.example"

        assertEquals(RuleWrite.DENIED_BY_PROVIDER, effects.addRule(allowTool, stamp))
        assertNull(
            policy.config.value.ruleFor("example_tool", "com.example"),
            "a rule that would not take effect must not be written",
        )
    }

    @Test
    fun `another plugin's rule for a same-named tool is not this tool's existing rule`() {
        registered["example_tool"] = "com.example"
        val policy = engine()
        policy.setToolPolicy("example_tool", McpPolicyAction.DENY, providerId = "com.other")
        val effects = effects(policy)

        val snapshot = runBlocking { effects.snapshot(PluginPack("team", emptyList(), listOf(allowTool))) }

        assertNull(snapshot.toolRules["example_tool"])
        assertEquals(RuleWrite.ADDED, effects.addRule(allowTool, snapshot.stamps.getValue("example_tool")))
        assertEquals(McpPolicyAction.ALLOW, policy.policyFor("example_tool", "com.example"))
        assertEquals(McpPolicyAction.DENY, policy.policyFor("example_tool", "com.other"))
    }

    @Test
    fun `this plugin's own rule for the tool is reported as existing`() {
        registered["example_tool"] = "com.example"
        val policy = engine()
        policy.setToolPolicy("example_tool", McpPolicyAction.DENY, providerId = "com.example")
        val effects = effects(policy)

        val snapshot = runBlocking { effects.snapshot(PluginPack("team", emptyList(), listOf(allowTool))) }

        assertEquals(McpPolicyAction.DENY, snapshot.toolRules["example_tool"])
    }

    @Test
    fun `a provider appearing since the snapshot is not mistaken for a reset`() {
        val policy = engine()
        // The provider has history, so its counter is non-zero - a summed stamp would differ now.
        policy.revokeProviderPolicy("com.example")
        val effects = effects(policy)
        val stamp = stampOf(effects, allowTool)

        registered["example_tool"] = "com.example"

        assertEquals(RuleWrite.ADDED, effects.addRule(allowTool, stamp))
        assertEquals(McpPolicyAction.ALLOW, policy.config.value.ruleFor("example_tool", "com.example"))
    }

    @Test
    fun `a reset of the stamped provider after approval refuses the tool write`() {
        registered["example_tool"] = "com.example"
        val policy = engine()
        val effects = effects(policy)
        val stamp = stampOf(effects, allowTool)
        assertEquals("com.example", stamp.providerId)

        policy.revokeProviderPolicy("com.example")

        assertEquals(RuleWrite.REVOKED_SINCE_APPROVAL, effects.addRule(allowTool, stamp))
        assertNull(policy.config.value.ruleFor("example_tool", "com.example"))
    }

    @Test
    fun `a reset of the tool after approval refuses the tool write`() {
        val policy = engine()
        val effects = effects(policy)
        val stamp = stampOf(effects, allowTool)

        policy.revokePersistedPolicy("example_tool")

        assertEquals(RuleWrite.REVOKED_SINCE_APPROVAL, effects.addRule(allowTool, stamp))
    }

    @Test
    fun `a reset of a provider subject after approval refuses the provider write`() {
        val policy = engine()
        val effects = effects(policy)
        val stamp = stampOf(effects, allowProvider)

        policy.revokeProviderPolicy("com.example")

        assertEquals(RuleWrite.REVOKED_SINCE_APPROVAL, effects.addRule(allowProvider, stamp))
        assertNull(policy.config.value.providerRules["com.example"])
    }

    @Test
    fun `a DENY default is not reported as the provider's doing`() {
        registered["example_tool"] = "com.example"
        val policy = engine("""{"defaultMutatingAction":"DENY","defaultReadOnlyAction":"DENY"}""")
        val effects = effects(policy)
        val stamp = stampOf(effects, allowTool)

        assertEquals(RuleWrite.DENIED_BY_POLICY, effects.addRule(allowTool, stamp))
    }
}
