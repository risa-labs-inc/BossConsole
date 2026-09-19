package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.rlm.RlmDisplayRow
import ai.rever.boss.mcp.rlm.RlmRunResult
import ai.rever.boss.mcp.rlm.buildRlmDisplayRows
import ai.rever.boss.plugin.ui.BossColorScheme
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
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
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

/**
 * The operator's view of what `codebase_query_rlm` actually did.
 *
 * The gap this closes: the RLM tool's own answer goes to the *agent*, and the ledger records each
 * delegate call as a separate entry with nothing tying them together. So a single recursive query
 * that fanned out to twenty-four `codebase_read`/`codebase_tree`/`project_search` calls used to be
 * indistinguishable, from the operator's side, from an agent that made twenty-four unrelated ones.
 * This shows the tree as one run: its shape, its depth against the cap, its cost, and which nodes
 * failed.
 *
 * [runs] is [ai.rever.boss.mcp.rlm.RlmRunLog.runs]'s own live list (newest first) - collected by
 * the caller and re-collected as it changes, not a copy this dialog owns, so a query issued while
 * the dialog is open appears without reopening it.
 *
 * Rendered from a flattened row list rather than nested composables, so the tree's shape is
 * asserted in a plain unit test (see `RlmRunLogTest`) and this file only draws.
 */
@Composable
@Suppress("LongMethod") // Declarative Compose layout.
fun RlmQueryTreeDialog(
    runs: List<RlmRunResult>,
    onDismiss: () -> Unit,
) {
    val windowSize = LocalWindowInfo.current.containerSize
    val windowHeight = with(LocalDensity.current) { windowSize.height.toDp() }
    val windowWidth = with(LocalDensity.current) { windowSize.width.toDp() }
    val maxHeight = if (windowHeight > 0.dp) (windowHeight - 32.dp).coerceAtLeast(1.dp) else 700.dp
    val maxWidth = if (windowWidth > 0.dp) (windowWidth - 32.dp).coerceAtLeast(1.dp) else 640.dp
    val colors = BossTheme.colors
    val radii = BossTheme.radius
    val rows = remember(runs) { buildRlmDisplayRows(runs) }

    BossDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(),
    ) {
        Surface(
            modifier = Modifier.widthIn(max = maxWidth).width(640.dp).heightIn(max = maxHeight),
            shape = RoundedCornerShape(radii.dialog),
            color = colors.panel,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Text(
                        text = "RLM Query Trees",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text =
                            "Recursive codebase queries from this session, newest first, with the tree each " +
                                "one executed. Depth is capped at 3 and a single query at 24 nodes, so a run " +
                                "marked TRUNCATED stopped at that budget rather than finishing. Every node " +
                                "named here was also policy-checked and recorded in the MCP ledger on its own.",
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    if (rows.isEmpty()) {
                        Text(
                            text = "No recursive codebase queries have run yet this session.",
                            fontSize = 13.sp,
                            color = colors.textSecondary,
                        )
                    } else {
                        rows.forEach { row -> RlmTreeRow(row, colors) }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(onClick = onDismiss) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@Composable
private fun RlmTreeRow(
    row: RlmDisplayRow,
    colors: BossColorScheme,
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = (row.indent * 12).dp, top = 2.dp),
    ) {
        Text(
            // A run header gets its own bullet so the eye can find where one tree ends and the
            // next begins - without it a 24-node tree followed by another reads as one list.
            text =
                if (row.isError) {
                    "✕"
                } else if (row.isRoot) {
                    "•"
                } else {
                    "·"
                },
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (row.isError) colors.alert else colors.textSecondary,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = row.label,
            fontSize = if (row.isRoot) 13.sp else 12.sp,
            fontWeight = if (row.isRoot) FontWeight.Medium else FontWeight.Normal,
            fontFamily = FontFamily.Monospace,
            color = if (row.isError) colors.alert else colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (row.detail.isNotEmpty()) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = row.detail,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.textSecondary,
            )
        }
    }
}
