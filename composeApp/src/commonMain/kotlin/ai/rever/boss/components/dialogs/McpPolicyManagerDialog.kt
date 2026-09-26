package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpMutatingToolCatalog
import ai.rever.boss.mcp.McpPolicyAction
import ai.rever.boss.mcp.McpProactivePolicyOutcome
import ai.rever.boss.mcp.McpSectionPolicyChange
import ai.rever.boss.mcp.McpToolPolicyConfig
import ai.rever.boss.mcp.sandbox.DefaultMcpRiskEvaluator
import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.ui.BossColorScheme
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Divider
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.RadioButtonDefaults
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/**
 * Lets an operator see and undo every persistent MCP tool policy rule saved from the approval
 * dialog's "Always Allow"/"Always Deny" actions ([McpApprovalDialog]) - the inspection and
 * revocation path this repo's own AGENTS.md names as reachable only by hand-editing
 * `~/.boss/mcp-tool-policy.json` and restarting, until this dialog existed.
 *
 * It also lets an operator set a rule *proactively*, for a tool nothing has ever asked about yet.
 * Before this, the only way a persistent rule came to exist was reactive: a plugin had to actually
 * invoke the tool and trigger the approval dialog, and only then could "Always Allow"/"Always
 * Deny" be clicked. An operator who already knows they never want a given tool run - `k8s_delete`,
 * say - had no way to say so ahead of time; they could only wait to be asked, or hand-edit the
 * policy file this dialog exists to make unnecessary. [availableTools] is every registered tool
 * that does not already have a rule in [rules], and [onSetPolicy] writes one through
 * [ai.rever.boss.mcp.McpPolicyEngine.setToolPolicyIfAbsent] - a dedicated add-only-if-absent
 * write, not the reactive approval path's own [ai.rever.boss.mcp.McpPolicyEngine.setToolPolicy].
 * The two calls answer different questions: the reactive path replaces whatever rule a tool has
 * when an operator explicitly clicks "Always Allow"/"Always Deny" *for that tool*, while this
 * path must never replace a rule that already exists, however it got there - including one an
 * explicit reactive decision wrote in the gap between this row being offered as a candidate and
 * the operator's click here reaching disk (review on #636).
 *
 * [rules] is a snapshot the caller re-derives from [ai.rever.boss.mcp.McpPolicyEngine.config] on
 * every recomposition, not a copy this dialog owns - a revoke calls back into [onRevoke] and
 * waits for the same state flow to reflect it, rather than mutating a local list that could drift
 * from what is actually on disk.
 */
@Composable
@Suppress("LongMethod") // Declarative Compose layout.
fun McpPolicyManagerDialog(
    policy: McpToolPolicyConfig,
    availableTools: List<McpToolIdentity>,
    onRevoke: suspend (rule: McpSavedRule) -> Boolean,
    onSetPolicy: suspend (tool: McpToolIdentity, action: McpPolicyAction) -> McpProactivePolicyOutcome,
    onRefreshCandidates: () -> Unit,
    onDismiss: () -> Unit,
    sectionTools: List<McpToolIdentity>? = null,
    onApplySection: (suspend (List<McpSectionPolicyChange>) -> McpProactivePolicyOutcome)? = null,
) {
    val windowSize = LocalWindowInfo.current.containerSize
    val windowHeight = with(LocalDensity.current) { windowSize.height.toDp() }
    val windowWidth = with(LocalDensity.current) { windowSize.width.toDp() }
    val maxHeight = if (windowHeight > 0.dp) (windowHeight - 32.dp).coerceAtLeast(1.dp) else 700.dp
    val maxWidth = if (windowWidth > 0.dp) (windowWidth - 32.dp).coerceAtLeast(1.dp) else 480.dp
    val colors = BossTheme.colors
    val radii = BossTheme.radius
    val scope = rememberCoroutineScope()
    // Which tool's revoke most recently failed to persist. A single slot, not a set: a
    // SUCCESSFUL revoke of any tool clears it, not only a retry of the same one, so two failures
    // in quick succession show only the most recent - accepted, since this is diagnostic rather
    // than an audit trail (the host log has that).
    var failedRevoke by remember { mutableStateOf<McpSavedRule?>(null) }
    // A DENY row asked to confirm once before it resets: resetting an ALLOW reduces standing
    // privilege, but resetting a DENY raises it, removing the one rule that beats session trust
    // (McpPolicyEngine.policyFor). Tracks at most one row at a time - switching to a different
    // row's button, or dismissing, drops any pending confirmation rather than carrying it silently.
    var confirmingDeny by remember { mutableStateOf<McpSavedRule?>(null) }
    var query by remember { mutableStateOf("") }
    val pluginNames = mcpPolicyPluginNames()
    val savedRules = policy.savedRules()
    val filteredRules = filterSavedPolicies(savedRules, sectionTools, query, pluginNames, availableTools)
    val filteredTools =
        availableTools.filter {
            it.matchesPolicyQuery(query)
        }

    BossDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(),
    ) {
        Surface(
            modifier = Modifier.widthIn(max = maxWidth).width(600.dp).heightIn(max = maxHeight),
            shape = RoundedCornerShape(radii.dialog),
            color = colors.panel,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Text(
                        text = "MCP tool policies",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Manage how tools request permission. Saved rules apply across agents and restarts.",
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = {
                            query = it
                            confirmingDeny = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("Find a tool or provider", fontSize = 12.sp) },
                        shape = RoundedCornerShape(8.dp),
                        colors =
                            TextFieldDefaults.outlinedTextFieldColors(
                                textColor = colors.textPrimary,
                                cursorColor = colors.signal,
                                focusedBorderColor = colors.signal,
                                unfocusedBorderColor = colors.textSecondary.copy(alpha = 0.25f),
                                placeholderColor = colors.textSecondary,
                                backgroundColor = colors.textSecondary.copy(alpha = 0.04f),
                            ),
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = policyToolsHeading(sectionTools, availableTools),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = policyToolsDescription(sectionTools),
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    PolicyToolContent(
                        sectionTools,
                        policy,
                        query,
                        onApplySection,
                        onRefreshCandidates,
                        filteredTools,
                        availableTools.isNotEmpty(),
                        onSetPolicy,
                        colors,
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "Saved rules · ${savedRules.size}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Reset removes a saved rule. Default policy and session trust then apply.",
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // The surrounding body scrolls as one region; Close stays outside it.
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth(),
                    ) {
                        if (filteredRules.isEmpty()) {
                            Text(
                                text = emptyRulesMessage(savedRules.isEmpty()),
                                fontSize = 13.sp,
                                color = colors.textSecondary,
                            )
                        } else {
                            filteredRules.forEach { rule ->
                                val (toolName, action) = rule.toolName to rule.action
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp)
                                            .background(
                                                color = colors.textSecondary.copy(alpha = 0.04f),
                                                shape = RoundedCornerShape(8.dp),
                                            ).padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = toolName,
                                            fontSize = 13.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = colors.textPrimary,
                                        )
                                        Text(
                                            text = savedRuleScopeLabel(rule, pluginNames),
                                            fontSize = 11.sp,
                                            color = colors.textSecondary,
                                        )
                                        Text(
                                            text = action.name,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            // Both values represent a durable, disk-persisted rule -
                                            // the same category McpApprovalDialog's "Always Allow"/
                                            // "Always Deny" buttons are, which use warn/alert rather
                                            // than an ordinary success color.
                                            color = if (action == McpPolicyAction.DENY) colors.alert else colors.warn,
                                        )
                                        if (failedRevoke == rule) {
                                            Text(
                                                // Session trust clears even on failure, but a saved ALLOW still
                                                // permits calls. Do not promise ASK while that durable rule remains.
                                                text =
                                                    "Saved rule unchanged; this tool's session trust was cleared. " +
                                                        "See the host log.",
                                                fontSize = 11.sp,
                                                color = colors.alert,
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))

                                    fun revoke() {
                                        confirmingDeny = null
                                        scope.launch {
                                            failedRevoke = if (onRevoke(rule)) null else rule
                                        }
                                    }
                                    // Removing an ALLOW is a de-escalation and needs no confirmation.
                                    // Removing a DENY is the one control in this dialog that INCREASES
                                    // what the tool is allowed to do, so it gets a second tap instead of
                                    // firing on the first click like every other row's button does.
                                    if (action == McpPolicyAction.DENY && confirmingDeny != rule) {
                                        TextButton(onClick = { confirmingDeny = rule }) {
                                            Text("Remove denial", fontSize = 12.sp, color = colors.alert)
                                        }
                                    } else if (action == McpPolicyAction.DENY) {
                                        TextButton(onClick = { revoke() }) {
                                            Text("Confirm remove?", fontSize = 12.sp, color = colors.alert)
                                        }
                                    } else {
                                        TextButton(onClick = { revoke() }) {
                                            Text("Reset", fontSize = 12.sp, color = colors.signal)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Divider(color = colors.textSecondary.copy(alpha = 0.15f))
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(backgroundColor = colors.signal),
                    ) {
                        Text("Close", color = colors.onSignal, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private fun emptyRulesMessage(noSavedRules: Boolean): String =
    if (noSavedRules) "No saved rules. Default policies and session trust still apply." else "No matching saved rules."

private fun McpToolIdentity.matchesPolicyQuery(query: String): Boolean =
    toolName.contains(query.trim(), ignoreCase = true) ||
        providerId.contains(query.trim(), ignoreCase = true) ||
        description.contains(query.trim(), ignoreCase = true)

@Composable
private fun FilteredPolicyCandidates(
    tools: List<McpToolIdentity>,
    hasAvailableTools: Boolean,
    onSetPolicy: suspend (McpToolIdentity, McpPolicyAction) -> McpProactivePolicyOutcome,
    onRefreshCandidates: () -> Unit,
    colors: BossColorScheme,
) {
    if (tools.isEmpty() && hasAvailableTools) {
        Text("No matching tools.", fontSize = 12.sp, color = colors.textSecondary)
    } else {
        ProactivePolicySectionContent(tools, onSetPolicy, onRefreshCandidates, colors)
    }
}

/**
 * A tool the policy engine can name a rule for - just enough identity for this dialog.
 *
 * [expectedRevocation] is [ai.rever.boss.mcp.McpPolicyEngine.revocationVersion] for
 * ([toolName], [providerId]) captured when this tool was offered as a proactive candidate - the
 * same generation-token pattern the reactive approval path uses (`McpToolRegistryImpl`'s own
 * `revocation` captured at invocation time), so a DENY or reset landing at either scope between
 * "this row was offered" and the write actually happening is caught by
 * [ai.rever.boss.mcp.McpPolicyEngine.setToolPolicyIfAbsent]'s own lock-protected recheck rather
 * than silently overwritten. That recheck also refuses outright if [toolName] has picked up *any*
 * rule since - not only a DENY, and not only through a revoke: an explicit ASK or ALLOW made
 * through the reactive approval dialog in the meantime never touches [expectedRevocation], so the
 * add-only-if-absent check is what actually protects it (review on #636).
 */
data class McpToolIdentity(
    val toolName: String,
    val providerId: String,
    val expectedRevocation: Long,
    val description: String = "",
    val readOnly: Boolean = false,
)

/**
 * [availableTools] is already filtered and sorted by the caller
 * ([ai.rever.boss.components.bars.horizontal.mcpProactivePolicyCandidates]); this renders it with
 * no scroll or height bound of its own so it shares the one outer scroll region
 * [McpPolicyManagerDialog] wraps both sections in.
 */
@Composable
private fun ProactivePolicySectionContent(
    availableTools: List<McpToolIdentity>,
    onSetPolicy: suspend (tool: McpToolIdentity, action: McpPolicyAction) -> McpProactivePolicyOutcome,
    onRefreshCandidates: () -> Unit,
    colors: BossColorScheme,
) {
    val scope = rememberCoroutineScope()
    // Like revoke feedback, retain only the latest attempted row outcome.
    var failedSet by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Allow raises standing privilege strictly further than anything else in this dialog can -
    // Default policy into unattended ALLOW for every future agent and argument set - so it gets the
    // same second-tap confirmation removing a DENY gets above, plus the risk/scope context
    // McpApprovalDialog shows before its own "Always Allow" (review on #636). Selecting either
    // radio option only stages it; saving is a separate explicit action.
    var pending by remember(availableTools) { mutableStateOf<Pair<McpToolIdentity, McpPolicyAction>?>(null) }

    if (availableTools.isEmpty()) {
        Text(
            text = "Every registered tool already has a rule, or is disabled.",
            fontSize = 12.sp,
            color = colors.textSecondary,
        )
        return
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        availableTools.groupBy { it.providerId }.forEach { (provider, tools) ->
            Text(
                text = "$provider · ${tools.size} tools",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 8.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            tools.forEach { tool ->
                ProactivePolicyRow(
                    tool = tool,
                    selected = pending?.takeIf { it.first == tool }?.second,
                    failureMessage = failedSet?.takeIf { it.first == tool.toolName }?.second,
                    onSelect = { pending = tool to it },
                    onSave = { action ->
                        pending = null
                        scope.launch {
                            val outcome = onSetPolicy(tool, action)
                            failedSet = outcome.proactivePolicyMessage()?.let { tool.toolName to it }
                            if (outcome is McpProactivePolicyOutcome.Refused) onRefreshCandidates()
                        }
                    },
                    colors = colors,
                )
            }
        }
    }
}

@Composable
private fun ProactivePolicyRow(
    tool: McpToolIdentity,
    selected: McpPolicyAction?,
    failureMessage: String?,
    onSelect: (McpPolicyAction) -> Unit,
    onSave: (McpPolicyAction) -> Unit,
    colors: BossColorScheme,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = colors.textSecondary.copy(alpha = 0.035f),
        border = BorderStroke(1.dp, colors.textSecondary.copy(alpha = 0.14f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = tool.toolName,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
            Text(
                text = tool.description.ifBlank { "No description provided by this tool." },
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = colors.textSecondary,
            )
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PolicyChoice("Allow", selected == McpPolicyAction.ALLOW, { onSelect(McpPolicyAction.ALLOW) }, colors)
                PolicyChoice("Deny", selected == McpPolicyAction.DENY, { onSelect(McpPolicyAction.DENY) }, colors)
            }
            if (selected == McpPolicyAction.ALLOW) {
                ProactiveAllowConfirmation(tool.toolName, tool.readOnly, colors)
            }
            if (failureMessage != null) {
                Text(failureMessage, fontSize = 12.sp, color = colors.alert)
            }
            selected?.let { action ->
                Button(
                    onClick = { onSave(action) },
                    colors =
                        ButtonDefaults.buttonColors(
                            backgroundColor = colors.signal,
                            contentColor = colors.onSignal,
                        ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(if (action == McpPolicyAction.ALLOW) "Confirm allow?" else "Save rule", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun PolicyChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    colors: BossColorScheme,
) {
    Row(
        modifier =
            Modifier
                .background(
                    if (selected) colors.signal.copy(alpha = 0.10f) else colors.textSecondary.copy(alpha = 0.04f),
                    RoundedCornerShape(8.dp),
                ).selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = colors.signal, unselectedColor = colors.textSecondary),
        )
        Text(label, fontSize = 13.sp, color = colors.textPrimary)
    }
}

/** The risk/scope context shown once Allow is armed, before the confirming second tap. */
@Composable
private fun ProactiveAllowConfirmation(
    toolName: String,
    readOnly: Boolean,
    colors: BossColorScheme,
) {
    val risk =
        remember(toolName) {
            DefaultMcpRiskEvaluator().evaluateRisk(toolName, McpToolArgs(emptyMap()))
        }
    if (McpMutatingToolCatalog.isMutating(toolName, readOnly)) {
        Text("This tool performs mutations or external execution.", fontSize = 12.sp, color = colors.alert)
    }
    Text(
        text = "${risk.level}: ${risk.reason}",
        fontSize = 12.sp,
        color = colors.alert,
    )
    Text(
        text =
            "Applies to this tool name for all agents and arguments, across restarts " +
                "and replacement plugins, until reset above.",
        fontSize = 12.sp,
        color = colors.textSecondary,
    )
}

/** Refusals refresh candidates but always require a new operator confirmation. */
internal fun McpProactivePolicyOutcome.proactivePolicyMessage(): String? =
    when (this) {
        McpProactivePolicyOutcome.Saved -> {
            null
        }

        McpProactivePolicyOutcome.Refused -> {
            "Policy changed. Candidates refreshed; review the current policy before trying again."
        }

        McpProactivePolicyOutcome.PolicyUnreadable -> {
            "Policy file unreadable: all tools are withheld. Preserve a backup, repair the file, and restart BOSS."
        }

        McpProactivePolicyOutcome.Denied -> {
            "Current policy already denies this tool. Reset the saved denial or the provider policy first, " +
                "then try again."
        }

        is McpProactivePolicyOutcome.Failed -> {
            "Could not save this rule. Check the host log and storage, then try again."
        }
    }

@Composable
private fun PolicyToolContent(
    sections: List<McpToolIdentity>?,
    policy: McpToolPolicyConfig,
    query: String,
    onApply: (suspend (List<McpSectionPolicyChange>) -> McpProactivePolicyOutcome)?,
    onRefresh: () -> Unit,
    filteredTools: List<McpToolIdentity>,
    hasTools: Boolean,
    onSet: suspend (McpToolIdentity, McpPolicyAction) -> McpProactivePolicyOutcome,
    colors: BossColorScheme,
) {
    if (sections != null && onApply != null) {
        McpPolicySections(sections, policy, query, onApply, onRefresh)
    } else {
        FilteredPolicyCandidates(filteredTools, hasTools, onSet, onRefresh, colors)
    }
}
