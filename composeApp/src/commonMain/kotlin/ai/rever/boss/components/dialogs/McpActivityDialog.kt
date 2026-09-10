package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpActivityEvent
import ai.rever.boss.mcp.McpActivityOutcome
import ai.rever.boss.mcp.McpActivityStore
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ACTIVITY_DIALOG_WIDTH = 960.dp
private val ACTIVITY_LIST_MAX_HEIGHT = 480.dp
private val ACTIVITY_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private const val MAX_IDENTIFIER_LENGTH = 160

internal enum class McpActivityFilter {
    ALL,
    ERRORS,
    TIMEOUTS,
    CANCELLED,
}

internal fun filterMcpActivity(
    events: List<McpActivityEvent>,
    filter: McpActivityFilter,
    query: String,
): List<McpActivityEvent> {
    val normalizedQuery = query.trim()
    return events
        .asReversed()
        .asSequence()
        .filter {
            when (filter) {
                McpActivityFilter.ALL -> true
                McpActivityFilter.ERRORS -> it.outcome == McpActivityOutcome.ERROR
                McpActivityFilter.TIMEOUTS -> it.outcome == McpActivityOutcome.TIMEOUT
                McpActivityFilter.CANCELLED -> it.outcome == McpActivityOutcome.CANCELLED
            }
        }.filter {
            normalizedQuery.isEmpty() ||
                it.toolName.contains(normalizedQuery, ignoreCase = true) ||
                it.providerId.contains(normalizedQuery, ignoreCase = true)
        }.toList()
}

internal fun formatMcpActivityDuration(durationMs: Long): String =
    if (durationMs < 1_000) {
        "${durationMs.coerceAtLeast(0)} ms"
    } else {
        "${durationMs / 1_000}.${(durationMs % 1_000) / 100} s"
    }

internal fun formatMcpActivityCompletion(
    epochMs: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String = ACTIVITY_TIME_FORMATTER.format(Instant.ofEpochMilli(epochMs).atZone(zoneId))

internal fun displayMcpActivityIdentifier(identifier: String): String {
    val clean = identifier.filterNot { it.isISOControl() || it.category == CharCategory.FORMAT }
    if (clean.isBlank()) return "-"
    return if (clean.length > MAX_IDENTIFIER_LENGTH) clean.take(MAX_IDENTIFIER_LENGTH - 1) + "…" else clean
}

/** Window-local surface over the process-wide, privacy-minimized MCP activity store. */
@Composable
internal fun McpActivityDialog(
    eventsFlow: StateFlow<List<McpActivityEvent>>,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val events by eventsFlow.collectAsState()
    var filter by remember { mutableStateOf(McpActivityFilter.ALL) }
    var query by remember { mutableStateOf("") }
    val visibleEvents = remember(events, filter, query) { filterMcpActivity(events, filter, query) }

    BossDialog(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .width(ACTIVITY_DIALOG_WIDTH)
                    .background(BossTheme.colors.panel)
                    .padding(20.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("MCP Activity", color = BossTheme.colors.textPrimary, fontSize = 20.sp)
                TextButton(onClick = onDismiss) { Text("Close", color = BossTheme.colors.signalText) }
            }
            McpActivityNotice(events.size)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                McpActivityFilter.entries.forEach { option ->
                    TextButton(onClick = { filter = option }) {
                        Text(
                            option.label(),
                            color =
                                if (filter == option) BossTheme.colors.signalText else BossTheme.colors.textSecondary,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onClear, enabled = events.isNotEmpty()) { Text("Clear view") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search tool name or provider ID") },
            )
            Spacer(Modifier.height(12.dp))
            McpActivityHeader()
            if (events.isEmpty()) {
                Text("No completed MCP activity yet.", color = BossTheme.colors.textSecondary)
            } else if (visibleEvents.isEmpty()) {
                Text("No activity matches the current filter.", color = BossTheme.colors.textSecondary)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = ACTIVITY_LIST_MAX_HEIGHT)) {
                    items(visibleEvents, key = { it.sequence }) { event -> McpActivityRow(event) }
                }
            }
        }
    }
}

@Composable
private fun McpActivityNotice(eventCount: Int) {
    Text(
        "This view keeps only execution metadata. The separate audit ledger may retain sanitized call details.",
        color = BossTheme.colors.textSecondary,
    )
    if (eventCount == McpActivityStore.CAPACITY) {
        Text(
            "Showing the most recent ${McpActivityStore.CAPACITY} events.",
            color = BossTheme.colors.textMuted,
            fontSize = 12.sp,
        )
    }
    Text(
        "Execution time excludes approval wait. Clear view affects all windows, not the audit ledger.",
        color = BossTheme.colors.textMuted,
        fontSize = 12.sp,
    )
}

@Composable
private fun McpActivityHeader() =
    Row(modifier = Modifier.fillMaxWidth()) {
        ActivityCell("STATUS", 0.9f)
        ActivityCell("TOOL NAME", 1.5f)
        ActivityCell("PROVIDER ID", 1.4f)
        ActivityCell("EXECUTION TIME", 1.1f)
        ActivityCell("COMPLETED", 1.8f)
    }

@Composable
private fun McpActivityRow(event: McpActivityEvent) =
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        ActivityCell(event.outcome.name, 0.9f)
        ActivityCell(displayMcpActivityIdentifier(event.toolName), 1.5f)
        ActivityCell(displayMcpActivityIdentifier(event.providerId), 1.4f)
        ActivityCell(formatMcpActivityDuration(event.durationMs), 1.1f)
        ActivityCell(formatMcpActivityCompletion(event.completedAtEpochMs), 1.8f)
    }

@Composable
private fun RowScope.ActivityCell(
    text: String,
    weight: Float,
) {
    Text(
        text = text,
        modifier = Modifier.weight(weight).padding(end = 8.dp),
        color = BossTheme.colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        fontSize = 12.sp,
    )
}

private fun McpActivityFilter.label(): String =
    when (this) {
        McpActivityFilter.ALL -> "All"
        McpActivityFilter.ERRORS -> "Errors"
        McpActivityFilter.TIMEOUTS -> "Timeouts"
        McpActivityFilter.CANCELLED -> "Cancelled"
    }
