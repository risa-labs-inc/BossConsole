package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpFlightCheckpoint
import ai.rever.boss.mcp.McpFlightCheckpointState
import ai.rever.boss.mcp.McpFlightOutcome
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

/**
 * An operator-facing simulator for the MCP admission path.
 *
 * The dialog receives already-computed plans and intentionally has no execution callback. That
 * keeps this surface a forecast: selecting a tool or reading its schema cannot reach the tool
 * handler, approval bus, or audit ledger.
 */
@Composable
@Suppress("LongMethod") // One self-contained dialog keeps the non-executing boundary obvious.
fun McpFlightPlanDialog(
    plans: List<McpFlightPlanView>,
    onDismiss: () -> Unit,
) {
    val windowSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val maxHeight =
        with(density) { windowSize.height.toDp() }.let {
            if (it > 0.dp) (it - 32.dp).coerceAtLeast(1.dp) else 700.dp
        }
    val maxWidth =
        with(density) { windowSize.width.toDp() }.let {
            if (it > 0.dp) (it - 32.dp).coerceAtLeast(1.dp) else 720.dp
        }
    val colors = BossTheme.colors
    val radii = BossTheme.radius
    var selectedId by remember(plans) { mutableStateOf(plans.firstOrNull()?.id) }
    val selected = plans.firstOrNull { it.id == selectedId } ?: plans.firstOrNull()

    BossDialog(onDismissRequest = onDismiss, properties = DialogProperties()) {
        Surface(
            modifier =
                Modifier
                    .widthIn(max = maxWidth)
                    .width(720.dp)
                    .heightIn(max = maxHeight),
            shape = RoundedCornerShape(radii.dialog),
            color = colors.panel,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    "MCP Flight Plan",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Preview the live governance route before an agent acts. This never runs a tool, " +
                        "requests approval, or records an audit event.",
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(16.dp))

                if (selected == null) {
                    Text(
                        "No registered MCP tools are available to preview.",
                        fontSize = 13.sp,
                        color = colors.textSecondary,
                    )
                } else {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        Text(
                            "Choose an action",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textSecondary,
                        )
                        Spacer(Modifier.height(6.dp))
                        plans.forEach { view ->
                            FlightPlanToolRow(view, selectedId == view.id) { selectedId = view.id }
                        }
                        Spacer(Modifier.height(16.dp))
                        FlightPlanDetail(selected)
                    }
                }
                Spacer(Modifier.height(16.dp))
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
private fun FlightPlanToolRow(
    view: McpFlightPlanView,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = BossTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(BossTheme.radius.card),
        color = if (selected) colors.raised else colors.panel,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    view.toolName,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    view.plan.summary,
                    fontSize = 10.sp,
                    color = outcomeColor(view.plan.outcome),
                )
            }
            Text(
                view.providerId,
                fontSize = 10.sp,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FlightPlanDetail(view: McpFlightPlanView) {
    val colors = BossTheme.colors
    val plan = view.plan
    Text(
        view.toolName,
        fontFamily = FontFamily.Monospace,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        color = colors.textPrimary,
    )
    Spacer(Modifier.height(4.dp))
    Text(view.description, fontSize = 12.sp, color = colors.textSecondary)
    Spacer(Modifier.height(12.dp))
    Text(
        plan.summary,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        color = outcomeColor(plan.outcome),
    )
    Text(
        "${if (plan.mutating) "Mutating" else "Read-only"} · ${plan.risk.level} risk · ${plan.risk.reason}",
        fontSize = 11.sp,
        color = colors.textSecondary,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        "Governance route",
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = colors.textPrimary,
    )
    plan.checkpoints.forEach { checkpoint -> FlightCheckpointRow(checkpoint) }
    if (view.inputSchema.isNotBlank()) {
        Spacer(Modifier.height(12.dp))
        Text(
            "Expected inputs",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
        )
        Text(
            view.inputSchema,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = colors.textSecondary,
            maxLines = 7,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FlightCheckpointRow(checkpoint: McpFlightCheckpoint) {
    val colors = BossTheme.colors
    Row(modifier = Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.Top) {
        Text(
            checkpointMark(checkpoint.state),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = checkpointColor(checkpoint.state),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                checkpoint.label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary,
            )
            Text(checkpoint.detail, fontSize = 11.sp, color = colors.textSecondary)
        }
    }
}

@Composable
private fun outcomeColor(outcome: McpFlightOutcome) =
    when (outcome) {
        McpFlightOutcome.READY_TO_RUN -> BossTheme.colors.signalText
        McpFlightOutcome.AWAITING_OPERATOR -> BossTheme.colors.warn
        McpFlightOutcome.WITHHELD -> BossTheme.colors.alert
    }

@Composable
private fun checkpointColor(state: McpFlightCheckpointState) =
    when (state) {
        McpFlightCheckpointState.CLEAR -> BossTheme.colors.signalText
        McpFlightCheckpointState.WAITING -> BossTheme.colors.warn
        McpFlightCheckpointState.BLOCKED -> BossTheme.colors.alert
    }

private fun checkpointMark(state: McpFlightCheckpointState): String =
    when (state) {
        McpFlightCheckpointState.CLEAR -> "✓"
        McpFlightCheckpointState.WAITING -> "●"
        McpFlightCheckpointState.BLOCKED -> "×"
    }
