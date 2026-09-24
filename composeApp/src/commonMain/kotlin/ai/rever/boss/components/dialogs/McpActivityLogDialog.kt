package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpApprovalDisposition
import ai.rever.boss.mcp.McpOperationRecord
import ai.rever.boss.mcp.McpPolicyAction
import ai.rever.boss.plugin.ui.BossColorScheme
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The read side of [ai.rever.boss.mcp.McpOperationLedger] - until this dialog existed, the
 * governed-autonomy audit trail BossConsole#336/#371 built had no viewer at all. `BossBottomBar`
 * shows one line for the single most recent call and nothing behind it; the other ~99 in-memory
 * records were reachable only by opening the rotated ledger file in a text editor.
 *
 * [operations] is [ai.rever.boss.mcp.McpOperationLedger.recentOperations]'s own in-memory ring
 * buffer (newest first, capped at 100) - a live snapshot the caller re-collects, not a copy this
 * dialog owns, so a call made while the dialog is open appears without reopening it.
 *
 * Scoped to what actually reaches the ledger: [ai.rever.boss.mcp.McpOperationLedger]'s own KDoc
 * records that an unregistered, unpermitted or kill-switch-disabled tool call is refused before
 * policy checks and never reaches [record] at all - this dialog is a view over registry-governed
 * calls, not every attempt to invoke MCP.
 */
@Composable
@Suppress("LongMethod") // Declarative Compose layout.
fun McpActivityLogDialog(
    operations: List<McpOperationRecord>,
    totalCalls: Long,
    totalErrors: Long,
    ledgerPath: String? = null,
    onDismiss: () -> Unit,
) {
    val windowSize = LocalWindowInfo.current.containerSize
    val windowHeight = with(LocalDensity.current) { windowSize.height.toDp() }
    val windowWidth = with(LocalDensity.current) { windowSize.width.toDp() }
    val maxHeight = if (windowHeight > 0.dp) (windowHeight - 32.dp).coerceAtLeast(1.dp) else 700.dp
    val maxWidth = if (windowWidth > 0.dp) (windowWidth - 32.dp).coerceAtLeast(1.dp) else 560.dp
    val colors = BossTheme.colors
    val radii = BossTheme.radius
    val timeFormat = remember { SimpleDateFormat("MMM d HH:mm:ss", Locale.getDefault()) }
    BossDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(),
    ) {
        Surface(
            modifier = Modifier.widthIn(max = maxWidth).width(560.dp).heightIn(max = maxHeight),
            shape = RoundedCornerShape(radii.dialog),
            color = colors.panel,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Text(
                        text = "MCP Activity Log",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text =
                            "The most recent tool calls this session that reached the policy engine, " +
                                "up to the last 100 - which plugin, what was decided, and how it turned " +
                                "out. A call to an unregistered, unpermitted or kill-switch-disabled " +
                                "tool never reaches this list. Older entries roll off here." +
                                if (ledgerPath != null) {
                                    " They may still be in $ledgerPath (active file plus up to 5 backups), " +
                                        "on a best-effort basis. Write failures are logged, not retried."
                                } else {
                                    " Disk persistence is not configured for this ledger."
                                },
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text =
                            buildString {
                                append("$totalCalls call")
                                if (totalCalls != 1L) append("s")
                                append(" total")
                                // "Unsuccessful," not "errors": totalErrors also counts an operator's
                                // own Deny and a cancelled approval, which are governance working as
                                // designed, not tool faults - see the per-call breakdown below for
                                // which kind actually happened (review on #636).
                                if (totalErrors > 0) {
                                    append(", $totalErrors unsuccessful")
                                }
                            },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textSecondary,
                    )
                    val unsuccessfulBreakdown = remember(operations) { operations.unsuccessfulBreakdown() }
                    if (unsuccessfulBreakdown.isNotEmpty()) {
                        Text(
                            text = "In the list below: " + unsuccessfulBreakdown.joinToString(", "),
                            fontSize = 11.sp,
                            color = colors.textSecondary,
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    if (operations.isEmpty()) {
                        Text(
                            text = "No MCP tool calls recorded yet this session.",
                            fontSize = 13.sp,
                            color = colors.textSecondary,
                        )
                    } else {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth(),
                        ) {
                            operations.forEach { op ->
                                // recentOperations is live and newest-first, so a new call
                                // prepends: without a stable key each row's remembered
                                // expansion state resets at its new position.
                                key(op.id) { McpOperationRow(op, timeFormat, colors) }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
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

@Composable
private fun McpOperationRow(
    op: McpOperationRecord,
    timeFormat: SimpleDateFormat,
    colors: BossColorScheme,
) {
    var expanded by remember(op.id) { mutableStateOf(false) }
    // A row expands when it has anything worth a second look: the sanitized args are never
    // otherwise reachable from this view, and a long error is clipped to two lines.
    val hasDetail = op.sanitizedArgs.isNotEmpty() || op.errorSnippet != null
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        McpOperationHeaderRow(
            op = op,
            timeFormat = timeFormat,
            colors = colors,
            expanded = expanded,
            onToggle = if (hasDetail) ({ expanded = !expanded }) else null,
        )
        McpOperationMetaRow(op, colors)
        if (expanded) {
            McpOperationDetail(op, colors)
        } else {
            op.errorSnippet?.let { snippet ->
                Text(
                    text = snippet,
                    fontSize = 11.sp,
                    color = colors.alert,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The part of a ledger row that did not fit the list: the sanitized argument map the call was
 * recorded with, and the full error. Everything sits inside one [SelectionContainer] so an
 * operator can copy either out without re-running the tool, and the error lives in a bounded
 * scrollable box so a long stack trace cannot stretch the row.
 */
@Composable
private fun McpOperationDetail(
    op: McpOperationRecord,
    colors: BossColorScheme,
) {
    SelectionContainer(modifier = Modifier.testTag("mcp-op-detail").fillMaxWidth()) {
        Column(modifier = Modifier.padding(top = 4.dp)) {
            Text(
                text = "Arguments",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textSecondary,
            )
            if (op.sanitizedArgs.isEmpty()) {
                Text(
                    text = "(no arguments recorded)",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.textSecondary,
                )
            } else {
                op.sanitizedArgs.forEach { (key, value) ->
                    Text(
                        text = "$key: $value",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.textPrimary,
                    )
                }
            }
            op.errorSnippet?.let { snippet ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Error",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textSecondary,
                )
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = snippet,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.alert,
                    )
                }
            }
        }
    }
}

@Composable
private fun McpOperationHeaderRow(
    op: McpOperationRecord,
    timeFormat: SimpleDateFormat,
    colors: BossColorScheme,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (onToggle != null) {
                        Modifier.clickable(
                            onClickLabel = if (expanded) "Hide details" else "Show details",
                            role = Role.Button,
                            onClick = onToggle,
                        )
                    } else {
                        Modifier
                    },
                ),
    ) {
        // Fixed-width slot so the timestamp column stays aligned whether or not a row expands.
        McpExpandChevron(expandable = onToggle != null, expanded = expanded, colors = colors)
        Text(
            text = timeFormat.format(Date(op.timestamp)),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = colors.textSecondary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = op.toolName,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${op.durationMs}ms",
            fontSize = 11.sp,
            color = colors.textSecondary,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = if (op.isError) "✕" else "✓",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            // signalText, not signal: this is text, and BossDesignSystem's own rule is "signal on
            // a rect, signalText on a Text/Icon" - signal falls under the 4.5:1 text floor on the
            // light theme (review on #636).
            color = if (op.isError) colors.alert else colors.signalText,
        )
    }
}

@Composable
private fun McpExpandChevron(
    expandable: Boolean,
    expanded: Boolean,
    colors: BossColorScheme,
) {
    Text(
        text =
            when {
                !expandable -> ""
                expanded -> "▾"
                else -> "▸"
            },
        fontSize = 11.sp,
        color = colors.textSecondary,
        modifier = Modifier.width(12.dp),
    )
}

@Composable
private fun McpOperationMetaRow(
    op: McpOperationRecord,
    colors: BossColorScheme,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = op.providerId,
            fontSize = 11.sp,
            color = colors.textSecondary,
            // Unbounded before this let a long provider id push the policy/disposition chips -
            // the two fields this view exists to show - off the row entirely (review on #636/#662).
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp),
        )
        Text(
            text = op.policyApplied.name,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = if (op.policyApplied == McpPolicyAction.DENY) colors.alert else colors.textSecondary,
        )
        Text(
            text = op.approvalDisposition.readable(),
            fontSize = 10.sp,
            color = colors.textSecondary,
        )
    }
}

/**
 * "SESSION_TRUSTED" -> "Session trusted" - the raw enum name is a log token, not UI copy.
 * [Locale.ROOT], not the default: this is an enum constant, not user text, and under a locale
 * like `tr-TR` a default-locale lowercase turns "CANCELLED_IN_FLIGHT" into "Cancelled ıin flight".
 *
 * `internal`, not `private`: the one thing standing between a new [McpApprovalDisposition] value
 * silently mis-lowercasing and a compile error checking that is worth pinning on its own
 * (review on #662).
 */
internal fun McpApprovalDisposition.readable(): String =
    name.lowercase(Locale.ROOT).split("_").joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

/**
 * How an unsuccessful call is summarized - split from a flat "N errors"/"N failed" count because
 * [McpOperationRecord.isError] covers governance working as designed (an operator's own Deny, a
 * cancelled approval) as well as an actual tool or host fault, and conflating them reads a
 * governance success as a failure (review on #636).
 */
internal enum class McpUnsuccessfulCategory(
    val label: String,
) {
    DENIED("denied"),
    CANCELLED("cancelled"),

    /** The tool never ran because the host could not obtain or persist approval. */
    WITHHELD("withheld"),

    /** Everything else: the tool (or the host executing it) genuinely failed. */
    FAILED("failed"),
}

/**
 * `internal`, not `private`, and an exhaustive `when` rather than the `setOf` membership checks
 * this replaces: [McpApprovalDisposition] has grown repeatedly (#336/#371/#636 each added
 * entries), and a `setOf`-based classification silently counts a new one as a tool fault by
 * falling through to the default. A `when` with no `else` is a compile error the day the enum
 * grows again, which is the point (review on #662).
 *
 * [McpApprovalDisposition.POLICY_PERSIST_FAILED] gets its own [McpUnsuccessfulCategory.WITHHELD]
 * bucket rather than folding into denied or failed: `McpToolRegistryImpl` documents it as "the
 * durable approval failed to save, and the call is withheld" - the tool itself never ran, so
 * counting it as a tool fault misattributes a host disk fault (review on #636/#662).
 * [McpApprovalDisposition.PROVIDER_TRUST_PERSIST_FAILED] is deliberately NOT in that bucket: its
 * own KDoc says the call in hand still executes, so on the rare occasion it does carry
 * `isError = true` that is the executed tool genuinely failing, not a withheld call.
 */
internal val McpApprovalDisposition.unsuccessfulCategory: McpUnsuccessfulCategory
    get() =
        when (this) {
            McpApprovalDisposition.DENIED_BY_OPERATOR,
            McpApprovalDisposition.POLICY_DENIED,
            McpApprovalDisposition.PERSISTENTLY_DENIED,
            -> McpUnsuccessfulCategory.DENIED

            McpApprovalDisposition.CANCELLED,
            McpApprovalDisposition.CANCELLED_AWAITING_APPROVAL,
            McpApprovalDisposition.CANCELLED_IN_FLIGHT,
            McpApprovalDisposition.TIMEOUT,
            -> McpUnsuccessfulCategory.CANCELLED

            McpApprovalDisposition.QUEUE_FULL,
            McpApprovalDisposition.POLICY_PERSIST_FAILED,
            -> McpUnsuccessfulCategory.WITHHELD

            McpApprovalDisposition.AUTO_ALLOWED,
            McpApprovalDisposition.APPROVED_ONCE,
            McpApprovalDisposition.SESSION_TRUSTED,
            McpApprovalDisposition.PERSISTENTLY_ALLOWED,
            McpApprovalDisposition.PROVIDER_TRUSTED,
            McpApprovalDisposition.PROVIDER_TRUST_PERSIST_FAILED,
            McpApprovalDisposition.YOLO_ALLOWED,
            McpApprovalDisposition.YOLO_ENABLED,
            McpApprovalDisposition.YOLO_DISABLED,
            -> McpUnsuccessfulCategory.FAILED
        }

/**
 * A short "3 denied, 1 cancelled" summary of the unsuccessful entries actually visible in this
 * window, grouped by [McpUnsuccessfulCategory] and rendered in a fixed, stable order rather than
 * map iteration order.
 */
internal fun List<McpOperationRecord>.unsuccessfulBreakdown(): List<String> {
    val counts =
        filter { it.isError }
            .groupingBy { it.approvalDisposition.unsuccessfulCategory }
            .eachCount()
    return McpUnsuccessfulCategory.entries.mapNotNull { category ->
        counts[category]?.let { count -> "$count ${category.label}" }
    }
}
