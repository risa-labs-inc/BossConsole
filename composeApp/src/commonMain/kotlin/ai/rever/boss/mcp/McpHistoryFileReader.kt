package ai.rever.boss.mcp

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Read [McpOperationRecord]s from the ledger's active file and its rotated
 * backups (`<file>.1` .. `<file>.<maxBackupIndex>`, as written by
 * [McpOperationLedger]'s size-based rotation).
 *
 * Read-only, on-demand counterpart to [McpOperationLedger.recentOperations]'s
 * in-memory ring buffer: the ring buffer only ever holds the newest 100
 * records, this reads whatever is still on disk beyond that.
 *
 * A malformed line (partial write mid-rotation, manual edit, etc.) is skipped
 * individually rather than failing the whole read - this mirrors the ledger's
 * own "never throws" posture, and read-only telemetry should fail open (show
 * what's readable), unlike the kill-switch's fail-closed posture for an actual
 * security decision.
 *
 * Deduplicated by [McpOperationRecord.id] (the active file and a not-yet-fully
 * -rotated backup can both contain the same record momentarily), returned
 * newest-first by [McpOperationRecord.timestamp], capped at [maxRecords].
 */
fun readHistoricalMcpRecords(
    ledgerFile: File,
    maxBackupIndex: Int = 5,
    maxRecords: Int = 1000,
): List<McpOperationRecord> {
    val json = Json { ignoreUnknownKeys = true }

    val candidateFiles =
        buildList {
            if (ledgerFile.exists()) add(ledgerFile)
            for (i in 1..maxBackupIndex) {
                val backup = File(ledgerFile.parentFile, "${ledgerFile.name}.$i")
                if (backup.exists()) add(backup)
            }
        }

    val byId = LinkedHashMap<String, McpOperationRecord>()
    for (file in candidateFiles) {
        val lines = runCatching { file.readLines() }.getOrNull() ?: continue
        for (line in lines) {
            if (line.isBlank()) continue
            val record = runCatching { json.decodeFromString<McpOperationRecord>(line) }.getOrNull() ?: continue
            byId[record.id] = record
        }
    }

    return byId.values.sortedByDescending { it.timestamp }.take(maxRecords)
}
