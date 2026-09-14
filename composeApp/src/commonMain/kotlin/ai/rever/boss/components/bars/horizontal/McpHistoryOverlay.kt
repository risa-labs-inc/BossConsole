package ai.rever.boss.components.bars.horizontal

import ai.rever.boss.mcp.McpOperationRecord
import ai.rever.boss.mcp.McpToolStats
import ai.rever.boss.mcp.computeToolStats
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup

/**
 * Dropdown-style overlay listing recent MCP tool calls and per-tool reliability
 * stats, anchored to the "MCP: ..." status-bar element in [BossRightBottomBar].
 *
 * Deliberately a lightweight [Popup], not a docked panel via the plugin
 * PanelRegistry: that system is oriented around plugin/IPC panel placement
 * (RemoteUiPlacement) and host-bundled panels appear to route through a
 * separate, not-yet-confirmed DataProviderFactory pattern. This overlay reuses
 * only APIs already proven in this file's neighborhood (Popup, BossTheme) and
 * can be migrated to a docked panel later without touching the data flow -
 * [records] in, rendered stats out, no new state of its own beyond visibility.
 */
@Composable
fun McpHistoryOverlay(
    records: List<McpOperationRecord>,
    onDismiss: () -> Unit,
) {
    val stats = remember(records) { computeToolStats(records) }

    Popup(
        onDismissRequest = onDismiss,
        offset = IntOffset(0, -8),
    ) {
        Column(
            modifier =
                Modifier
                    .testTag("mcpHistoryOverlayRoot")
                    .widthIn(min = 360.dp, max = 460.dp)
                    .heightIn(max = 480.dp)
                    .background(BossTheme.colors.raised, RoundedCornerShape(6.dp))
                    .border(1.dp, BossTheme.colors.lineStrong, RoundedCornerShape(6.dp))
                    .padding(8.dp),
        ) {
            Text(
                text = "MCP tool call history",
                color = BossTheme.colors.textPrimary,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )

            if (records.isEmpty()) {
                Text(
                    text = "No MCP tool calls recorded yet this session.",
                    color = BossTheme.colors.textSecondary,
                    fontSize = 12.sp,
                )
            } else {
                Text(
                    text = "Per-tool stats (worst error rate first)",
                    color = BossTheme.colors.textSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                stats.forEach { stat -> McpToolStatsRow(stat) }

                Divider(color = BossTheme.colors.line, modifier = Modifier.padding(vertical = 6.dp))

                Text(
                    text = "Recent calls (${records.size})",
                    color = BossTheme.colors.textSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                LazyColumn {
                    items(records) { record -> McpOperationRow(record) }
                }
            }
        }
    }
}

@Composable
private fun McpToolStatsRow(stat: McpToolStats) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stat.toolName,
            color = BossTheme.colors.textPrimary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(140.dp),
        )
        Text(text = "${stat.callCount}x", color = BossTheme.colors.textSecondary, fontSize = 11.sp)
        Text(text = "p50 ${stat.p50DurationMs}ms", color = BossTheme.colors.textSecondary, fontSize = 11.sp)
        Text(
            text = "err ${(stat.errorRate * 100).toInt()}%",
            color = if (stat.errorRate > 0) BossTheme.colors.alert else BossTheme.colors.textSecondary,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun McpOperationRow(record: McpOperationRecord) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (record.isError) "\u2715" else "\u2713",
            color = if (record.isError) BossTheme.colors.alert else BossTheme.colors.textSecondary,
            fontSize = 11.sp,
            modifier = Modifier.width(16.dp),
        )
        Text(
            text = record.toolName,
            color = BossTheme.colors.textPrimary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "${record.durationMs}ms",
            color = BossTheme.colors.textSecondary,
            fontSize = 11.sp,
        )
    }
}
