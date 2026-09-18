package ai.rever.boss.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Session trust is a decision about ONE provider's tool. These tests pin the scoping of
 * [McpPolicyEngine.trustForSession] against tool-name squats: a second provider shipping a
 * same-named tool is a different tool and must keep prompting, no matter which of its
 * siblings the operator trusted.
 */
class McpSessionTrustScopingTest {
    @Test
    fun `a same-named tool from an untrusted provider does not inherit session trust`() {
        val engine = McpPolicyEngine(policyFile = null)

        // run_command is in the host mutating catalog, so its no-trust default is ASK -
        // an innocent name would default ALLOW and hide the leak this test exists to pin.
        engine.trustForSession("run_command", "terminal-tab")

        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command", "terminal-tab"))
        assertEquals(McpPolicyAction.ASK, engine.policyFor("run_command", "flow-tab"))
        assertEquals(McpPolicyAction.ASK, engine.policyFor("run_command"))
    }

    @Test
    fun `confirmInvocation grants trust only to the provider that was approved`() {
        val engine = McpPolicyEngine(policyFile = null)
        val revocation = engine.revocationVersion("run_command", "terminal-tab")

        assertTrue(
            engine.confirmInvocation(
                "run_command",
                revocation,
                grantSessionTrust = true,
                providerId = "terminal-tab",
            ),
        )

        assertEquals(setOf(McpSessionTrust("terminal-tab", "run_command")), engine.sessionTrustedTools.value)
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command", "terminal-tab"))
        assertEquals(McpPolicyAction.ASK, engine.policyFor("run_command", "flow-tab"))
    }

    @Test
    fun `confirmInvocation without a provider in hand runs once but grants no name-only trust`() {
        val engine = McpPolicyEngine(policyFile = null)
        val revocation = engine.revocationVersion("run_command")

        assertTrue(engine.confirmInvocation("run_command", revocation, grantSessionTrust = true))

        assertTrue(engine.sessionTrustedTools.value.isEmpty())
        assertEquals(McpPolicyAction.ASK, engine.policyFor("run_command"))
        // The name-only grant is the leak: if it had stuck, every provider's run_command
        // would run unattended - so pin that it did not, from the provider side too.
        assertEquals(McpPolicyAction.ASK, engine.policyFor("run_command", "terminal-tab"))
    }

    @Test
    fun `a scoped revoke removes one provider's grant while a name-wide revoke clears them all`() {
        val engine = McpPolicyEngine(policyFile = null)
        engine.trustForSession("run_command", "terminal-tab")
        engine.trustForSession("run_command", "flow-tab")
        engine.trustForSession("helm_upgrade", "flow-tab")

        engine.revokeSessionTrust("run_command", "flow-tab")
        assertEquals(
            setOf(
                McpSessionTrust("terminal-tab", "run_command"),
                McpSessionTrust("flow-tab", "helm_upgrade"),
            ),
            engine.sessionTrustedTools.value,
        )

        // No provider in hand - the direction a name-keyed persisted-rule reset needs.
        engine.revokeSessionTrust("run_command")
        assertEquals(setOf(McpSessionTrust("flow-tab", "helm_upgrade")), engine.sessionTrustedTools.value)
    }

    @Test
    fun `revoking a persisted policy clears session trust for every provider of the name`() {
        val engine = McpPolicyEngine(policyFile = null)
        engine.trustForSession("run_command", "terminal-tab")
        engine.trustForSession("run_command", "flow-tab")

        assertTrue(engine.revokePersistedPolicy("run_command"))

        assertTrue(engine.sessionTrustedTools.value.isEmpty())
    }

    @Test
    fun `a section write drops session trust only for the provider it changed`() {
        val engine = McpPolicyEngine(policyFile = null)
        engine.trustForSession("run_command", "terminal-tab")
        engine.trustForSession("run_command", "flow-tab")

        val outcome =
            engine.setSectionPolicies(
                listOf(
                    McpSectionPolicyChange(
                        toolName = "run_command",
                        providerId = "terminal-tab",
                        expectedRevocation = engine.revocationVersion("run_command", "terminal-tab"),
                        expectedRule = null,
                        action = McpPolicyAction.DENY,
                    ),
                ),
            )

        assertEquals(McpProactivePolicyOutcome.Saved, outcome)
        assertEquals(setOf(McpSessionTrust("flow-tab", "run_command")), engine.sessionTrustedTools.value)
    }
}
