package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpApprovalRequest
import ai.rever.boss.mcp.McpMutatingToolCatalog
import ai.rever.boss.mcp.PreparedPackDisplayModel
import ai.rever.boss.mcp.secrets.SecretDescriptor
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.RadioButtonDefaults
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/**
 * How far an operator's answer in [McpApprovalDialog] reaches, in increasing durability.
 *
 * The dialog used to spread these over three rows of differently styled buttons (seven buttons in
 * all), so the same question - "allow or deny?" - was asked in three places at three sizes. Now
 * the scope is picked once and the answer is one of two buttons whose label says exactly what it
 * will do. Picking a scope first is also the deliberate reach [ALWAYS_PLUGIN] needs: the broadest
 * grant this dialog can make can no longer be landed on by a fast click next to "Allow once".
 */
internal enum class McpApprovalScope {
    /** Just this call. */
    ONCE,

    /** This tool from this provider, until the app quits. Allow only. */
    SESSION,

    /** A persisted, tool-name-wide rule, across restarts. Allow or deny. */
    ALWAYS_TOOL,

    /** A persisted ALLOW for every tool the provider contributes. Allow only. */
    ALWAYS_PLUGIN,
}

/** The `onApprove` flags a scope maps to: `(trustForSession, persistPolicy, trustProvider)`. */
internal data class McpApproveFlags(
    val trustForSession: Boolean,
    val persistPolicy: Boolean,
    val trustProvider: Boolean,
)

internal fun McpApprovalScope.approveFlags(): McpApproveFlags =
    when (this) {
        McpApprovalScope.ONCE -> McpApproveFlags(false, false, false)
        McpApprovalScope.SESSION -> McpApproveFlags(true, false, false)
        McpApprovalScope.ALWAYS_TOOL -> McpApproveFlags(false, true, false)
        McpApprovalScope.ALWAYS_PLUGIN -> McpApproveFlags(false, false, true)
    }

/**
 * Whether Deny persists a rule. Only [McpApprovalScope.ALWAYS_TOOL] can: there is no session or
 * provider-wide deny, so under those scopes Deny answers this one call - and says so in its label
 * ([denyLabel]) rather than silently doing less than the selected scope suggests.
 */
internal fun McpApprovalScope.persistsDeny(): Boolean = this == McpApprovalScope.ALWAYS_TOOL

/**
 * What an approval prompt offers, and what its allow button does, for escalated requests - a
 * saved ALLOW overridden because the call rates CRITICAL (#1577, #1624). No saved *allow* can
 * pre-approve such a call - the gate overrides it on the next destructive attempt - but a saved
 * *deny* is never overridden, so it is exactly the durable answer an operator facing repeated
 * destructive attempts needs. An object so the rules sit together, apart from the composable.
 */
internal object McpPromptChoices {
    /**
     * The scopes offered: all of them, or a narrowed set when a saved allow could not have
     * pre-approved this call.
     *
     * Two reasons that happens, and they narrow differently. An **escalated** call (#1624) offers
     * once plus "Always" for its deny half, since the destructive-shell gate overrides any saved
     * allow on the next call anyway. A **secret-bearing** call offers once and this session: the
     * two durable scopes are refused by the engine for such a call
     * (`approvedAuthorization` applies them as once), so offering them would promise a rule that
     * is never written, while session trust is genuinely available because it never satisfies a
     * later secret-bearing call - that one asks again regardless.
     */
    fun scopesFor(request: McpApprovalRequest): List<McpApprovalScope> =
        when {
            !request.allowStandingTrust -> listOf(McpApprovalScope.ONCE)
            request.escalated -> listOf(McpApprovalScope.ONCE, McpApprovalScope.ALWAYS_TOOL)
            request.secretRefs.isNotEmpty() -> listOf(McpApprovalScope.ONCE, McpApprovalScope.SESSION)
            else -> McpApprovalScope.entries
        }

    /** What the allow button sends: always once on an escalated request, whatever is selected. */
    fun allowFlagsFor(
        request: McpApprovalRequest,
        scope: McpApprovalScope,
    ): McpApproveFlags =
        if (!request.allowStandingTrust || request.escalated) {
            McpApprovalScope.ONCE.approveFlags()
        } else {
            scope.approveFlags()
        }

    /** The allow button's label, matching [allowFlagsFor]. */
    fun allowLabelFor(
        request: McpApprovalRequest,
        scope: McpApprovalScope,
    ): String =
        when {
            !request.allowStandingTrust -> "Approve Once"
            request.escalated -> McpApprovalScope.ONCE.allowLabel()
            else -> scope.allowLabel()
        }

    /** Title and description of the "Always, for this tool" option, which only denies when escalated. */
    fun alwaysToolText(request: McpApprovalRequest): Pair<String, String> =
        if (request.escalated) {
            "Always deny this tool" to
                "Saves a deny by tool name, across restarts. Allowing still runs just this call: a saved " +
                "allow cannot pre-approve a destructive one."
        } else {
            "Always, for this tool" to
                "Saved by tool name for all agents and arguments, across restarts - including a replacement " +
                "plugin that ships a tool with this name."
        }
}

internal fun McpApprovalScope.allowLabel(): String =
    when (this) {
        McpApprovalScope.ONCE -> "Allow once"
        McpApprovalScope.SESSION -> "Allow for session"
        McpApprovalScope.ALWAYS_TOOL -> "Always allow"
        McpApprovalScope.ALWAYS_PLUGIN -> "Trust plugin"
    }

internal fun McpApprovalScope.denyLabel(): String =
    when (this) {
        McpApprovalScope.ONCE -> "Deny"
        McpApprovalScope.ALWAYS_TOOL -> "Always deny"
        McpApprovalScope.SESSION, McpApprovalScope.ALWAYS_PLUGIN -> "Deny once"
    }

private const val DEFAULT_DENY_REASON = "Operator declined this action"

/**
 * Interactive dialog prompted when an AI agent attempts to execute a tool
 * governed by an ASK policy.
 *
 * Four scopes an operator can choose ([McpApprovalScope]), in increasing durability: just this
 * one call, trust it for the rest of this session only, persist a rule to
 * `~/.boss/mcp-tool-policy.json` so the same tool never asks again across restarts, or trust the
 * whole plugin. The engine behind the persisted scope ([ai.rever.boss.mcp.McpPolicyEngine
 * .setToolPolicy]) already existed and was already tested; this dialog was the only thing
 * standing between it and an operator who wanted to use it without hand-editing that file.
 *
 * "Trust plugin" persists an ALLOW for every tool [McpApprovalRequest.providerId]
 * contributes, not just this one call or this one tool - the answer to a plugin whose tools get
 * approved one at a time in the same session. It is weaker than an explicit per-tool rule (see
 * [ai.rever.boss.mcp.McpPolicyEngine.policyFor]), and revocable from the bottom bar's MCP access
 * menu.
 */
@Composable
@Suppress("LongMethod") // Declarative Compose layout.
fun McpApprovalDialog(
    request: McpApprovalRequest,
    pendingQueueSize: Int = 1,
    onApprove: (trustForSession: Boolean, persistPolicy: Boolean, trustProvider: Boolean) -> Unit,
    onDeny: (reason: String, persistPolicy: Boolean) -> Unit,
    onDenyAllPending: () -> Unit = {},
) {
    val colors = BossTheme.colors
    val radii = BossTheme.radius
    // The tool's own readOnly declaration rides on the request (#804): an innocent name must
    // never soften this label while the provider itself declared the tool mutating.
    val isMutating =
        remember(request.toolName, request.declaredReadOnly) {
            McpMutatingToolCatalog.isMutating(request.toolName, request.declaredReadOnly)
        }
    var rejectionReason by remember(request.id) { mutableStateOf("") }
    var showReasonInput by remember(request.id) { mutableStateOf(false) }
    var scope by remember(request.id) { mutableStateOf(McpApprovalScope.ONCE) }
    val remainingMs by
        produceState(
            initialValue = approvalMillisRemaining(request, System.currentTimeMillis()),
            key1 = request.id,
        ) {
            while (value > 0L) {
                delay(minOf(APPROVAL_COUNTDOWN_TICK_MS, value))
                value = approvalMillisRemaining(request, System.currentTimeMillis())
            }
        }

    // Bounded by the window the prompt belongs to, read here rather than inside the dialog so the
    // heavyweight overlay's own window cannot answer. The card used to be wrapContentHeight with no
    // cap and nothing scrolled, so a long description or argument list pushed the actions below
    // the window edge: Allow and Deny were unreachable and the prompt could only time out.
    val (maxWidth, maxHeight) = approvalDialogBounds()
    val bodyScroll = remember(request.id) { ScrollState(0) }

    BossDialog(
        // onDismissRequest is required by BossDialog; outside-click and back-press are disabled below
        // to enforce deliberate operator approval or denial.
        onDismissRequest = { onDeny("Dismissed by operator", false) },
        properties =
            DialogProperties(
                dismissOnClickOutside = false,
                dismissOnBackPress = false,
            ),
    ) {
        Surface(
            modifier =
                Modifier
                    .widthIn(max = maxWidth)
                    .width(APPROVAL_DIALOG_WIDTH)
                    .heightIn(max = maxHeight)
                    .border(1.dp, colors.line, RoundedCornerShape(radii.dialog)),
            shape = RoundedCornerShape(radii.dialog),
            color = colors.panel,
        ) {
            // Three bands: the header (with the one countdown) and the actions are pinned, and only
            // the body between them scrolls, so the answer is reachable at any window height.
            Column {
                Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp)) {
                    ApprovalHeader(
                        isMutating = isMutating,
                        pendingQueueSize = pendingQueueSize,
                        remainingMs = remainingMs,
                    )
                }

                Divider(color = colors.line, thickness = 1.dp)

                Box(modifier = Modifier.weight(1f, fill = false)) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag(APPROVAL_BODY_TAG)
                                .verticalScroll(bodyScroll)
                                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
                    ) {
                        ToolDetails(request)

                        val packModel = request.displayModel as? PreparedPackDisplayModel
                        if (packModel != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Resolved Pack Plan (${packModel.packId}):",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 200.dp)
                                        .verticalScroll(rememberScrollState())
                                        .background(colors.raised, RoundedCornerShape(4.dp))
                                        .border(1.dp, colors.line, RoundedCornerShape(4.dp))
                                        .padding(8.dp),
                            ) {
                                if (packModel.plugins.isNotEmpty()) {
                                    Text(
                                        text = "Plugins (${packModel.plugins.size}):",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.signal,
                                    )
                                    packModel.plugins.forEach { plugin ->
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Column(modifier = Modifier.fillMaxWidth().padding(start = 6.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = "${plugin.pluginId} [${plugin.action}]",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = colors.textPrimary,
                                                )
                                                if (plugin.optional) {
                                                    Text(
                                                        text = " (optional)",
                                                        fontSize = 10.sp,
                                                        color = colors.textSecondary,
                                                    )
                                                }
                                            }
                                            val versionDetails =
                                                buildString {
                                                    if (plugin.installedVersion != null) {
                                                        append("installed: ${plugin.installedVersion} -> ")
                                                    }
                                                    if (plugin.targetVersion != null) {
                                                        append("target: ${plugin.targetVersion}")
                                                    }
                                                    if (plugin.targetSha256 != null) {
                                                        append(" (${plugin.targetSha256.take(12)}…)")
                                                    }
                                                }
                                            if (versionDetails.isNotBlank()) {
                                                Text(
                                                    text = versionDetails,
                                                    fontSize = 10.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = colors.textSecondary,
                                                )
                                            }
                                            if (plugin.extraDependencies.isNotEmpty()) {
                                                Text(
                                                    text = "Also installs:",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = colors.warn,
                                                )
                                                plugin.extraDependencies.forEach { dep ->
                                                    Text(
                                                        text = "• ${dep.pluginId}@${dep.version} (${dep.sha256.take(12)}…)",
                                                        fontSize = 10.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = colors.textSecondary,
                                                        modifier = Modifier.padding(start = 6.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                if (packModel.rules.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Policy Rules (${packModel.rules.size}):",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.signal,
                                    )
                                    packModel.rules.forEach { rule ->
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Column(modifier = Modifier.fillMaxWidth().padding(start = 6.dp)) {
                                            Text(
                                                text = "${rule.scope} / ${rule.subject}: ${rule.action} -> ${rule.outcome}",
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = colors.textPrimary,
                                            )
                                            if (rule.existing != null) {
                                                Text(
                                                    text = "replaces existing: ${rule.existing}",
                                                    fontSize = 10.sp,
                                                    color = colors.textSecondary,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // What the tool would be handed from the vault. Metadata only - the request
                        // carries descriptors, never values - and above the risk line because it is
                        // the one fact this dialog exists to put in front of the operator here.
                        if (request.secretRefs.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            SecretReferencesSection(request.secretRefs)
                        }

                        val riskLines =
                            buildList {
                                request.riskAssessment?.let { add("${it.level}: ${it.reason}") }
                                if (isMutating) add("This tool performs mutations or external execution.")
                            }
                        if (riskLines.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            RiskBanner(riskLines)
                        }

                        if (pendingQueueSize > 1) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .background(colors.raised, RoundedCornerShape(radii.card))
                                        .border(1.dp, colors.line, RoundedCornerShape(radii.card))
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text =
                                        "$pendingQueueSize actions are waiting for approval. " +
                                            "New requests are unaffected.",
                                    fontSize = 10.sp,
                                    color = colors.textSecondary,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                TextButton(
                                    onClick = onDenyAllPending,
                                    colors = ButtonDefaults.textButtonColors(contentColor = colors.alert),
                                ) {
                                    Text("Deny All Pending", fontSize = 11.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Remember this decision",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textSecondary,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        ScopeOptions(request = request, selected = scope, onSelect = { scope = it })
                        if (request.escalated) {
                            Text(
                                text =
                                    "This call is asked every time, even though this tool is allowed: it looks " +
                                        "destructive, and no saved rule can approve that in advance - only deny it.",
                                fontSize = 11.sp,
                                color = colors.warn,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        Text(
                            text = "Saved rules and trusted plugins can be reviewed from MCP access in the bottom bar.",
                            fontSize = 11.sp,
                            color = colors.textMuted,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(bodyScroll),
                        modifier =
                            Modifier
                                .matchParentSize()
                                .wrapContentWidth(Alignment.End)
                                .padding(vertical = 2.dp, horizontal = 2.dp),
                    )
                }

                Divider(color = colors.line, thickness = 1.dp)

                // The denial note sits with the actions it belongs to, outside the scrolling body,
                // so opening it never lands the field somewhere the operator has to scroll to.
                if (showReasonInput) {
                    Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
                        OutlinedTextField(
                            value = rejectionReason,
                            onValueChange = { rejectionReason = it },
                            label = { Text("Note for the agent (sent with a denial)", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 2,
                            colors =
                                TextFieldDefaults.outlinedTextFieldColors(
                                    textColor = colors.textPrimary,
                                    cursorColor = colors.signal,
                                    focusedBorderColor = colors.alert,
                                    unfocusedBorderColor = colors.line,
                                    focusedLabelColor = colors.alert,
                                    unfocusedLabelColor = colors.textSecondary,
                                ),
                        )
                    }
                }

                // Footer: one secondary text action on the left, and exactly two answers on the
                // right - both the same height, shape and type size, so neither reads as the
                // "real" button by accident. Allow is the filled one because it is the answer the
                // dialog exists to collect; Deny is outlined in the alert colour.
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(colors.raised)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!showReasonInput) {
                        TextButton(
                            onClick = { showReasonInput = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = colors.textSecondary),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.height(DIALOG_BUTTON_HEIGHT),
                        ) {
                            Text("Add denial note...", fontSize = 12.sp)
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))

                    OutlinedButton(
                        onClick = { onDeny(rejectionReason.ifBlank { DEFAULT_DENY_REASON }, scope.persistsDeny()) },
                        border = BorderStroke(1.dp, colors.alert),
                        shape = RoundedCornerShape(radii.button),
                        colors =
                            ButtonDefaults.outlinedButtonColors(
                                backgroundColor = Color.Transparent,
                                contentColor = colors.alert,
                            ),
                        contentPadding = DIALOG_BUTTON_PADDING,
                        modifier = Modifier.height(DIALOG_BUTTON_HEIGHT),
                    ) {
                        Text(scope.denyLabel(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            val flags = McpPromptChoices.allowFlagsFor(request, scope)
                            onApprove(flags.trustForSession, flags.persistPolicy, flags.trustProvider)
                        },
                        shape = RoundedCornerShape(radii.button),
                        elevation = null,
                        colors =
                            ButtonDefaults.buttonColors(
                                backgroundColor = colors.signal,
                                contentColor = colors.onSignal,
                            ),
                        contentPadding = DIALOG_BUTTON_PADDING,
                        modifier = Modifier.height(DIALOG_BUTTON_HEIGHT),
                    ) {
                        Text(
                            McpPromptChoices.allowLabelFor(request, scope),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

internal val APPROVAL_DIALOG_WIDTH = 520.dp

private val DIALOG_BUTTON_HEIGHT = 32.dp
private val DIALOG_BUTTON_PADDING = PaddingValues(horizontal = 14.dp)

@Composable
private fun ApprovalHeader(
    isMutating: Boolean,
    pendingQueueSize: Int,
    remainingMs: Long,
) {
    val colors = BossTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(
            imageVector = Icons.Outlined.Warning,
            contentDescription = "Security Alert",
            tint = if (isMutating) colors.alert else colors.warn,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Agent Action Approval",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
            Text(
                text = "An AI agent requested to invoke a governed tool.",
                fontSize = 12.sp,
                color = colors.textSecondary,
            )
            Text(
                text = approvalExpiryLabel(remainingMs),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color =
                    if (remainingMs <= APPROVAL_EXPIRY_WARNING_MS) {
                        colors.alert
                    } else {
                        colors.textSecondary
                    },
            )
        }
        if (pendingQueueSize > 1) {
            Text(
                text = "1 of $pendingQueueSize",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textSecondary,
                modifier =
                    Modifier
                        .background(colors.raised, RoundedCornerShape(10.dp))
                        .border(1.dp, colors.line, RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun RiskBanner(lines: List<String>) {
    val colors = BossTheme.colors
    val radii = BossTheme.radius
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.alert.copy(alpha = 0.08f), RoundedCornerShape(radii.card))
                .border(1.dp, colors.alert.copy(alpha = 0.35f), RoundedCornerShape(radii.card))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        lines.forEach { line ->
            Text(text = line, fontSize = 12.sp, color = colors.alert)
        }
    }
}

/** The scopes [McpPromptChoices.scopesFor] offers for [request], as one radio group. */
@Composable
private fun ScopeOptions(
    request: McpApprovalRequest,
    selected: McpApprovalScope,
    onSelect: (McpApprovalScope) -> Unit,
) {
    val scopes = McpPromptChoices.scopesFor(request)
    Column(modifier = Modifier.selectableGroup()) {
        if (McpApprovalScope.ONCE in scopes) {
            ScopeOption(
                title = "Just this call",
                description = "Ask again next time.",
                selected = selected == McpApprovalScope.ONCE,
                onSelect = { onSelect(McpApprovalScope.ONCE) },
            )
        }
        if (McpApprovalScope.SESSION in scopes) {
            ScopeOption(
                title = "This session",
                description = "Allow this tool until BOSS quits. Deny still applies once.",
                selected = selected == McpApprovalScope.SESSION,
                onSelect = { onSelect(McpApprovalScope.SESSION) },
            )
        }
        if (McpApprovalScope.ALWAYS_TOOL in scopes) {
            val (alwaysTitle, alwaysDescription) = McpPromptChoices.alwaysToolText(request)
            ScopeOption(
                title = alwaysTitle,
                description = alwaysDescription,
                selected = selected == McpApprovalScope.ALWAYS_TOOL,
                onSelect = { onSelect(McpApprovalScope.ALWAYS_TOOL) },
            )
        }
        if (McpApprovalScope.ALWAYS_PLUGIN in scopes) {
            ScopeOption(
                title = "Always, for every tool from this plugin",
                description = "Trusts everything \"${request.providerId}\" provides, now and in later versions.",
                selected = selected == McpApprovalScope.ALWAYS_PLUGIN,
                titleColor = BossTheme.colors.warn,
                onSelect = { onSelect(McpApprovalScope.ALWAYS_PLUGIN) },
            )
        }
        if (!request.allowStandingTrust) {
            Text(
                text = "Pack applications require fresh approval for each plan and cannot be granted standing or session trust.",
                fontSize = 11.sp,
                color = BossTheme.colors.textSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ScopeOption(
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
    titleColor: Color = BossTheme.colors.textPrimary,
) {
    val colors = BossTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            // No size override: the whole row is the selectable target, and shrinking the control
            // below Material's own size only made the radio itself harder to hit.
            colors = RadioButtonDefaults.colors(selectedColor = colors.signal, unselectedColor = colors.textSecondary),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(text = title, fontSize = 13.sp, color = titleColor)
            Text(text = description, fontSize = 11.sp, color = colors.textSecondary)
        }
    }
}

/**
 * The secrets a call would deliver, one row each, and the sentence about scope that keeps the
 * operator's expectations right: the value goes to the tool and never to the agent, and a
 * secret-bearing call asks again whatever rule exists for the tool (see `McpSecretPolicyAction`).
 */
@Composable
internal fun SecretReferencesSection(secretRefs: List<SecretDescriptor>) {
    val colors = BossTheme.colors
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.raised, RoundedCornerShape(4.dp))
                .border(1.dp, colors.alert, RoundedCornerShape(4.dp))
                .padding(8.dp),
    ) {
        Text(
            text =
                if (secretRefs.size == 1) {
                    "This call receives 1 secret:"
                } else {
                    "This call receives ${secretRefs.size} secrets:"
                },
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = colors.alert,
        )
        Spacer(modifier = Modifier.height(4.dp))
        secretRefs.forEach { descriptor ->
            Text(
                text = descriptor.display,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.textPrimary,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text =
                "The value goes to the tool, never to the agent. " +
                    "Secret-bearing calls ask every time and cannot create a durable allow rule; " +
                    "\"this session\" still covers this tool's calls that carry no secret.",
            fontSize = 11.sp,
            color = colors.textSecondary,
        )
    }
}
