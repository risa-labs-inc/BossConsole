package ai.rever.boss.mcp

import kotlin.math.ceil

/**
 * Aggregate reliability stats for one MCP tool, computed over some window of
 * [McpOperationRecord]s (typically [McpOperationLedger.recentOperations]).
 *
 * Pure/derived data — never persisted, always recomputed from the ledger's
 * records so it can never drift out of sync with them.
 */
data class McpToolStats(
    val toolName: String,
    val callCount: Int,
    val errorCount: Int,
    val errorRate: Double,
    val p50DurationMs: Long,
    val p95DurationMs: Long,
)

/**
 * Group [records] by tool name and compute per-tool call count, error rate,
 * and p50/p95 duration (nearest-rank, not interpolated — appropriate given how
 * small a single tool's sample usually is inside the ledger's 100-entry ring
 * buffer).
 *
 * Sorted highest error rate first, then highest call count — surfaces the
 * tool most worth an operator's attention first, not just the busiest one.
 */
fun computeToolStats(records: List<McpOperationRecord>): List<McpToolStats> =
    records
        .groupBy { it.toolName }
        .map { (toolName, toolRecords) ->
            val durations = toolRecords.map { it.durationMs }.sorted()
            val errorCount = toolRecords.count { it.isError }
            McpToolStats(
                toolName = toolName,
                callCount = toolRecords.size,
                errorCount = errorCount,
                errorRate = errorCount.toDouble() / toolRecords.size,
                p50DurationMs = percentile(durations, 0.50),
                p95DurationMs = percentile(durations, 0.95),
            )
        }.sortedWith(compareByDescending<McpToolStats> { it.errorRate }.thenByDescending { it.callCount })

/** Nearest-rank percentile over an already-sorted (ascending) list. Empty input returns 0. */
private fun percentile(
    sortedDurations: List<Long>,
    p: Double,
): Long {
    if (sortedDurations.isEmpty()) return 0L
    val rank = ceil(p * sortedDurations.size).toInt().coerceIn(1, sortedDurations.size)
    return sortedDurations[rank - 1]
}
