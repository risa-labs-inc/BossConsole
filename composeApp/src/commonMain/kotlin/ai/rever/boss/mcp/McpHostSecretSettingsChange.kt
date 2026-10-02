package ai.rever.boss.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Save the host's secret-reference switches and record the change in the ledger, the way
 * `setYoloMode` records its switch: a governance marker ([McpApprovalDisposition.HOST_SECRET_SETTINGS_CHANGED],
 * tool [McpHostSecretSettings.LEDGER_TOOL_NAME], `countsAsCall = false`) carrying every switch's
 * new value and which ones changed. So the hash-chained audit trail shows when delivery was
 * switched off or on, and when scrubbing was given up, even if no call ran in between.
 *
 * Only a saved change writes a marker: a refused write, a failed one and a no-op write nothing,
 * so the ledger holds one marker per real change. The engine write and the marker both run on
 * [Dispatchers.IO] (both are synchronous file I/O); the marker under [NonCancellable], so a
 * dialog closed mid-save cannot leave a change on disk with no record of it.
 *
 * File scope rather than a member of `McpToolRegistryCore`, which is at detekt's LargeClass
 * ceiling; the facade hands it the engine and ledger it already owns.
 */
internal suspend fun changeHostSecretSettings(
    policyEngine: McpPolicyEngine,
    ledger: McpOperationLedger,
    expected: McpHostSecretSettings,
    updated: McpHostSecretSettings,
): McpProactivePolicyOutcome {
    val outcome = withContext(Dispatchers.IO) { policyEngine.setHostSecretSettings(expected, updated) }
    if (outcome is McpProactivePolicyOutcome.Saved && updated != expected) {
        withContext(NonCancellable + Dispatchers.IO) {
            ledger.record(
                toolName = McpHostSecretSettings.LEDGER_TOOL_NAME,
                providerId = McpHostSecretSettings.LEDGER_PROVIDER_ID,
                policyApplied = McpPolicyAction.ASK,
                approvalDisposition = McpApprovalDisposition.HOST_SECRET_SETTINGS_CHANGED,
                durationMs = 0L,
                isError = false,
                rawArgs = updated.ledgerArgs(before = expected),
                countsAsCall = false,
            )
        }
    }
    return outcome
}
