package ai.rever.boss.mcp

import ai.rever.boss.components.dialogs.savedRules
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A persisted tool rule used to be keyed by name only: any provider shipping the same tool
 * name inherited whatever rule the slot held, in both directions.
 *
 * Rules now live under the provider that decided them
 * ([McpToolPolicyConfig.providerToolRules]). The older name-only map
 * ([McpToolPolicyConfig.rules]) stays readable, so a file written before this change keeps
 * every rule and each one keeps answering for every provider. [ruleFor] combines the two, and a
 * DENY from either place holds.
 */
class McpPersistedRuleProviderScopeTest {
    private val tempFiles = mutableListOf<File>()

    private fun createTempPolicyFile(): File {
        val dir =
            kotlin.io.path
                .createTempDirectory("mcp-policy-scope-test")
                .toFile()
        return File(dir, "mcp-tool-policy.json").also { tempFiles.add(it) }
    }

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { it.parentFile?.deleteRecursively() }
        tempFiles.clear()
    }

    @Test
    fun `an ALLOW earned by one provider is not inherited by a same-named successor`() {
        val engine = McpPolicyEngine(policyFile = createTempPolicyFile())

        engine.setToolPolicy("run_command", McpPolicyAction.ALLOW, providerId = "terminal-tab")

        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command", "terminal-tab"))
        assertEquals(McpPolicyAction.ASK, engine.policyFor("run_command", "flow-tab"))
        assertEquals(McpPolicyAction.ASK, engine.policyFor("run_command"))
    }

    @Test
    fun `a DENY earned against one provider does not block a same-named successor`() {
        val engine = McpPolicyEngine(policyFile = createTempPolicyFile())

        engine.setToolPolicy("k8s_delete", McpPolicyAction.DENY, providerId = "terminal-tab")

        assertEquals(McpPolicyAction.DENY, engine.policyFor("k8s_delete", "terminal-tab"))
        assertEquals(McpPolicyAction.ASK, engine.policyFor("k8s_delete", "flow-tab"))
    }

    @Test
    fun `two providers each keep their own rule for one tool name`() {
        val engine = McpPolicyEngine(policyFile = createTempPolicyFile())

        engine.setToolPolicy("run_command", McpPolicyAction.ALLOW, providerId = "terminal-tab")
        engine.setToolPolicy("run_command", McpPolicyAction.DENY, providerId = "flow-tab")

        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command", "terminal-tab"))
        assertEquals(McpPolicyAction.DENY, engine.policyFor("run_command", "flow-tab"))
        assertEquals(
            2,
            engine.config.value
                .savedRules()
                .size,
        )
    }

    @Test
    fun `a rule with no provider still answers for every provider`() {
        val engine = McpPolicyEngine(policyFile = createTempPolicyFile())

        engine.setToolPolicy("codebase_write", McpPolicyAction.ALLOW)

        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("codebase_write"))
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("codebase_write", "editor-tab"))
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("codebase_write", "any-other-plugin"))
        assertEquals(McpPolicyAction.ALLOW, engine.config.value.rules["codebase_write"])
        assertTrue(
            engine.config.value.providerToolRules
                .isEmpty(),
        )
    }

    @Test
    fun `a name-wide DENY holds against a rule scoped to one provider`() {
        val file = createTempPolicyFile()
        file.writeText("""{"rules": {"k8s_delete": "DENY"}}""")
        val engine = McpPolicyEngine(policyFile = file)

        // The approval path writes with preserveDeny and is refused.
        val approved =
            engine.setToolPolicy("k8s_delete", McpPolicyAction.ALLOW, preserveDeny = true, providerId = "flow-tab")
        assertFalse(approved)
        // Even a write that is not refused cannot lift a denial that was never scoped to it.
        engine.setToolPolicy("k8s_delete", McpPolicyAction.ALLOW, providerId = "flow-tab")

        assertEquals(McpPolicyAction.DENY, engine.policyFor("k8s_delete", "flow-tab"))
        assertEquals(McpPolicyAction.DENY, engine.policyFor("k8s_delete", "terminal-tab"))
    }

    @Test
    fun `a section write cannot lift a name-wide DENY and says so`() {
        val file = createTempPolicyFile()
        file.writeText("""{"rules": {"k8s_delete": "DENY"}}""")
        val engine = McpPolicyEngine(policyFile = file)
        val change = McpSectionPolicyChange("k8s_delete", "flow-tab", 0L, McpPolicyAction.DENY, McpPolicyAction.ALLOW)

        val outcome = engine.setSectionPolicies(listOf(change))

        assertEquals(McpProactivePolicyOutcome.Denied, outcome)
        assertTrue(
            engine.config.value.providerToolRules
                .isEmpty(),
        )
        assertEquals(McpPolicyAction.DENY, engine.policyFor("k8s_delete", "flow-tab"))
    }

    @Test
    fun `a rule scoped to one provider can tighten a name-wide ALLOW for that provider only`() {
        val file = createTempPolicyFile()
        file.writeText("""{"rules": {"codebase_write": "ALLOW"}}""")
        val engine = McpPolicyEngine(policyFile = file)

        engine.setToolPolicy("codebase_write", McpPolicyAction.ASK, providerId = "flow-tab")

        assertEquals(McpPolicyAction.ASK, engine.policyFor("codebase_write", "flow-tab"))
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("codebase_write", "editor-tab"))
    }

    @Test
    fun `resetting a scoped rule leaves other providers and the name-wide rule alone`() {
        val file = createTempPolicyFile()
        file.writeText("""{"rules": {"run_command": "ASK"}}""")
        val engine = McpPolicyEngine(policyFile = file)
        engine.setToolPolicy("run_command", McpPolicyAction.DENY, providerId = "plugin-a")
        engine.setToolPolicy("run_command", McpPolicyAction.ALLOW, providerId = "plugin-b")

        assertTrue(engine.revokePersistedPolicy("run_command", "plugin-b"))

        assertEquals(McpPolicyAction.DENY, engine.policyFor("run_command", "plugin-a"))
        assertEquals(McpPolicyAction.ASK, engine.policyFor("run_command", "plugin-b"))
        assertEquals(McpPolicyAction.ASK, engine.config.value.rules["run_command"])
        assertEquals(
            listOf("plugin-a"),
            engine.config.value.providerToolRules.keys
                .toList(),
        )
    }

    @Test
    fun `resetting the name-wide rule leaves scoped rules alone`() {
        val file = createTempPolicyFile()
        file.writeText("""{"rules": {"run_command": "ASK"}}""")
        val engine = McpPolicyEngine(policyFile = file)
        engine.setToolPolicy("run_command", McpPolicyAction.DENY, providerId = "plugin-a")

        assertTrue(engine.revokePersistedPolicy("run_command"))

        assertNull(engine.config.value.rules["run_command"])
        assertEquals(McpPolicyAction.DENY, engine.policyFor("run_command", "plugin-a"))
        val reloaded = McpPolicyEngine(policyFile = file)
        assertEquals(McpPolicyAction.DENY, reloaded.policyFor("run_command", "plugin-a"))
    }

    @Test
    fun `setToolPolicyIfAbsent is refused by the same provider's rule but not by another provider's`() {
        val engine = McpPolicyEngine(policyFile = createTempPolicyFile())
        engine.setToolPolicy("run_command", McpPolicyAction.ALLOW, providerId = "terminal-tab")

        val sameProvider =
            engine.setToolPolicyIfAbsent(
                "run_command",
                McpPolicyAction.ASK,
                expectedRevocation = engine.revocationVersion("run_command", "terminal-tab"),
                providerId = "terminal-tab",
            )
        assertEquals(McpProactivePolicyOutcome.Refused, sameProvider)

        val otherProvider =
            engine.setToolPolicyIfAbsent(
                "run_command",
                McpPolicyAction.ASK,
                expectedRevocation = engine.revocationVersion("run_command", "flow-tab"),
                providerId = "flow-tab",
            )
        assertEquals(McpProactivePolicyOutcome.Saved, otherProvider)
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command", "terminal-tab"))
    }

    @Test
    fun `a policy file written before rules were per provider loads unchanged`() {
        // The literal older format: a flat "rules" map and nothing else.
        val file = createTempPolicyFile()
        file.writeText(
            """
            {
                "defaultMutatingAction": "ASK",
                "defaultReadOnlyAction": "ALLOW",
                "rules": {"codebase_write": "ALLOW", "k8s_delete": "DENY"}
            }
            """.trimIndent(),
        )

        val engine = McpPolicyEngine(policyFile = file)

        assertEquals(emptyMap(), engine.config.value.providerToolRules)
        assertEquals(
            mapOf("codebase_write" to McpPolicyAction.ALLOW, "k8s_delete" to McpPolicyAction.DENY),
            engine.config.value.rules,
        )
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("codebase_write", "any-provider"))
        assertEquals(McpPolicyAction.DENY, engine.policyFor("k8s_delete", "any-provider"))
        assertNull(engine.fault.value)
        // Loading does not rewrite the file.
        assertFalse(file.readText().contains("providerToolRules"))
    }

    @Test
    fun `a file that mixes older and per-provider rules round trips`() {
        val file = createTempPolicyFile()
        file.writeText(
            """{"rules": {"k8s_delete": "DENY"}, "providerToolRules": {"plugin-a": {"run_command": "ALLOW"}}}""",
        )

        val reloaded = McpPolicyEngine(policyFile = file)
        reloaded.setToolPolicy("run_command", McpPolicyAction.DENY, providerId = "plugin-b")
        val again = McpPolicyEngine(policyFile = file)

        assertEquals(McpPolicyAction.DENY, again.policyFor("k8s_delete", "plugin-a"))
        assertEquals(McpPolicyAction.ALLOW, again.policyFor("run_command", "plugin-a"))
        assertEquals(McpPolicyAction.DENY, again.policyFor("run_command", "plugin-b"))
    }

    @Test
    fun `a section write scopes each rule to its own provider and survives a reload`() {
        val file = createTempPolicyFile()
        val engine = McpPolicyEngine(policyFile = file)
        val change =
            McpSectionPolicyChange(
                "run_command",
                "terminal-tab",
                engine.revocationVersion("run_command", "terminal-tab"),
                null,
                McpPolicyAction.ALLOW,
            )

        engine.setSectionPolicies(listOf(change))
        val reloaded = McpPolicyEngine(policyFile = file)

        assertTrue(
            reloaded.config.value.rules
                .isEmpty(),
        )
        assertEquals(
            mapOf("terminal-tab" to mapOf("run_command" to McpPolicyAction.ALLOW)),
            reloaded.config.value.providerToolRules,
        )
        assertEquals(McpPolicyAction.ALLOW, reloaded.policyFor("run_command", "terminal-tab"))
        assertEquals(McpPolicyAction.ASK, reloaded.policyFor("run_command", "flow-tab"))
    }

    @Test
    fun `ruleFor resolves DENY from either place and prefers the provider's own otherwise`() {
        val config =
            McpToolPolicyConfig(
                rules = mapOf("a" to McpPolicyAction.DENY, "b" to McpPolicyAction.ALLOW),
                providerToolRules =
                    mapOf(
                        "p" to
                            mapOf(
                                "a" to McpPolicyAction.ALLOW,
                                "b" to McpPolicyAction.ASK,
                                "c" to McpPolicyAction.DENY,
                            ),
                    ),
            )

        assertEquals(McpPolicyAction.DENY, config.ruleFor("a", "p"))
        assertEquals(McpPolicyAction.ASK, config.ruleFor("b", "p"))
        assertEquals(McpPolicyAction.ALLOW, config.ruleFor("b", "q"))
        assertEquals(McpPolicyAction.DENY, config.ruleFor("c", "p"))
        assertNull(config.ruleFor("c", "q"))
        assertNull(config.ruleFor("c", null))
        assertEquals(McpPolicyAction.DENY, config.ruleFor("a", null))
    }
}
