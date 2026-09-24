package ai.rever.boss.mcp

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A persisted tool rule must speak for the provider that earned it - the persisted-rules half
 * of the identity argument session trust already makes ([McpSessionTrust]: "a second provider
 * shipping a same-named tool is a *different* tool"). Before rule scopes were recorded,
 * `rules` was name-keyed only, so a plugin that shipped a tool named like one another provider
 * had a standing ALLOW for inherited that ALLOW without ever being prompted - and the
 * registry's first-wins dedup only stops same-named tools from coexisting, not from following
 * each other, so the squat works even with one tool per name registered at a time.
 *
 * These tests pin the scope on every write path ([McpPolicyEngine.setToolPolicy],
 * [McpPolicyEngine.setToolPolicyIfAbsent], [McpPolicyEngine.setSectionPolicies]) and on every
 * read ([McpPolicyEngine.policyFor], reload included), keep the add-only and exact-identity
 * contracts intact, and pin the fail-open-to-legacy semantics for rules written before scopes
 * existed. Scenarios use the mutating name `run_command` on purpose: its no-rule default is
 * ASK, so an unearned ALLOW surfaces as a wrong ALLOW.
 */
class McpPolicyEngineProviderScopedRuleTest {
    private val tempDirs = mutableListOf<File>()

    private fun createTempPolicyFile(): File {
        val dir = createTempDirectory("mcp-policy-scoped-test").toFile()
        tempDirs.add(dir)
        return File(dir, "mcp-tool-policy.json")
    }

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    @Test
    fun `a persisted ALLOW earned for one provider does not answer another provider's same-named tool`() {
        val file = createTempPolicyFile()
        val engine = McpPolicyEngine(policyFile = file)
        engine.setToolPolicy("run_command", McpPolicyAction.ALLOW, providerId = "terminal-tab")

        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command", "terminal-tab"))
        // The mutating default, not the sibling provider's standing ALLOW: a same-named tool is
        // a different tool and must earn its own decision.
        assertEquals(McpPolicyAction.ASK, engine.policyFor("run_command", "evil-tab"))
        // A caller with no provider in hand reads the operator's standing decision for the
        // name - it cannot be the provider a scoped rule speaks for, so that read is not a
        // dispatch and the scoped rule answers it.
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command"))

        val reloaded = McpPolicyEngine(policyFile = file)
        assertEquals(McpPolicyAction.ALLOW, reloaded.policyFor("run_command", "terminal-tab"))
        assertEquals(McpPolicyAction.ASK, reloaded.policyFor("run_command", "evil-tab"))
    }

    @Test
    fun `a queued approval for one provider cannot erase another provider's persisted DENY`() {
        val file = createTempPolicyFile()
        val engine = McpPolicyEngine(policyFile = file)
        engine.setToolPolicy("git_status", McpPolicyAction.DENY, providerId = "plugin-a")

        // The reactive approval path (validateApproval) writes with preserveDeny = true and
        // the approving provider's id. git_status's no-rule default for a second provider is
        // the read-only default ALLOW, so a write that erased the scoped DENY would leave
        // plugin-a running unattended on a tool the operator explicitly denied.
        val written =
            engine.setToolPolicy(
                "git_status",
                McpPolicyAction.ALLOW,
                preserveDeny = true,
                providerId = "plugin-b",
            )

        assertEquals(false, written)
        // The earlier decision is still in force, for the provider that earned it and on disk.
        assertEquals(McpPolicyAction.DENY, engine.policyFor("git_status", "plugin-a"))
        val reloaded = McpPolicyEngine(policyFile = file)
        assertEquals(McpPolicyAction.DENY, reloaded.policyFor("git_status", "plugin-a"))
    }

    @Test
    fun `a persisted DENY earned for one provider does not block another provider's same-named tool either`() {
        val file = createTempPolicyFile()
        val engine = McpPolicyEngine(policyFile = file)
        engine.setToolPolicy("run_command", McpPolicyAction.DENY, providerId = "terminal-tab")

        assertEquals(McpPolicyAction.DENY, engine.policyFor("run_command", "terminal-tab"))
        // The identity argument cuts both ways: terminal-tab's DENY is not evil-tab's DENY, or
        // the squat would work in reverse - blocking a tool from even being considered.
        assertEquals(McpPolicyAction.ASK, engine.policyFor("run_command", "evil-tab"))
        // The operator's own read of the file still sees the standing DENY for the name.
        assertEquals(McpPolicyAction.DENY, engine.policyFor("run_command"))
    }

    @Test
    fun `a rule written without a provider keeps answering every provider`() {
        val file = createTempPolicyFile()
        val engine = McpPolicyEngine(policyFile = file)
        engine.setToolPolicy("run_command", McpPolicyAction.ALLOW)

        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command", "terminal-tab"))
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command", "evil-tab"))
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command"))
    }

    @Test
    fun `a legacy policy file without rule scopes keeps its rules answering every provider`() {
        val file = createTempPolicyFile()
        // Exactly what an operator's pre-scope policy file looks like: rules, no ruleProviders.
        file.writeText("""{"rules":{"run_command":"ALLOW"}}""")

        val engine = McpPolicyEngine(policyFile = file)
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command", "terminal-tab"))
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command", "evil-tab"))
        assertEquals(McpPolicyAction.ASK, engine.policyFor("k8s_delete", "evil-tab"))
    }

    @Test
    fun `revoking a persisted rule drops its recorded provider with it`() {
        val file = createTempPolicyFile()
        val engine = McpPolicyEngine(policyFile = file)
        engine.setToolPolicy("run_command", McpPolicyAction.ALLOW, providerId = "terminal-tab")

        assertTrue(engine.revokePersistedPolicy("run_command"))
        assertNull(engine.config.value.rules["run_command"])
        assertNull(engine.config.value.ruleProviders["run_command"])

        // The next decision re-records who earned it instead of inheriting a stale scope.
        engine.setToolPolicy("run_command", McpPolicyAction.ALLOW)
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command", "evil-tab"))
    }

    @Test
    fun `an add-only-if-absent write scopes the rule to the provider it was offered for`() {
        val file = createTempPolicyFile()
        val engine = McpPolicyEngine(policyFile = file)

        assertEquals(
            McpProactivePolicyOutcome.Saved,
            engine.setToolPolicyIfAbsent(
                "run_command",
                McpPolicyAction.ALLOW,
                engine.revocationVersion("run_command", "terminal-tab"),
                "terminal-tab",
            ),
        )
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command", "terminal-tab"))
        assertEquals(McpPolicyAction.ASK, engine.policyFor("run_command", "evil-tab"))

        // Add-only still holds for the NAME - one rule per name exists at a time - so a second
        // provider's candidate is refused rather than shadowing the first provider's rule.
        assertEquals(
            McpProactivePolicyOutcome.Refused,
            engine.setToolPolicyIfAbsent(
                "run_command",
                McpPolicyAction.DENY,
                engine.revocationVersion("run_command", "evil-tab"),
                "evil-tab",
            ),
        )
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command", "terminal-tab"))
    }

    @Test
    fun `a section write scopes each rule to the provider whose section carried it`() {
        val file = createTempPolicyFile()
        val engine = McpPolicyEngine(policyFile = file)
        val change =
            McpSectionPolicyChange(
                toolName = "run_command",
                providerId = "terminal-tab",
                expectedRevocation = engine.revocationVersion("run_command", "terminal-tab"),
                expectedRule = null,
                action = McpPolicyAction.ALLOW,
            )

        assertEquals(McpProactivePolicyOutcome.Saved, engine.setSectionPolicies(listOf(change)))

        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command", "terminal-tab"))
        assertEquals(McpPolicyAction.ASK, engine.policyFor("run_command", "evil-tab"))
    }

    @Test
    fun `exact identity only - a rule never answers a name that merely extends the ruled name`() {
        val file = createTempPolicyFile()
        // Innocent names default ALLOW, which would mask a prefix or substring match; an
        // ask-by-default config makes any match that fails to stay exact show up as an
        // unearned ALLOW.
        file.writeText("""{"defaultMutatingAction":"ASK","defaultReadOnlyAction":"ASK"}""")
        val engine = McpPolicyEngine(policyFile = file)
        engine.setToolPolicy("allowed.tool", McpPolicyAction.ALLOW)

        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("allowed.tool"))
        assertEquals(McpPolicyAction.ASK, engine.policyFor("allowed.tool.evil"))
        assertEquals(McpPolicyAction.ASK, engine.policyFor("allowed"))
        assertEquals(McpPolicyAction.ASK, engine.policyFor("allowed_tool"))
    }
}
