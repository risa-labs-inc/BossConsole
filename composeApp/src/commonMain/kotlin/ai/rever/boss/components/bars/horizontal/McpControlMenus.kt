package ai.rever.boss.components.bars.horizontal

import ai.rever.boss.components.dialogs.McpActivityLogDialog
import ai.rever.boss.components.dialogs.McpPolicyManagerDialog
import ai.rever.boss.components.dialogs.McpProviderTrustDialog
import ai.rever.boss.components.dialogs.McpSessionTrustDialog
import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.mcp.McpPolicyAction
import ai.rever.boss.mcp.McpToolPolicyConfig
import ai.rever.boss.mcp.McpToolRegistryImpl
import ai.rever.boss.mcp.McpYoloPrompt
import ai.rever.boss.window.LocalWindowId
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared permission dialogs and actions for the title-bar menu and bottom-bar fallback. */
@Composable
@Suppress("LongMethod") // Declarative Compose layout.
internal fun rememberMcpAccessMenu(persistedPolicyConfig: McpToolPolicyConfig): McpAccessMenu {
    val allTools by McpToolRegistryImpl.allTools.collectAsState()
    val sessionTrusted by McpToolRegistryImpl.policyEngine.sessionTrustedTools.collectAsState()
    var showPolicyManager by remember { mutableStateOf(false) }
    var showTrustedPlugins by remember { mutableStateOf(false) }
    var showSessionTrust by remember { mutableStateOf(false) }
    val yolo by McpToolRegistryImpl.yoloMode.collectAsState()
    val windowId = LocalWindowId.current
    val scope = rememberCoroutineScope()
    val summary =
        McpAccessSummary(
            savedRules = persistedPolicyConfig.rules.size,
            trustedPlugins = persistedPolicyConfig.providerRules.count { it.value == McpPolicyAction.ALLOW },
            sessionGrants = sessionTrusted.size,
            yolo = yolo,
            yoloAvailable = McpToolRegistryImpl.yoloAvailable,
        )
    if (showSessionTrust) {
        McpSessionTrustDialog(
            trusted = sessionTrusted,
            // The exact (provider, tool) pair: a same-named tool from another provider keeps its
            // own grant. In-memory only, so there is no disk write to move off the UI thread.
            onRevoke = { McpToolRegistryImpl.policyEngine.revokeSessionTrust(it.toolName, it.providerId) },
            onRevokeAll = { McpToolRegistryImpl.policyEngine.clearSessionTrusts() },
            onDismiss = { showSessionTrust = false },
        )
    }
    if (showTrustedPlugins) {
        McpProviderTrustDialog(
            providerRules = persistedPolicyConfig.providerRules,
            // Dispatchers.IO: revokeProviderPolicy performs the same synchronized atomicWriteText
            // disk write as revokePersistedPolicy, off the UI thread for the same reason.
            onRevoke = { providerId ->
                withContext(Dispatchers.IO) {
                    McpToolRegistryImpl.policyEngine.revokeProviderPolicy(providerId)
                }
            },
            onDismiss = { showTrustedPlugins = false },
        )
    }
    if (showPolicyManager) {
        val disabledToolNames by McpToolRegistryImpl.disabledToolNames.collectAsState()
        var candidateRefresh by remember { mutableStateOf(0) }
        val availableTools =
            remember(allTools, persistedPolicyConfig.rules, disabledToolNames, candidateRefresh) {
                mcpProactivePolicyCandidates(
                    allTools,
                    persistedPolicyConfig.rules,
                    disabledToolNames,
                    McpToolRegistryImpl.policyEngine::revocationVersion,
                )
            }
        McpPolicyManagerDialog(
            rules = persistedPolicyConfig.rules,
            availableTools = availableTools,
            // Dispatchers.IO: revokePersistedPolicy and setToolPolicyIfAbsent both do a
            // synchronized atomicWriteText disk write - this call site was the one still running
            // it on the UI thread, where a click could block behind another write holding the
            // same lock from a slow, networked or AV-scanned home directory.
            onRevoke = { toolName ->
                withContext(Dispatchers.IO) {
                    McpToolRegistryImpl.policyEngine.revokePersistedPolicy(toolName)
                }
            },
            // setToolPolicyIfAbsent, not setToolPolicy: this path must add a rule only while the
            // tool still has none of its own, atomically re-checked at write time - not just
            // refuse a DENY, and not only when the revocation counter moved. An intervening
            // explicit ASK or ALLOW, made through the reactive approval dialog for this same tool
            // between "this row was offered" and this click reaching disk, never bumps that
            // counter, so a `preserveDeny`-style guard alone would let this write silently
            // clobber it (review on #636). tool.expectedRevocation is still passed, and still
            // checked first, to catch a DENY or provider-wide reset the same way the reactive
            // path's own capture-then-recheck does.
            onSetPolicy = ::saveProactiveToolPolicy,
            onRefreshCandidates = { candidateRefresh++ },
            onDismiss = { showPolicyManager = false },
            sectionTools =
                remember(allTools, persistedPolicyConfig.rules, disabledToolNames, candidateRefresh) {
                    mcpProactivePolicyCandidates(
                        allTools,
                        emptyMap(),
                        disabledToolNames,
                        McpToolRegistryImpl.policyEngine::revocationVersion,
                    )
                },
            onApplySection = { changes ->
                withContext(Dispatchers.IO) { McpToolRegistryImpl.policyEngine.setSectionPolicies(changes) }
            },
        )
    }
    return McpAccessMenu(
        summary = summary,
        visible = summary.isVisible(allTools.isNotEmpty()),
        items =
            mcpAccessMenuItems(
                summary = summary,
                onPolicies = { showPolicyManager = true },
                onSessionTrust = { showSessionTrust = true },
                onTrustedPlugins = { showTrustedPlugins = true },
                onYolo = {
                    if (yolo) {
                        scope.launch { McpToolRegistryImpl.setYoloMode(false) }
                    } else {
                        windowId?.let(McpYoloPrompt::request)
                    }
                },
            ),
    )
}

internal data class McpAccessMenu(
    val summary: McpAccessSummary,
    val visible: Boolean,
    val items: List<ContextMenuItem>,
)

/** Keep the activity log reachable before the first call as well as after it. */
@Composable
internal fun rememberMcpActivityItem(): ContextMenuItem {
    val recentOps by McpToolRegistryImpl.ledger.recentOperations.collectAsState()
    var showActivityLog by remember { mutableStateOf(false) }
    // The most recent CALL: a YOLO on/off marker is in the ledger for audit but is not a call.
    val lastOp = recentOps.firstOrNull { !it.approvalDisposition.isGovernanceEvent }
    val statusText =
        if (lastOp != null) {
            "MCP: ${lastOp.toolName} (${formatMcpDuration(lastOp.durationMs)}) ${if (lastOp.isError) "✕" else "✓"}"
        } else {
            "MCP: no activity yet"
        }
    if (showActivityLog) {
        val totalCalls by McpToolRegistryImpl.ledger.totalCalls.collectAsState()
        val totalErrors by McpToolRegistryImpl.ledger.totalErrors.collectAsState()
        val pendingWriteIds by McpToolRegistryImpl.ledger.pendingWriteIds.collectAsState()
        val droppedWrites by McpToolRegistryImpl.ledger.droppedWrites.collectAsState()
        McpActivityLogDialog(
            operations = recentOps,
            totalCalls = totalCalls,
            totalErrors = totalErrors,
            ledgerPath = McpToolRegistryImpl.ledger.persistencePath,
            pendingWriteIds = pendingWriteIds,
            droppedWrites = droppedWrites,
            onDismiss = { showActivityLog = false },
        )
    }
    return ContextMenuItem(text = statusText, onClick = { showActivityLog = true })
}
