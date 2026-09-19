package ai.rever.boss.mcp

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Edge cases around #815's provider-scoped session trust that [McpSessionTrustScopingTest]'s
 * core scenarios do not pin: one provider id shared across two windows, no-op scoped revokes,
 * same-provider cross-tool isolation, [McpPolicyEngine.revokePersistedPolicy]'s name-wide
 * trust clear beside a same-provider survivor, two grants racing on one tool name, and the
 * DENY-before-trust precedence order inside [McpPolicyEngine.policyFor].
 *
 * Every scenario uses mutating-catalog names (run_command, helm_upgrade) on purpose: their
 * no-rule default is ASK, so an unearned grant surfaces as a wrong ALLOW. An innocent name
 * that defaults ALLOW would mask exactly the leaks these tests exist to catch.
 */
class McpSessionTrustEdgeCasesTest {
    @Test
    fun `trust follows the provider id so a second window presenting the same provider shares it`() {
        val engine = McpPolicyEngine(policyFile = null)

        // Window 1: the operator approves terminal-tab's run_command and trusts it for the session.
        val revocation = engine.revocationVersion("run_command", "terminal-tab")
        assertTrue(
            engine.beginDispatch("run_command", revocation, grantSessionTrust = true, providerId = "terminal-tab"),
        )

        // Window 2 is a different surface of the same session showing a plugin with the SAME
        // provider id - a re-opened or replacement window of one plugin, not a different plugin.
        // The engine's only notion of a tool's identity is (providerId, toolName), so the grant
        // window 1 earned must answer window 2's policy check too: the provider id IS identity.
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command", "terminal-tab"))

        // Window 2's already-approved call also passes the final boundary on that same grant -
        // beginDispatch re-authorizes a decided call; it is not a second prompt decision.
        assertTrue(
            engine.beginDispatch(
                "run_command",
                engine.revocationVersion("run_command", "terminal-tab"),
                grantSessionTrust = false,
                providerId = "terminal-tab",
            ),
        )

        // A second window presenting a DIFFERENT provider id is a different tool and keeps prompting.
        assertEquals(McpPolicyAction.ASK, engine.policyFor("run_command", "flow-tab"))
    }

    @Test
    fun `a scoped revoke for a provider that was never trusted changes nothing`() {
        val engine = McpPolicyEngine(policyFile = null)
        engine.trustForSession("run_command", "terminal-tab")
        engine.trustForSession("run_command", "flow-tab")
        engine.trustForSession("helm_upgrade", "flow-tab")
        val granted =
            setOf(
                McpSessionTrust("terminal-tab", "run_command"),
                McpSessionTrust("flow-tab", "run_command"),
                McpSessionTrust("flow-tab", "helm_upgrade"),
            )

        // ghost-tab holds no grant, so the scoped filter matches nothing. It must not disturb
        // any other provider's grant - including the same tool name trusted for other providers.
        engine.revokeSessionTrust("run_command", "ghost-tab")
        assertEquals(granted, engine.sessionTrustedTools.value)
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command", "terminal-tab"))
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command", "flow-tab"))
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("helm_upgrade", "flow-tab"))

        // The same holds for a name-wide revoke of a tool nobody ever trusted.
        engine.revokeSessionTrust("k8s_delete")
        assertEquals(granted, engine.sessionTrustedTools.value)
    }

    @Test
    fun `trust on one tool never leaks to the same provider's other tools`() {
        val engine = McpPolicyEngine(policyFile = null)

        // The operator approved terminal-tab's run_command - one tool of the plugin, not the
        // plugin. helm_upgrade from the same provider must keep prompting.
        engine.trustForSession("run_command", "terminal-tab")
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command", "terminal-tab"))
        assertEquals(McpPolicyAction.ASK, engine.policyFor("helm_upgrade", "terminal-tab"))
        assertEquals(setOf(McpSessionTrust("terminal-tab", "run_command")), engine.sessionTrustedTools.value)

        // Granting the second tool later adds a pair; it does not widen the first grant into a
        // provider-wide one, and the two pairs stay independently revocable.
        engine.trustForSession("helm_upgrade", "terminal-tab")
        assertEquals(
            setOf(
                McpSessionTrust("terminal-tab", "run_command"),
                McpSessionTrust("terminal-tab", "helm_upgrade"),
            ),
            engine.sessionTrustedTools.value,
        )
        engine.revokeSessionTrust("helm_upgrade", "terminal-tab")
        assertEquals(McpPolicyAction.ASK, engine.policyFor("helm_upgrade", "terminal-tab"))
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command", "terminal-tab"))
    }

    @Test
    fun `revokePersistedPolicy clears the reset tool's trusts for every provider but keeps its other tools`() {
        val engine = McpPolicyEngine(policyFile = null)
        engine.trustForSession("run_command", "terminal-tab")
        engine.trustForSession("run_command", "flow-tab")
        engine.trustForSession("helm_upgrade", "terminal-tab")

        // Current engine behavior, pinned with its intent: revokePersistedPolicy routes through
        // revokeSessionTrust(toolName) with the provider left null - a NAME-WIDE clear - because
        // the persisted rules on disk are name-keyed and cannot tell which provider a session
        // grant was earned for (see McpPolicyEngine.revokeSessionTrust's KDoc). Over-removing
        // trust fails closed; keeping it would not. So both providers' run_command grants fall
        // even though the reset only ever spoke about the tool's name.
        assertTrue(engine.revokePersistedPolicy("run_command"))
        assertEquals(McpPolicyAction.ASK, engine.policyFor("run_command", "terminal-tab"))
        assertEquals(McpPolicyAction.ASK, engine.policyFor("run_command", "flow-tab"))

        // The clear is bounded by the reset tool's NAME. terminal-tab's helm_upgrade grant was
        // not part of the reset and must survive it, still answering ALLOW.
        assertEquals(setOf(McpSessionTrust("terminal-tab", "helm_upgrade")), engine.sessionTrustedTools.value)
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("helm_upgrade", "terminal-tab"))
    }

    @Test
    fun `two providers' grants on one tool name coexist when granted concurrently`() {
        val engine = McpPolicyEngine(policyFile = null)

        // Line the two grantors up so their trustForSession calls genuinely overlap. The store
        // is a MutableStateFlow whose update is a compare-and-swap loop, so neither provider's
        // grant can be lost to a read-modify-write race; the engine must end up holding both.
        val atTheGate = CountDownLatch(2)
        val done = CountDownLatch(2)
        val grant = { providerId: String ->
            thread {
                atTheGate.countDown()
                atTheGate.await()
                engine.trustForSession("run_command", providerId)
                done.countDown()
            }
        }
        grant("terminal-tab")
        grant("flow-tab")
        assertTrue(done.await(10, TimeUnit.SECONDS))

        assertEquals(
            setOf(
                McpSessionTrust("terminal-tab", "run_command"),
                McpSessionTrust("flow-tab", "run_command"),
            ),
            engine.sessionTrustedTools.value,
        )
        // Each provider's grant resolves independently for the shared tool name.
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command", "terminal-tab"))
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command", "flow-tab"))
        // A third provider that never earned a grant still prompts.
        assertEquals(McpPolicyAction.ASK, engine.policyFor("run_command", "shell-tab"))
    }

    @Test
    fun `an explicit DENY outranks session trust no matter which one lands first`() {
        val engine = McpPolicyEngine(policyFile = null)

        // Order 1 - trust first, DENY second. policyFor evaluates its inputs most authoritative
        // first: a fault, then a tool-specific DENY, then the provider's own DENY, and only
        // AFTER those the session-trust membership check. A trust granted before the rule
        // existed therefore cannot punch through the newer DENY.
        engine.trustForSession("run_command", "terminal-tab")
        assertTrue(engine.setToolPolicy("run_command", McpPolicyAction.DENY))
        assertEquals(McpPolicyAction.DENY, engine.policyFor("run_command", "terminal-tab"))

        // The grant is outranked, not erased: it stays in the set, inert while the DENY stands.
        assertEquals(setOf(McpSessionTrust("terminal-tab", "run_command")), engine.sessionTrustedTools.value)

        // The final authorization boundary agrees - a queued approval cannot smuggle the
        // trusted call past a DENY saved after the approval was captured.
        assertFalse(
            engine.beginDispatch(
                "run_command",
                engine.revocationVersion("run_command", "terminal-tab"),
                grantSessionTrust = true,
                providerId = "terminal-tab",
            ),
        )

        // Order 2 + provider scope - a provider-wide DENY saved BEFORE the grant also wins:
        // granting trust for an already-denied (provider, tool) pair does not upgrade the answer.
        assertTrue(engine.setProviderPolicy("flow-tab", McpPolicyAction.DENY))
        engine.trustForSession("helm_upgrade", "flow-tab")
        assertEquals(McpPolicyAction.DENY, engine.policyFor("helm_upgrade", "flow-tab"))

        // The provider DENY is scoped to that provider: a sibling provider's same-named tool
        // falls back to the mutating default, ASK.
        assertEquals(McpPolicyAction.ASK, engine.policyFor("helm_upgrade", "terminal-tab"))
    }
}
