package ai.rever.boss.mcp

import ai.rever.boss.utils.atomicWriteText
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
    fun `two plugins claiming the same old ID - ambiguous ALLOW never authorizes either plugin and legacy DENY stays effective`() {
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

        // Key assertion BEFORE reconcile: ambiguous ALLOW never authorizes either plugin
        assertNotEquals(
            McpPolicyAction.ALLOW,
            engine.policyFor("mutating_action", "pluginA::shared-tools", declaredReadOnly = false),
            "Ambiguous ALLOW must never authorize plugin A",
        )
        assertNotEquals(
            McpPolicyAction.ALLOW,
            engine.policyFor("mutating_action", "pluginB::shared-tools", declaredReadOnly = false),
            "Ambiguous ALLOW must never authorize plugin B",
        )

        // Legacy DENY is effective against both candidates
        assertEquals(
            McpPolicyAction.DENY,
            engine.policyFor("any_tool", "pluginA::shared-danger"),
            "Legacy DENY must apply to candidate plugin A",
        )
        assertEquals(
            McpPolicyAction.DENY,
            engine.policyFor("any_tool", "pluginB::shared-danger"),
            "Legacy DENY must apply to candidate plugin B",
        )

        // Attempt reconciliation
        val outcome = engine.reconcileLegacyEntries()
        assertEquals(McpProactivePolicyOutcome.Saved, outcome)

        // Ambiguous ALLOW must NOT be retired and must remain visible for review
        val unresolved = engine.unresolvedLegacyRules()
        assertTrue("shared-tools" in unresolved, "Ambiguous ALLOW must remain unresolved for review")
        assertTrue("shared-danger" in unresolved, "Ambiguous DENY must remain unresolved so it cannot disappear")

        // Key assertion AFTER reconcile: ambiguous ALLOW still never authorizes either plugin
        assertNotEquals(
            McpPolicyAction.ALLOW,
            engine.policyFor("mutating_action", "pluginA::shared-tools", declaredReadOnly = false),
            "Ambiguous ALLOW must still never authorize plugin A after reconcile",
        )
        assertNotEquals(
            McpPolicyAction.ALLOW,
            engine.policyFor("mutating_action", "pluginB::shared-tools", declaredReadOnly = false),
            "Ambiguous ALLOW must still never authorize plugin B after reconcile",
        )

        // Legacy DENY must still be effective against both candidates
        assertEquals(
            McpPolicyAction.DENY,
            engine.policyFor("any_tool", "pluginA::shared-danger"),
            "Legacy DENY must still apply to candidate plugin A after reconcile",
        )
        assertEquals(
            McpPolicyAction.DENY,
            engine.policyFor("any_tool", "pluginB::shared-danger"),
            "Legacy DENY must still apply to candidate plugin B after reconcile",
        )
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
                providerRules = engine.config.value.providerRules + ("pluginA::restricted-tools" to McpPolicyAction.ALLOW),
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
        // Create an unwritable policy file path (pointing to an impossible directory on Linux)
        val unwritableFile = File("/proc/boss-nonexistent-path/mcp-tool-policy.json")

        val engine =
            McpPolicyEngine(
                policyFile = unwritableFile,
                onFault = { notifiedFault = it },
                getInstalledPluginIds = { setOf("pluginA") },
            )
        // Manually seed config state with legacy rule for testing persistence failure:
        val field = McpPolicyEngine::class.java.getDeclaredField("_config")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val stateFlow = field.get(engine) as kotlinx.coroutines.flow.MutableStateFlow<McpToolPolicyConfig>
        stateFlow.value = initialConfig

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
    fun `key assertion - ambiguous ALLOW never authorizes either plugin and legacy DENY never disappears during migration`() {
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

        // 1. Ambiguous ALLOW check
        assertNotEquals(
            McpPolicyAction.ALLOW,
            engine.policyFor("exec", "plugin1::ambiguous-allow", declaredReadOnly = false),
            "Ambiguous ALLOW must never authorize plugin 1",
        )
        assertNotEquals(
            McpPolicyAction.ALLOW,
            engine.policyFor("exec", "plugin2::ambiguous-allow", declaredReadOnly = false),
            "Ambiguous ALLOW must never authorize plugin 2",
        )

        // 2. Legacy DENY check
        assertEquals(
            McpPolicyAction.DENY,
            engine.policyFor("exec", "plugin1::ambiguous-deny"),
            "Legacy DENY must apply to plugin 1",
        )
        assertEquals(
            McpPolicyAction.DENY,
            engine.policyFor("exec", "plugin2::ambiguous-deny"),
            "Legacy DENY must apply to plugin 2",
        )

        // 3. Migration attempt
        engine.reconcileLegacyEntries()

        // 4. Assert ambiguous ALLOW still never authorizes either plugin
        assertNotEquals(
            McpPolicyAction.ALLOW,
            engine.policyFor("exec", "plugin1::ambiguous-allow", declaredReadOnly = false),
            "Ambiguous ALLOW must still never authorize plugin 1 after migration",
        )
        assertNotEquals(
            McpPolicyAction.ALLOW,
            engine.policyFor("exec", "plugin2::ambiguous-allow", declaredReadOnly = false),
            "Ambiguous ALLOW must still never authorize plugin 2 after migration",
        )

        // 5. Assert legacy DENY never disappears during or after migration
        assertEquals(
            McpPolicyAction.DENY,
            engine.policyFor("exec", "plugin1::ambiguous-deny"),
            "Legacy DENY must still apply to plugin 1 after migration",
        )
        assertEquals(
            McpPolicyAction.DENY,
            engine.policyFor("exec", "plugin2::ambiguous-deny"),
            "Legacy DENY must still apply to plugin 2 after migration",
        )
    }
}
