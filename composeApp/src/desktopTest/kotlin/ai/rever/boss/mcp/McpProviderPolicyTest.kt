package ai.rever.boss.mcp

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "Trust This Plugin": a persisted, provider-wide ALLOW ([McpPolicyEngine.setProviderPolicy]) so
 * an operator does not have to approve every tool one plugin contributes individually.
 *
 * Precedence is the interesting part - see [McpPolicyEngine.policyFor]'s own KDoc for the full
 * ordering. These tests pin each step against the ones next to it, since that is exactly where a
 * future change is most likely to invert two steps without anything else noticing.
 */
class McpProviderPolicyTest {
    private val tempFiles = mutableListOf<File>()

    private fun createTempPolicyFile(): File {
        val dir =
            kotlin.io.path
                .createTempDirectory("mcp-provider-policy-test")
                .toFile()
        return File(dir, "mcp-tool-policy.json").also { tempFiles.add(it) }
    }

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { it.parentFile?.deleteRecursively() }
        tempFiles.clear()
    }

    @Test
    fun `a provider ALLOW covers every tool from that provider, but not other providers`() {
        val engine = McpPolicyEngine(policyFile = createTempPolicyFile())

        assertEquals(McpPolicyAction.ASK, engine.policyFor("k8s_delete", "terminal-tab"))
        assertTrue(engine.setProviderPolicy("terminal-tab", McpPolicyAction.ALLOW))

        // Every tool named as coming from the trusted provider is allowed now...
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("k8s_delete", "terminal-tab"))
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command", "terminal-tab"))
        // ...but the same tool name from a DIFFERENT provider is not.
        assertEquals(McpPolicyAction.ASK, engine.policyFor("k8s_delete", "flow-tab"))
        // A read-only tool from the trusted provider was already ALLOW by default; still is.
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("git_status", "terminal-tab"))
    }

    @Test
    fun `a tool-specific ALLOW or ASK wins over its provider's rule - a DENY at either level always wins`() {
        val engine = McpPolicyEngine(policyFile = createTempPolicyFile())

        engine.setProviderPolicy("terminal-tab", McpPolicyAction.ALLOW)
        engine.setToolPolicy("run_command", McpPolicyAction.DENY)
        // The provider trusts everything, but this ONE tool has its own, more specific DENY -
        // and DENY beats a broader ALLOW regardless of which one is more specific.
        assertEquals(McpPolicyAction.DENY, engine.policyFor("run_command", "terminal-tab"))
        // A sibling tool from the same provider is unaffected by that one override.
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("k8s_delete", "terminal-tab"))

        // NOT symmetric: a tool-specific ALLOW does NOT survive a provider-wide DENY. DENY is
        // never "most specific wins" - see policyFor's own KDoc for why a narrower ALLOW must
        // not be able to punch a hole through a broader, deliberate denial.
        val engine2 = McpPolicyEngine(policyFile = createTempPolicyFile())
        engine2.setProviderPolicy("flow-tab", McpPolicyAction.DENY)
        engine2.setToolPolicy("git_status", McpPolicyAction.ALLOW)
        assertEquals(McpPolicyAction.DENY, engine2.policyFor("git_status", "flow-tab"))
        assertEquals(McpPolicyAction.DENY, engine2.policyFor("run_command", "flow-tab"))
    }

    @Test
    fun `a provider DENY overrides session trust for its tools, the same way a tool DENY does`() {
        val engine = McpPolicyEngine(policyFile = createTempPolicyFile())

        engine.trustForSession("run_command", "terminal-tab")
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command", "terminal-tab"))

        engine.setProviderPolicy("terminal-tab", McpPolicyAction.DENY)
        assertEquals(
            McpPolicyAction.DENY,
            engine.policyFor("run_command", "terminal-tab"),
            "an explicit provider-wide DENY must not be weaker than a per-tool DENY - both beat session trust",
        )
    }

    @Test
    fun `a provider ALLOW does not override a per-tool DENY set before or after it`() {
        val engine = McpPolicyEngine(policyFile = createTempPolicyFile())

        engine.setToolPolicy("run_command", McpPolicyAction.DENY)
        engine.setProviderPolicy("terminal-tab", McpPolicyAction.ALLOW)
        assertEquals(McpPolicyAction.DENY, engine.policyFor("run_command", "terminal-tab"))
    }

    @Test
    fun `provider rules persist across reload, and are absent from a caller that omits providerId`() {
        val file = createTempPolicyFile()
        val engine = McpPolicyEngine(policyFile = file)
        engine.setProviderPolicy("terminal-tab", McpPolicyAction.ALLOW)

        val reloaded = McpPolicyEngine(policyFile = file)
        assertEquals(McpPolicyAction.ALLOW, reloaded.policyFor("k8s_delete", "terminal-tab"))
        // A caller that never learned the provider id (the default parameter) cannot benefit
        // from a rule it never named - falls through to the ordinary risk-based default.
        assertEquals(McpPolicyAction.ASK, reloaded.policyFor("k8s_delete"))
    }

    @Test
    fun `a legacy provider DENY governs namespaced invocation and policy writes`() {
        val file = createTempPolicyFile()
        val engine = McpPolicyEngine(policyFile = file)
        engine.setProviderPolicy("shared-provider", McpPolicyAction.DENY)

        val reloaded = McpPolicyEngine(policyFile = file)

        assertEquals(
            McpPolicyAction.DENY,
            reloaded.policyFor("git_status", "plugin-a::shared-provider"),
            "a saved raw DENY must not fail open when the live provider gains its plugin namespace",
        )
        assertEquals(
            McpPolicyAction.DENY,
            reloaded.policyFor("git_status", "plugin-b::shared-provider"),
            "an ambiguous legacy DENY must conservatively cover every plugin that claims the old id",
        )
        assertEquals(
            McpProactivePolicyOutcome.Denied,
            reloaded.setProviderPolicyIfAbsent(
                "plugin-c::shared-provider",
                McpPolicyAction.ALLOW,
            ),
            "a proactive scoped grant must not be staged behind the effective legacy DENY",
        )
        val providerId = "plugin-b::shared-provider"
        assertEquals(
            McpProactivePolicyOutcome.Denied,
            reloaded.setSectionPolicies(
                listOf(
                    McpSectionPolicyChange(
                        toolName = "git_status",
                        providerId = providerId,
                        expectedRevocation = reloaded.revocationVersion("git_status", providerId),
                        expectedRule = null,
                        action = McpPolicyAction.ALLOW,
                    ),
                ),
            ),
            "a section grant must use the same effective provider DENY as invocation",
        )

        reloaded.setProviderPolicy("plugin-a::shared-provider", McpPolicyAction.ALLOW)
        assertEquals(
            McpPolicyAction.DENY,
            reloaded.policyFor("git_status", "plugin-a::shared-provider"),
            "a new scoped ALLOW must not silently override the older durable DENY",
        )
    }

    @Test
    fun `revoking a legacy provider DENY immediately lifts namespaced inheritance without scoped copies`() {
        val file = createTempPolicyFile()
        val engine = McpPolicyEngine(policyFile = file)
        val namespacedId = "plugin-a::shared-provider"
        val fallbackAction = engine.policyFor("git_status", namespacedId)

        engine.setProviderPolicy("shared-provider", McpPolicyAction.DENY)

        assertEquals(
            McpPolicyAction.DENY,
            engine.policyFor("git_status", namespacedId),
        )
        assertFalse(
            engine.config.value.providerRules
                .containsKey(namespacedId),
            "runtime compatibility must not persist a derived scoped rule",
        )

        assertTrue(engine.revokeProviderPolicy("shared-provider"))

        assertEquals(
            fallbackAction,
            engine.policyFor("git_status", namespacedId),
            "revoking the raw rule must immediately lift its inherited effect",
        )
        assertFalse(
            engine.config.value.providerRules
                .containsKey(namespacedId),
        )

        val reloaded = McpPolicyEngine(policyFile = file)
        assertEquals(fallbackAction, reloaded.policyFor("git_status", namespacedId))
        assertFalse(
            reloaded.config.value.providerRules
                .containsKey(namespacedId),
        )
    }

    @Test
    fun `a legacy provider ALLOW is not inherited by a namespaced provider`() {
        val file = createTempPolicyFile()
        val engine = McpPolicyEngine(policyFile = file)
        engine.setProviderPolicy("shared-provider", McpPolicyAction.ALLOW)

        val reloaded = McpPolicyEngine(policyFile = file)

        assertFalse(
            reloaded.config.value.providerRules
                .containsKey("plugin-a::shared-provider"),
        )
        assertEquals(
            McpPolicyAction.ASK,
            reloaded.policyFor("run_command", "plugin-a::shared-provider"),
            "legacy trust must not cross the plugin namespace introduced to stop provider-id aliasing",
        )
    }

    @Test
    fun `revoking a provider rule removes it rather than rewriting it, so the tool falls back to its own rule`() {
        val file = createTempPolicyFile()
        val engine = McpPolicyEngine(policyFile = file)

        engine.setProviderPolicy("terminal-tab", McpPolicyAction.ALLOW)
        assertTrue(engine.revokeProviderPolicy("terminal-tab"))

        assertFalse(
            engine.config.value.providerRules
                .containsKey("terminal-tab"),
            "revoke must remove the key, not rewrite it to some other action - the exact mistake " +
                "an earlier version of this same idea made for per-tool revocation",
        )
        assertEquals(McpPolicyAction.ASK, engine.policyFor("k8s_delete", "terminal-tab"))

        // Survives a reload too - the removal itself was persisted, not just applied in memory.
        val reloaded = McpPolicyEngine(policyFile = file)
        assertFalse(
            reloaded.config.value.providerRules
                .containsKey("terminal-tab"),
        )
    }

    @Test
    fun `revoking one provider's trust leaves another provider's trust untouched`() {
        val engine = McpPolicyEngine(policyFile = createTempPolicyFile())

        engine.setProviderPolicy("terminal-tab", McpPolicyAction.ALLOW)
        engine.setProviderPolicy("flow-tab", McpPolicyAction.ALLOW)
        engine.revokeProviderPolicy("terminal-tab")

        assertEquals(McpPolicyAction.ASK, engine.policyFor("run_command", "terminal-tab"))
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command", "flow-tab"))
    }

    @Test
    fun `a policy-unreadable fault denies a provider-trusted tool until a write recovers it`() {
        val file = createTempPolicyFile()
        file.writeText("{ not valid json")
        val engine = McpPolicyEngine(policyFile = file)

        // Fail-closed defaults apply to a mutating tool from a provider that was never asked
        // about, same as the existing per-tool case in McpPolicyEngineTest.
        assertEquals(McpPolicyAction.DENY, engine.policyFor("run_command", "terminal-tab"))

        // The only way to populate providerRules is a load (which would not have hit the fault
        // to begin with) or a successful write - and any successful write is what clears the
        // fault, same as setToolPolicy already does. So recovery and trust land together here:
        // an operator fixing "everything is denied" via the very action that also grants trust.
        assertTrue(engine.setProviderPolicy("terminal-tab", McpPolicyAction.ALLOW))
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command", "terminal-tab"))
    }
}
