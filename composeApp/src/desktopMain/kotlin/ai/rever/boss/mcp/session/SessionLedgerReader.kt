package ai.rever.boss.mcp.session

import ai.rever.boss.mcp.McpApprovalDisposition
import ai.rever.boss.mcp.McpOperationRecord
import ai.rever.boss.mcp.McpPolicyAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.RandomAccessFile

/** Records in the requested window, plus what could not be read. */
internal data class LedgerWindow(
    val records: List<McpOperationRecord>,
    /** Lines present in the file that could not be parsed. Reported, never thrown. */
    val malformedLines: Int,
    /** True when the tail cap was hit, so older calls in the window were not read. */
    val truncated: Boolean,
)

/**
 * Reads the MCP operation ledger back.
 *
 * The ledger is written by `McpOperationLedger` as append only JSONL at
 * `~/.boss/mcp-calls.jsonl`, rotating at 10 MB with up to 5 backups. This reads it, which nothing
 * else outside the host's own activity dialog does.
 *
 * Three properties matter more than the parsing:
 *
 * 1. **Only the tail is read.** A rotated ledger is 10 MB and the host must not pull that into
 *    memory to answer a tool call. The last [MAX_TAIL_BYTES] are read and the result says so.
 * 2. **A torn line is normal.** The writer appends from another coroutine while this reads, so the
 *    final line can be half written. Malformed lines are skipped and counted, never thrown.
 * 3. **In memory records win.** `McpOperationLedger` writes to disk asynchronously in a `finally`,
 *    so the most recent call may not be on disk yet. Its 100 entry ring buffer is merged in and
 *    deduplicated by `id`, which is why a `session_review` run immediately after a failure still
 *    sees that failure.
 */
internal object SessionLedgerReader {
    /** 2 MB of tail. Comfortably thousands of records, bounded against a rotated 10 MB file. */
    const val MAX_TAIL_BYTES: Long = 2L * 1024 * 1024

    /** Hard ceiling on parsed records, so an enormous window cannot swamp the heap. */
    const val MAX_RECORDS: Int = 5000

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Records at or after [sinceMs], newest last.
     *
     * [inMemory] is the host ledger's ring buffer. Passed in rather than read here so the analyzer
     * and its tests stay free of the registry singleton.
     */
    fun read(
        ledgerFile: File,
        inMemory: List<McpOperationRecord>,
        sinceMs: Long,
    ): LedgerWindow {
        val tail = readTail(ledgerFile)
        var malformed = 0
        val fromFile = mutableListOf<McpOperationRecord>()

        tail.lines.forEach { line ->
            if (line.isBlank()) return@forEach
            val record = parse(line)
            if (record == null) malformed++ else fromFile += record
        }

        // Disk first, then memory, so the deduplicating fold keeps the in memory copy of any call
        // present in both. They agree today, but memory is the writer's own object.
        val merged =
            (fromFile + inMemory)
                .filter { it.timestamp >= sinceMs }
                .associateBy { it.id }
                .values
                .sortedBy { it.timestamp }

        return LedgerWindow(
            records = merged.takeLast(MAX_RECORDS),
            malformedLines = malformed,
            truncated = tail.truncated || merged.size > MAX_RECORDS,
        )
    }

    private data class Tail(
        val lines: List<String>,
        val truncated: Boolean,
    )

    /**
     * The last [MAX_TAIL_BYTES] of the file, split into lines.
     *
     * The first line is dropped when the file was truncated, because seeking to a byte offset
     * almost always lands mid record and half a JSON object is not a parse failure worth counting.
     */
    // Swallowed deliberately: an unreadable ledger is an empty session, not a failed tool call.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun readTail(file: File): Tail {
        if (!file.isFile) return Tail(emptyList(), truncated = false)
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                val truncated = length > MAX_TAIL_BYTES
                val start = if (truncated) length - MAX_TAIL_BYTES else 0L
                raf.seek(start)
                val bytes = ByteArray((length - start).toInt())
                raf.readFully(bytes)
                val lines = bytes.toString(Charsets.UTF_8).split('\n')
                Tail(if (truncated) lines.drop(1) else lines, truncated)
            }
        } catch (t: Throwable) {
            // An unreadable ledger is an empty session, not a failed tool call. The caller still
            // answers, and says the ledger could not be read.
            Tail(emptyList(), truncated = false)
        }
    }

    /**
     * One JSONL line.
     *
     * Hand decoded rather than `@Serializable`, for two reasons. `McpOperationRecord` lives in
     * `commonMain` and is not annotated, and annotating a host type to suit a reader would be the
     * wrong direction of dependency. And a reader of an append only log written by another
     * process generation has to tolerate fields it does not know and enum values it has never
     * seen, which strict decoding turns into a hard failure.
     */
    // Swallowed deliberately: a torn or unknown line is counted by the caller, not logged. The
    // writer appends while this reads, so a malformed final line is an expected steady state, and
    // logging each one would turn normal operation into log noise.
    @Suppress("TooGenericExceptionCaught", "ReturnCount", "SwallowedException")
    fun parse(line: String): McpOperationRecord? =
        try {
            val obj = json.parseToJsonElement(line).jsonObject
            val id = obj.string("id")
            val toolName = obj.string("toolName")
            if (id == null || toolName == null) return null
            McpOperationRecord(
                id = id,
                timestamp = obj.long("timestamp") ?: return null,
                toolName = toolName,
                providerId = obj.string("providerId") ?: "unknown",
                policyApplied = obj.enumOr("policyApplied", McpPolicyAction.ALLOW),
                // An unknown disposition is treated as allowed rather than blocked: over reporting
                // "governance stopped you" would be a confusing lie, and a value this build does
                // not know is far more likely to be a newer allow variant than a newer denial.
                approvalDisposition = obj.enumOr("approvalDisposition", McpApprovalDisposition.AUTO_ALLOWED),
                durationMs = obj.long("durationMs") ?: 0L,
                isError = (obj["isError"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
                sanitizedArgs =
                    (obj["sanitizedArgs"] as? JsonObject)
                        ?.mapValues { (_, value) -> (value as? JsonPrimitive)?.content.orEmpty() }
                        .orEmpty(),
                errorSnippet = obj.string("errorSnippet"),
            )
        } catch (t: Throwable) {
            null
        }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString || it.content != "null" }?.content

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

    private inline fun <reified T : Enum<T>> JsonObject.enumOr(
        key: String,
        fallback: T,
    ): T {
        val raw = string(key) ?: return fallback
        return enumValues<T>().firstOrNull { it.name == raw } ?: fallback
    }
}
