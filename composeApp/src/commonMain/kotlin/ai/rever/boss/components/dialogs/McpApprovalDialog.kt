package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpApprovalRequest
import ai.rever.boss.mcp.McpArgumentSanitizer
import ai.rever.boss.mcp.McpMutatingToolCatalog
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

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
                    .width(520.dp)
                    .wrapContentHeight()
                    .border(1.dp, colors.line, RoundedCornerShape(radii.dialog)),
            shape = RoundedCornerShape(radii.dialog),
            color = colors.panel,
        ) {
            Column {
                Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp)) {
                    ApprovalHeader(isMutating = isMutating, pendingQueueSize = pendingQueueSize)

                    Spacer(modifier = Modifier.height(16.dp))
                    ToolDetails(request)

                    val riskLines =
                        buildList {
                            request.riskAssessment?.let { add("${it.level}: ${it.reason}") }
                            if (isMutating) add("This tool performs mutations or external execution.")
                        }
                    if (riskLines.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        RiskBanner(riskLines)
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Remember this decision",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textSecondary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Column(modifier = Modifier.selectableGroup()) {
                        ScopeOption(
                            title = "Just this call",
                            description = "Ask again next time.",
                            selected = scope == McpApprovalScope.ONCE,
                            onSelect = { scope = McpApprovalScope.ONCE },
                        )
                        ScopeOption(
                            title = "This session",
                            description = "Allow this tool until BOSS quits. Deny still applies once.",
                            selected = scope == McpApprovalScope.SESSION,
                            onSelect = { scope = McpApprovalScope.SESSION },
                        )
                        ScopeOption(
                            title = "Always, for this tool",
                            description =
                                "Saved by tool name for all agents and arguments, across restarts - " +
                                    "including a replacement plugin that ships a tool with this name.",
                            selected = scope == McpApprovalScope.ALWAYS_TOOL,
                            onSelect = { scope = McpApprovalScope.ALWAYS_TOOL },
                        )
                        ScopeOption(
                            title = "Always, for every tool from this plugin",
                            description =
                                "Trusts everything \"${request.providerId}\" provides, now and in later versions.",
                            selected = scope == McpApprovalScope.ALWAYS_PLUGIN,
                            titleColor = colors.warn,
                            onSelect = { scope = McpApprovalScope.ALWAYS_PLUGIN },
                        )
                    }
                    Text(
                        text = "Saved rules and trusted plugins can be reviewed from MCP access in the bottom bar.",
                        fontSize = 11.sp,
                        color = colors.textMuted,
                        modifier = Modifier.padding(top = 6.dp),
                    )

                    if (showReasonInput) {
                        Spacer(modifier = Modifier.height(12.dp))
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

                Divider(color = colors.line, thickness = 1.dp)

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
                            val flags = scope.approveFlags()
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
                        Text(scope.allowLabel(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

private val DIALOG_BUTTON_HEIGHT = 32.dp
private val DIALOG_BUTTON_PADDING = PaddingValues(horizontal = 14.dp)

@Composable
private fun ApprovalHeader(
    isMutating: Boolean,
    pendingQueueSize: Int,
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
@Suppress("LongMethod") // Declarative Compose layout.
private fun ToolDetails(request: McpApprovalRequest) {
    val colors = BossTheme.colors
    val radii = BossTheme.radius
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.raised, RoundedCornerShape(radii.card))
                .border(1.dp, colors.line, RoundedCornerShape(radii.card))
                .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = request.toolName,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = colors.signalText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = request.providerId,
                fontSize = 11.sp,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .padding(start = 8.dp)
                        .border(1.dp, colors.line, RoundedCornerShape(radii.input))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }

        val sanitizedArguments =
            remember(request.arguments) {
                McpArgumentSanitizer.sanitize(request.arguments)
            }

        if (sanitizedArguments.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp)
                        .background(colors.ink, RoundedCornerShape(radii.input))
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                sanitizedArguments.forEach { (k, v) ->
                    Text(
                        text = "$k = $v",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.textPrimary,
                    )
                }
            }
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
