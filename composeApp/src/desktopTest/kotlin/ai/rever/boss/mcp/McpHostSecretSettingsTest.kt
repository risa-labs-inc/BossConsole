package ai.rever.boss.mcp

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The host's three secret-reference switches as the "Secret references" dialog saves them: one
 * durable write through the engine's own lock, refused rather than written over a change the
 * operator never saw or a policy file the engine could not read, and recorded in the ledger as a
 * governance event. What a switch flipped mid-prompt does to that call is in
 * `SecretReferenceInvariantTest` (INV4), beside the other fences.
 */
class McpHostSecretSettingsTest {
    private val dirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() = dirs.forEach { it.deleteRecursively() }

    private fun policyFile(): File =
        File(createTempDirectory("mcp-host-secret-settings").toFile().also { dirs += it }, "mcp-tool-policy.json")

    private val defaults = McpToolPolicyConfig().hostSecretSettings
    private val locked = McpHostSecretSettings(false, McpSecretPolicyAction.DENY, true)

    @Test
    fun `a change is saved, applied at once, and survives a restart with every rule untouched`() {
        val file = policyFile()
        val engine = McpPolicyEngine(policyFile = file)
        assertTrue(engine.setToolPolicy("write", McpPolicyAction.DENY))
        engine.setProviderPolicy("p1", McpPolicyAction.ALLOW)

        assertEquals(McpProactivePolicyOutcome.Saved, engine.setHostSecretSettings(defaults, locked))

        assertEquals(locked, engine.config.value.hostSecretSettings)
        val restarted = McpPolicyEngine(policyFile = file)
        assertEquals(locked, restarted.config.value.hostSecretSettings)
        assertEquals(mapOf("write" to McpPolicyAction.DENY), restarted.config.value.rules)
        assertEquals(mapOf("p1" to McpPolicyAction.ALLOW), restarted.config.value.providerRules)
    }

    @Test
    fun `a change saved meanwhile refuses a stale one instead of writing over it`() {
        val engine = McpPolicyEngine(policyFile = policyFile())
        // Another window's dialog turned scrubbing off after this one opened.
        val scrubOff = defaults.copy(resultScrubbingEnabled = false)
        assertEquals(McpProactivePolicyOutcome.Saved, engine.setHostSecretSettings(defaults, scrubOff))

        // This dialog still shows the defaults; saving from them must not quietly put scrubbing back.
        val outcome = engine.setHostSecretSettings(defaults, defaults.copy(referencesEnabled = false))

        assertEquals(McpProactivePolicyOutcome.Refused, outcome)
        assertEquals(scrubOff, engine.config.value.hostSecretSettings)
    }

    @Test
    fun `an unreadable policy file refuses the write and is left exactly as it was`() {
        val file = policyFile().apply { writeText("{ not json") }
        val engine = McpPolicyEngine(policyFile = file)
        assertIs<McpPolicyFault.PersistedPolicyUnreadable>(engine.fault.value)

        val outcome = engine.setHostSecretSettings(engine.config.value.hostSecretSettings, locked)

        assertEquals(McpProactivePolicyOutcome.PolicyUnreadable, outcome)
        // Writing the fail-closed defaults out would have replaced the operator's file.
        assertEquals("{ not json", file.readText())
        assertIs<McpPolicyFault.PersistedPolicyUnreadable>(engine.fault.value)
    }

    @Test
    fun `unchanged settings write nothing`() {
        val file = policyFile()
        val engine = McpPolicyEngine(policyFile = file)

        assertEquals(McpProactivePolicyOutcome.Saved, engine.setHostSecretSettings(defaults, defaults))

        assertTrue(!file.exists(), "a no-op save wrote the policy file")
    }

    @Test
    fun `a write that cannot land is reported and changes nothing`() {
        // The policy file's parent is a regular file, so the atomic write cannot create it.
        val blocker = policyFile().apply { writeText("") }
        val engine = McpPolicyEngine(policyFile = File(blocker, "mcp-tool-policy.json"))

        val outcome = engine.setHostSecretSettings(defaults, locked)

        assertIs<McpProactivePolicyOutcome.Failed>(outcome)
        assertEquals(defaults, engine.config.value.hostSecretSettings)
        assertIs<McpPolicyFault.PolicyPersistFailed>(engine.fault.value)
    }

    @Test
    fun `a saved change writes one governance marker naming every switch and what changed`() =
        runBlocking<Unit> {
            val engine = McpPolicyEngine(policyFile = policyFile())
            val ledger = McpOperationLedger(ledgerFile = null)

            changeHostSecretSettings(engine, ledger, defaults, locked)

            val marker = ledger.recentOperations.value.single()
            assertEquals(McpHostSecretSettings.LEDGER_TOOL_NAME, marker.toolName)
            assertEquals(McpHostSecretSettings.LEDGER_PROVIDER_ID, marker.providerId)
            assertEquals(McpApprovalDisposition.HOST_SECRET_SETTINGS_CHANGED, marker.approvalDisposition)
            assertTrue(marker.approvalDisposition.isGovernanceEvent)
            // Plain words, none of them a key the sanitizer would redact.
            assertEquals(
                mapOf(
                    "references" to "off",
                    "bearing_calls" to "DENY",
                    "result_scrubbing" to "on",
                    "changed" to "references,bearing_calls",
                ),
                marker.sanitizedArgs,
            )
            // Not a tool call: the activity log's counters do not move.
            assertEquals(0L, ledger.totalCalls.value)
        }

    @Test
    fun `a refused, unreadable or unchanged save writes no marker`() =
        runBlocking<Unit> {
            val ledger = McpOperationLedger(ledgerFile = null)
            val engine = McpPolicyEngine(policyFile = policyFile())
            val stale = defaults.copy(resultScrubbingEnabled = false)
            assertEquals(McpProactivePolicyOutcome.Refused, changeHostSecretSettings(engine, ledger, stale, locked))
            assertEquals(McpProactivePolicyOutcome.Saved, changeHostSecretSettings(engine, ledger, defaults, defaults))

            val unreadable = McpPolicyEngine(policyFile = policyFile().apply { writeText("{") })
            assertEquals(
                McpProactivePolicyOutcome.PolicyUnreadable,
                changeHostSecretSettings(unreadable, ledger, unreadable.config.value.hostSecretSettings, locked),
            )

            assertEquals(emptyList(), ledger.recentOperations.value)
        }
}
