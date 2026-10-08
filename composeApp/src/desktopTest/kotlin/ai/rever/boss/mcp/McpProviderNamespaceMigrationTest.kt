package ai.rever.boss.mcp

import ai.rever.boss.components.dialogs.matchingLegacyRuleFor
import ai.rever.boss.utils.atomicWriteText
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies preservation of MCP provider denials and namespacing migration (#1633, #958).
 *
 * Requirements:
 * 1. Host-owned mapping from old raw provider ID to new pluginId::providerId identity.
 *    Installed but currently disabled plugins are included when checking ownership.
 * 2. Reconcile legacy entries: migrate ALLOW only when ownership is unambiguous; keep ambiguous
 *    ALLOW inert and visible for review; keep legacy DENY effective against matching candidates
 *    until safely migrated so an upgrade cannot silently lift it.
 * 3. Persist reconciled config atomically before retiring old keys; on write failure, retain old
 *    denial behavior and surface policy fault.
 * 4. Restart with legacy DENY and ALLOW, two plugins claiming same ID, an unavailable plugin that
 *    registers later, conflicting new rules, and failed persistence.
 *
 * Key assertion: ambiguous ALLOW never authorizes either plugin; legacy DENY never disappears during migration.
 */
class McpProviderNamespaceMigrationTest {
    private val tempDirs = mutableListOf<File>()
    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

    private fun createTempPolicyFile(initialConfig: McpToolPolicyConfig? = null): File {
        val dir =
            kotlin.io.path
                .createTempDirectory("mcp-migration-test")
                .toFile()
        tempDirs.add(dir)
        val file = File(dir, "mcp-tool-policy.json")
        if (initialConfig != null) {
            file.atomicWriteText(json.encodeToString(initialConfig))
        }
        return file
    }

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    @Test
    fun `restart with legacy DENY and ALLOW migrates unambiguous rules and retires old keys`() {
        val initialConfig =
            McpToolPolicyConfig(
                providerRules =
                    mapOf(
                        "legacy-allow" to McpPolicyAction.ALLOW,
                        "legacy-deny" to McpPolicyAction.DENY,
                    ),
                providerMapping =
                    mapOf(
                        "legacy-allow" to setOf("pluginA::legacy-allow"),
                        "legacy-deny" to setOf("pluginB::legacy-deny"),
                    ),
            )
        val policyFile = createTempPolicyFile(initialConfig)
        val engine =
            McpPolicyEngine(
                policyFile = policyFile,
                getInstalledPluginIds = { setOf("pluginA", "pluginB") },
            )

        // Reconcile legacy entries
        val outcome = engine.reconcileLegacyEntries()
        assertEquals(McpProactivePolicyOutcome.Saved, outcome)

        // Legacy keys should be retired and migrated to namespaced identities
        val rules = engine.config.value.providerRules
        assertEquals(McpPolicyAction.ALLOW, rules["pluginA::legacy-allow"])
        assertEquals(McpPolicyAction.DENY, rules["pluginB::legacy-deny"])
        assertTrue("legacy-allow" !in rules, "Unambiguous legacy ALLOW must be retired")
        assertTrue("legacy-deny" !in rules, "Unambiguous legacy DENY must be retired")
        assertTrue(engine.unresolvedLegacyRules().isEmpty(), "No unresolved legacy rules should remain")

        // Fresh restart must load the migrated rules directly from disk
        val restartedEngine =
            McpPolicyEngine(
                policyFile = policyFile,
                getInstalledPluginIds = { setOf("pluginA", "pluginB") },
            )
        assertEquals(McpPolicyAction.ALLOW, restartedEngine.policyFor("status", "pluginA::legacy-allow"))
        assertEquals(McpPolicyAction.DENY, restartedEngine.policyFor("status", "pluginB::legacy-deny"))
    }

    @Test
    fun `two plugins claiming same old ID - ambiguous ALLOW never authorizes and legacy DENY holds`() {
        val initialConfig =
            McpToolPolicyConfig(
                providerRules =
                    mapOf(
                        "shared-tools" to McpPolicyAction.ALLOW,
                        "shared-danger" to McpPolicyAction.DENY,
                    ),
                providerMapping =
                    mapOf(
                        "shared-tools" to setOf("pluginA::shared-tools", "pluginB::shared-tools"),
                        "shared-danger" to setOf("pluginA::shared-danger", "pluginB::shared-danger"),
                    ),
            )
        val policyFile = createTempPolicyFile(initialConfig)
        val engine =
            McpPolicyEngine(
                policyFile = policyFile,
                getInstalledPluginIds = { setOf("pluginA", "pluginB") },
            )

        val candidateAllow = listOf("pluginA::shared-tools", "pluginB::shared-tools")
        val candidateDeny = listOf("pluginA::shared-danger", "pluginB::shared-danger")

        // Key assertion BEFORE reconcile: ambiguous ALLOW never authorizes either plugin
        assertAmbiguousAllowDoesNotAuthorize(engine, "shared-tools", candidateAllow, "mutating_action")
        assertLegacyDenyAppliesToCandidates(engine, candidateDeny, "any_tool")

        // Attempt reconciliation
        val outcome = engine.reconcileLegacyEntries()
        assertEquals(McpProactivePolicyOutcome.Saved, outcome)

        // Ambiguous ALLOW must NOT be retired and must remain visible for review
        val unresolved = engine.unresolvedLegacyRules()
        assertTrue("shared-tools" in unresolved, "Ambiguous ALLOW must remain unresolved for review")
        assertTrue("shared-danger" in unresolved, "Ambiguous DENY must remain unresolved so it cannot disappear")

        // Key assertion AFTER reconcile: ambiguous ALLOW still never authorizes either plugin
        assertAmbiguousAllowDoesNotAuthorize(
            engine,
            "shared-tools",
            candidateAllow,
            "mutating_action",
            " after reconcile",
        )
        assertLegacyDenyAppliesToCandidates(engine, candidateDeny, "any_tool", " after reconcile")
    }

    @Test
    fun `unavailable plugin that registers later - disabled installed plugin prevents false unambiguous claim`() {
        val initialConfig =
            McpToolPolicyConfig(
                providerRules =
                    mapOf(
                        "service-tools" to McpPolicyAction.ALLOW,
                        "service-danger" to McpPolicyAction.DENY,
                    ),
                providerMapping =
                    mapOf(
                        "service-tools" to setOf("pluginA::service-tools", "pluginB::service-tools"),
                        "service-danger" to setOf("pluginA::service-danger", "pluginB::service-danger"),
                    ),
            )
        val policyFile = createTempPolicyFile(initialConfig)

        // pluginB is installed but currently disabled (not running).
        // getInstalledPluginIds includes both pluginA and pluginB.
        val engine =
            McpPolicyEngine(
                policyFile = policyFile,
                getInstalledPluginIds = { setOf("pluginA", "pluginB") },
            )

        // Only pluginA registers in this session initially:
        engine.recordProviderRegistration("pluginA", "service-tools")
        engine.recordProviderRegistration("pluginA", "service-danger")

        // Reconcile runs while pluginB has not registered yet:
        // "only one provider is running" does not prove an old ALLOW belongs to it!
        val outcome = engine.reconcileLegacyEntries()
        assertEquals(McpProactivePolicyOutcome.Saved, outcome)

        // pluginA must NOT receive the ALLOW grant:
        assertNotEquals(
            McpPolicyAction.ALLOW,
            engine.policyFor("mutating_op", "pluginA::service-tools", declaredReadOnly = false),
            "Ambiguous ALLOW must not be granted to plugin A just because plugin B is disabled",
        )
        // pluginA must still be denied for service-danger:
        assertEquals(
            McpPolicyAction.DENY,
            engine.policyFor("mutating_op", "pluginA::service-danger"),
        )

        // Later, pluginB becomes enabled and registers its provider:
        engine.recordProviderRegistration("pluginB", "service-tools")
        engine.recordProviderRegistration("pluginB", "service-danger")

        // Reconcile runs again:
        engine.reconcileLegacyEntries()

        // Neither plugin is authorized by the ambiguous ALLOW:
        assertNotEquals(
            McpPolicyAction.ALLOW,
            engine.policyFor("mutating_op", "pluginA::service-tools", declaredReadOnly = false),
        )
        assertNotEquals(
            McpPolicyAction.ALLOW,
            engine.policyFor("mutating_op", "pluginB::service-tools", declaredReadOnly = false),
        )

        // Both plugins are denied by the legacy DENY:
        assertEquals(
            McpPolicyAction.DENY,
            engine.policyFor("mutating_op", "pluginA::service-danger"),
        )
        assertEquals(
            McpPolicyAction.DENY,
            engine.policyFor("mutating_op", "pluginB::service-danger"),
        )
    }

    @Test
    fun `conflicting new rules - new ALLOW rule cannot silently lift legacy DENY`() {
        val initialConfig =
            McpToolPolicyConfig(
                providerRules =
                    mapOf(
                        "restricted-tools" to McpPolicyAction.DENY,
                    ),
                providerMapping =
                    mapOf(
                        "restricted-tools" to setOf("pluginA::restricted-tools"),
                    ),
            )
        val policyFile = createTempPolicyFile(initialConfig)
        val engine =
            McpPolicyEngine(
                policyFile = policyFile,
                getInstalledPluginIds = { setOf("pluginA") },
            )

        // A new conflicting rule attempts to set ALLOW for pluginA::restricted-tools:
        // (e.g. from an upgrade script, pack, or direct write)
        val updated =
            engine.config.value.copy(
                providerRules =
                    engine.config.value.providerRules + ("pluginA::restricted-tools" to McpPolicyAction.ALLOW),
            )
        // Simulate an upgrade introducing this conflicting rule into config:
        policyFile.atomicWriteText(json.encodeToString(updated))
        val engineWithConflict =
            McpPolicyEngine(
                policyFile = policyFile,
                getInstalledPluginIds = { setOf("pluginA") },
            )

        // Legacy DENY for restricted-tools must take precedence over the conflicting ALLOW!
        assertEquals(
            McpPolicyAction.DENY,
            engineWithConflict.policyFor("delete_data", "pluginA::restricted-tools"),
            "Legacy DENY must remain effective against matching candidate despite conflicting new ALLOW",
        )
    }

    @Test
    fun `failed persistence retains old denial behavior and surfaces policy fault`() {
        var notifiedFault: McpPolicyFault? = null
        val initialConfig =
            McpToolPolicyConfig(
                providerRules =
                    mapOf(
                        "old-danger" to McpPolicyAction.DENY,
                    ),
                providerMapping =
                    mapOf(
                        "old-danger" to setOf("pluginA::old-danger"),
                    ),
            )
        val policyFile = createTempPolicyFile(initialConfig)

        val engine =
            McpPolicyEngine(
                policyFile = policyFile,
                onFault = { notifiedFault = it },
                getInstalledPluginIds = { setOf("pluginA") },
            )

        // Replace parent directory with a regular file so atomicWriteText fails on write:
        policyFile.parentFile.deleteRecursively()
        policyFile.parentFile.createNewFile()

        // Attempt reconcile - disk write will fail
        val outcome = engine.reconcileLegacyEntries()

        assertTrue(outcome is McpProactivePolicyOutcome.Failed, "Reconcile must return Failed on write failure")
        assertTrue(engine.fault.value is McpPolicyFault.ProviderPolicyPersistFailed, "Policy fault must be set")
        assertTrue(notifiedFault is McpPolicyFault.ProviderPolicyPersistFailed, "Fault must be surfaced to onFault")

        // Old denial behavior must be retained in-memory
        assertEquals(
            McpPolicyAction.DENY,
            engine.policyFor("any_tool", "pluginA::old-danger"),
            "Legacy DENY must never disappear when persistence fails",
        )
        assertEquals(
            McpPolicyAction.DENY,
            engine.config.value.providerRules["old-danger"],
            "Old denial key must not be retired when write fails",
        )
    }

    @Test
    fun `key assertion - ambiguous ALLOW never authorizes and legacy DENY holds during migration`() {
        val initialConfig =
            McpToolPolicyConfig(
                providerRules =
                    mapOf(
                        "ambiguous-allow" to McpPolicyAction.ALLOW,
                        "ambiguous-deny" to McpPolicyAction.DENY,
                    ),
                providerMapping =
                    mapOf(
                        "ambiguous-allow" to setOf("plugin1::ambiguous-allow", "plugin2::ambiguous-allow"),
                        "ambiguous-deny" to setOf("plugin1::ambiguous-deny", "plugin2::ambiguous-deny"),
                    ),
            )
        val policyFile = createTempPolicyFile(initialConfig)
        val engine =
            McpPolicyEngine(
                policyFile = policyFile,
                getInstalledPluginIds = { setOf("plugin1", "plugin2") },
            )

        val candidateAllow = listOf("plugin1::ambiguous-allow", "plugin2::ambiguous-allow")
        val candidateDeny = listOf("plugin1::ambiguous-deny", "plugin2::ambiguous-deny")

        // 1. Ambiguous ALLOW check
        assertAmbiguousAllowDoesNotAuthorize(engine, "ambiguous-allow", candidateAllow)

        // 2. Legacy DENY check
        assertLegacyDenyAppliesToCandidates(engine, candidateDeny)

        // 3. Migration attempt
        engine.reconcileLegacyEntries()

        // 4. Assert ambiguous ALLOW still never authorizes either plugin
        assertAmbiguousAllowDoesNotAuthorize(
            engine,
            "ambiguous-allow",
            candidateAllow,
            suffix = " after migration",
        )

        // 5. Assert legacy DENY never disappears during or after migration
        assertLegacyDenyAppliesToCandidates(engine, candidateDeny, suffix = " after migration")
    }

    private fun assertAmbiguousAllowDoesNotAuthorize(
        engine: McpPolicyEngine,
        rawId: String,
        candidateIds: List<String>,
        toolName: String = "exec",
        suffix: String = "",
    ) {
        for (candidateId in candidateIds) {
            assertNotEquals(
                McpPolicyAction.ALLOW,
                engine.policyFor(toolName, candidateId, declaredReadOnly = false),
                "Ambiguous ALLOW must never authorize $candidateId$suffix",
            )
        }
        assertNotEquals(
            McpPolicyAction.ALLOW,
            engine.policyFor(toolName, rawId, declaredReadOnly = false),
            "Ambiguous ALLOW must never authorize unnamespaced raw provider$suffix",
        )
    }

    private fun assertLegacyDenyAppliesToCandidates(
        engine: McpPolicyEngine,
        candidateIds: List<String>,
        toolName: String = "exec",
        suffix: String = "",
    ) {
        for (candidateId in candidateIds) {
            assertEquals(
                McpPolicyAction.DENY,
                engine.policyFor(toolName, candidateId),
                "Legacy DENY must apply to $candidateId$suffix",
            )
        }
    }

    @Test
    fun `host providers are exempt from legacy rule classification and ambiguity gate`() {
        val initialConfig =
            McpToolPolicyConfig(
                providerRules =
                    mapOf(
                        "boss-workspace" to McpPolicyAction.ALLOW,
                        "legacy-plugin" to McpPolicyAction.ALLOW,
                    ),
                providerMapping =
                    mapOf(
                        "legacy-plugin" to setOf("pluginA::legacy-plugin", "pluginB::legacy-plugin"),
                    ),
            )
        val policyFile = createTempPolicyFile(initialConfig)
        val engine =
            McpPolicyEngine(
                policyFile = policyFile,
                getInstalledPluginIds = { setOf("pluginA", "pluginB") },
                getRegisteredHostProviderIds = { setOf("boss-workspace") },
            )

        // boss-workspace is a host provider, so unresolvedLegacyRules must NOT include it
        val unresolved = engine.unresolvedLegacyRules()
        assertTrue("boss-workspace" !in unresolved, "Host provider must not be classified as legacy rule")
        assertTrue("legacy-plugin" in unresolved, "Legacy plugin rule must be in unresolved legacy rules")

        // Host provider ALLOW must be honored directly without being treated as ambiguous
        assertEquals(
            McpPolicyAction.ALLOW,
            engine.policyFor("open_workspace", "boss-workspace", declaredReadOnly = false),
            "Host provider ALLOW must be honored",
        )
        assertFalse(
            engine.isOwnershipAmbiguous("boss-workspace"),
            "Host provider must never be treated as ownership ambiguous",
        )

        // Attempting to record a plugin provider mapping for a host provider suffix must be refused
        engine.recordProviderRegistration("pluginA::boss-workspace")
        assertEquals(emptySet(), engine.providerCandidates("boss-workspace"))

        // Reconcile must not touch or retire host provider rule
        val outcome = engine.reconcileLegacyEntries()
        assertEquals(McpProactivePolicyOutcome.Saved, outcome)
        assertEquals(McpPolicyAction.ALLOW, engine.config.value.providerRules["boss-workspace"])
    }

    @Test
    fun `computeLegacyReconciliation never weakens existing scoped rule`() {
        val initialConfig =
            McpToolPolicyConfig(
                providerRules =
                    mapOf(
                        "tool-deny" to McpPolicyAction.ALLOW,
                        "pluginA::tool-deny" to McpPolicyAction.DENY,
                        "tool-ask" to McpPolicyAction.ALLOW,
                        "pluginA::tool-ask" to McpPolicyAction.ASK,
                        "tool-upgrade-deny" to McpPolicyAction.DENY,
                        "pluginA::tool-upgrade-deny" to McpPolicyAction.ALLOW,
                        "tool-fill-unset" to McpPolicyAction.ALLOW,
                    ),
                providerMapping =
                    mapOf(
                        "tool-deny" to setOf("pluginA::tool-deny"),
                        "tool-ask" to setOf("pluginA::tool-ask"),
                        "tool-upgrade-deny" to setOf("pluginA::tool-upgrade-deny"),
                        "tool-fill-unset" to setOf("pluginA::tool-fill-unset"),
                    ),
            )
        val policyFile = createTempPolicyFile(initialConfig)
        val engine =
            McpPolicyEngine(
                policyFile = policyFile,
                getInstalledPluginIds = { setOf("pluginA") },
            )

        val outcome = engine.reconcileLegacyEntries()
        assertEquals(McpProactivePolicyOutcome.Saved, outcome)

        val rules = engine.config.value.providerRules
        // Pre-existing scoped DENY must NOT be overwritten by legacy ALLOW
        assertEquals(McpPolicyAction.DENY, rules["pluginA::tool-deny"])
        // Pre-existing scoped ASK must NOT be overwritten by legacy ALLOW
        assertEquals(McpPolicyAction.ASK, rules["pluginA::tool-ask"])
        // Pre-existing scoped ALLOW must be upgraded to DENY by legacy DENY
        assertEquals(McpPolicyAction.DENY, rules["pluginA::tool-upgrade-deny"])
        // Unset scoped rule must be filled by legacy ALLOW
        assertEquals(McpPolicyAction.ALLOW, rules["pluginA::tool-fill-unset"])

        // Legacy raw keys should all be retired
        assertTrue("tool-deny" !in rules)
        assertTrue("tool-ask" !in rules)
        assertTrue("tool-upgrade-deny" !in rules)
        assertTrue("tool-fill-unset" !in rules)
    }

    @Test
    fun `matchingLegacyRuleFor correctly gates unresolved legacy rule banner`() {
        val unresolved =
            mapOf(
                "my-provider" to McpPolicyAction.ALLOW,
                "danger-provider" to McpPolicyAction.DENY,
            )

        // Matching namespaced provider
        assertEquals(
            McpPolicyAction.ALLOW,
            matchingLegacyRuleFor("pluginA::my-provider", unresolved),
        )
        assertEquals(
            McpPolicyAction.DENY,
            matchingLegacyRuleFor("pluginA::danger-provider", unresolved),
        )

        // Matching raw provider
        assertEquals(
            McpPolicyAction.ALLOW,
            matchingLegacyRuleFor("my-provider", unresolved),
        )

        // Non-matching provider returns null (banner suppressed)
        assertNull(
            matchingLegacyRuleFor("pluginA::unknown-provider", unresolved),
        )

        // Host provider not in unresolved returns null
        assertNull(
            matchingLegacyRuleFor("boss-workspace", unresolved),
        )
    }
}
