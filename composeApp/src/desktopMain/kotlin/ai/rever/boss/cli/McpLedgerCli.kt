package ai.rever.boss.cli

import ai.rever.boss.components.dialogs.McpUnsuccessfulCategory
import ai.rever.boss.components.dialogs.unsuccessfulCategory
import ai.rever.boss.mcp.McpLedgerBreakReason
import ai.rever.boss.mcp.McpLedgerEntry
import ai.rever.boss.mcp.McpLedgerReadException
import ai.rever.boss.mcp.McpLedgerVerification
import ai.rever.boss.mcp.McpOperationLedger
import ai.rever.boss.mcp.McpOperationRecord
import ai.rever.boss.plugin.pathutils.BossDirectories
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

/**
 * What a `boss mcp ledger` action produced.
 *
 * [Ok] goes to stdout with exit 0. [Failed] goes to stderr with exit 1, which is why a broken chain
 * arrives as [Failed] even though it is a report rather than a crash: `docs/CLI.md` promises that
 * exit 1 writes its description strictly to stderr and leaves stdout clean, so a script piping
 * `boss mcp ledger tail` into another tool cannot be handed a failure report as if it were data.
 */
internal sealed interface McpLedgerOutcome {
    data class Ok(
        val text: String,
    ) : McpLedgerOutcome

    data class Failed(
        val message: String,
    ) : McpLedgerOutcome
}

/** The filters `tail` and `search` share, already parsed and validated by the caller. */
internal data class McpLedgerQuery(
    val tool: String? = null,
    val category: McpUnsuccessfulCategory? = null,
    val fromMillis: Long? = null,
    val toMillis: Long? = null,
)

/** An exception's message as the operator's terminal may safely see it; it can quote ledger content. */
private fun reasonOf(e: Exception): String = TerminalText.safe(e.message.orEmpty())

/**
 * The read side of the durable MCP operation ledger, for `boss mcp ledger verify|tail|search`.
 *
 * Reads `~/.boss/mcp-calls.jsonl` and its rotated backups directly off disk. Nothing here goes
 * through the running app or through [ai.rever.boss.plugin.api.PluginContext], and that is
 * deliberate: `AGENTS.md` forbids handing an installed plugin the ledger's tool names, sanitized
 * arguments and error snippets, and blesses an operator-only read of the same data. A local,
 * read-only command line is exactly that, and it is the only way to inspect an audit trail on a
 * machine where BOSS is not running at all.
 */
internal object McpLedgerCli {
    /** The ledger file the app writes under `~/.boss`. */
    const val LEDGER_FILE_NAME = "mcp-calls.jsonl"

    private const val DAY_MILLIS = 86_400_000L

    /** The ledger to read: `--file` when given, otherwise the path the app writes to. */
    fun resolveLedgerFile(fileOverride: String?): File =
        fileOverride?.takeIf { it.isNotBlank() }?.let { File(it).absoluteFile }
            ?: BossDirectories.resolve(LEDGER_FILE_NAME)

    /**
     * Checks the chain and reports either that it is intact or exactly where it first breaks.
     *
     * An intact chain is a success; a break, and a ledger that could not be read or fully covered,
     * are both failures, because "we could not check it" must never be reported as "it is fine".
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught") // Any read failure must be reported, never passed over.
    fun verify(
        fileOverride: String?,
        json: Boolean,
    ): McpLedgerOutcome {
        val ledgerFile = resolveLedgerFile(fileOverride)
        val verification =
            try {
                requireLedger(ledgerFile)
                McpOperationLedger(ledgerFile = ledgerFile).verifyChain()
            } catch (e: McpLedgerReadException) {
                return McpLedgerOutcome.Failed("Error: ${reasonOf(e)}")
            } catch (e: Exception) {
                val where = TerminalText.safe(ledgerFile.absolutePath)
                return McpLedgerOutcome.Failed("Error: cannot read $where: ${reasonOf(e)}")
            }
        val text =
            if (json) {
                McpLedgerFormat.verificationJson(ledgerFile, verification).toString()
            } else {
                McpLedgerFormat.verification(ledgerFile, verification)
            }
        return if (verification.verdict == "intact") McpLedgerOutcome.Ok(text) else McpLedgerOutcome.Failed(text)
    }

    /**
     * The newest matching records, newest first.
     *
     * [lines] caps the result, so this is a window onto the durable file rather than a way to dump
     * it: `verify` is what reads every record.
     */
    @Suppress("TooGenericExceptionCaught") // Any read failure must be reported, never passed over.
    fun tail(
        fileOverride: String?,
        lines: Int,
        query: McpLedgerQuery,
        json: Boolean,
    ): McpLedgerOutcome =
        try {
            val ledgerFile = resolveLedgerFile(fileOverride)
            val matched = filterEntries(readEntries(ledgerFile), query)
            val shown = matched.takeLast(lines.coerceAtLeast(1)).reversed()
            McpLedgerOutcome.Ok(
                if (json) {
                    McpLedgerFormat.recordsJson(ledgerFile, shown, matched.size).toString()
                } else {
                    McpLedgerFormat.records(shown, matched.size)
                },
            )
        } catch (e: McpLedgerReadException) {
            McpLedgerOutcome.Failed("Error: ${reasonOf(e)}")
        } catch (e: Exception) {
            McpLedgerOutcome.Failed("Error: cannot read the MCP operation ledger: ${reasonOf(e)}")
        }

    /** Matching records newest first, capped at [limit], with the total number of matches. */
    @Suppress("TooGenericExceptionCaught") // Any read failure must be reported, never passed over.
    fun search(
        fileOverride: String?,
        limit: Int,
        query: McpLedgerQuery,
        json: Boolean,
    ): McpLedgerOutcome =
        try {
            val ledgerFile = resolveLedgerFile(fileOverride)
            val matched = filterEntries(readEntries(ledgerFile), query)
            val shown = matched.takeLast(limit.coerceAtLeast(1)).reversed()
            McpLedgerOutcome.Ok(
                if (json) {
                    McpLedgerFormat.recordsJson(ledgerFile, shown, matched.size).toString()
                } else {
                    McpLedgerFormat.records(shown, matched.size)
                },
            )
        } catch (e: McpLedgerReadException) {
            McpLedgerOutcome.Failed("Error: ${reasonOf(e)}")
        } catch (e: Exception) {
            McpLedgerOutcome.Failed("Error: cannot read the MCP operation ledger: ${reasonOf(e)}")
        }

    /**
     * The four categories `--disposition` accepts, or null when [raw] names none of them.
     *
     * [Locale.ROOT], not the default locale: under `tr-TR` a default-locale lowercase turns
     * "WITHHELD" into "wıthheld", so the option would be rejected on that machine only.
     */
    fun parseCategory(raw: String?): McpUnsuccessfulCategory? {
        val text = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return McpUnsuccessfulCategory.entries.firstOrNull { it.label == text }
    }

    /** The `--disposition` values, in the order the activity log renders them. */
    fun categoryLabels(): String = McpUnsuccessfulCategory.entries.joinToString(", ") { it.label }

    /**
     * Parses a `--from`/`--to` bound: epoch milliseconds, `YYYY-MM-DD`, or an ISO-8601 instant or
     * local date-time. A date, or a date-time with no offset, is read as UTC so that a bound means
     * the same thing whatever the operator's timezone is.
     *
     * Returns null for anything it cannot read. The caller checks for a blank bound first, so a
     * null here always means "unparseable" and is reported rather than silently ignored.
     */
    @Suppress("ReturnCount") // Each accepted time representation exits as soon as it parses.
    fun parseTime(
        raw: String?,
        endOfDay: Boolean,
    ): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        text.toLongOrNull()?.let { return it }
        runCatching { LocalDate.parse(text) }.getOrNull()?.let { date ->
            val start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            return if (endOfDay) start + DAY_MILLIS - 1 else start
        }
        runCatching { Instant.parse(text) }.getOrNull()?.let { return it.toEpochMilli() }
        runCatching { LocalDateTime.parse(text) }.getOrNull()?.let {
            return it.toInstant(ZoneOffset.UTC).toEpochMilli()
        }
        return null
    }

    fun filterEntries(
        entries: List<McpLedgerEntry>,
        query: McpLedgerQuery,
    ): List<McpLedgerEntry> =
        entries.filter { entry ->
            val record = entry.record
            val categoryMatches =
                query.category == null ||
                    (record.isError && record.approvalDisposition.unsuccessfulCategory == query.category)
            val toolMatches = query.tool == null || record.toolName == query.tool
            val afterFrom = query.fromMillis == null || record.timestamp >= query.fromMillis
            val beforeTo = query.toMillis == null || record.timestamp <= query.toMillis
            toolMatches && categoryMatches && afterFrom && beforeTo
        }

    /**
     * Every record on disk in write order. Missing is an error rather than an empty result: an
     * operator asking to read an audit trail needs to know there is no audit trail, not to be told
     * there is nothing in it.
     */
    private fun readEntries(ledgerFile: File): List<McpLedgerEntry> {
        requireLedger(ledgerFile)
        return McpOperationLedger(ledgerFile = ledgerFile).readEntries()
    }

    private fun requireLedger(ledgerFile: File) {
        if (!ledgerFile.isFile && McpOperationLedger(ledgerFile = ledgerFile).ledgerFilesOldestFirst().isEmpty()) {
            throw McpLedgerReadException(
                "No MCP operation ledger at ${ledgerFile.absolutePath}. " +
                    "Records appear here after a governed MCP tool call.",
            )
        }
    }
}

/** Human and JSON rendering for the `boss mcp ledger` actions. */
internal object McpLedgerFormat {
    /** ISO-8601 in UTC, so a timestamp in a report cannot be misread as local time. */
    fun timestamp(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

    /** [text] made safe to print; see [TerminalText.safe]. */
    fun terminalSafe(text: String): String = TerminalText.safe(text)

    fun verification(
        ledgerFile: File,
        verification: McpLedgerVerification,
    ): String =
        buildString {
            appendLine("Ledger:  ${terminalSafe(ledgerFile.absolutePath)}")
            val files = verification.files.joinToString(", ") { terminalSafe(it) }
            appendLine("Files:   ${verification.files.size} ($files)")
            appendLine(
                "Records: ${verification.totalRecords} " +
                    "(${verification.chainedRecords} chained, " +
                    "${verification.unverifiableRecords} unverifiable)",
            )
            val broken = verification.firstBreak
            if (broken != null) {
                appendLine(
                    "Chain:   BROKEN - first break at record ${broken.recordIndex} " +
                        "of ${verification.totalRecords}, ${terminalSafe(broken.fileName)} line ${broken.lineNumber}",
                )
                appendLine("         ${terminalSafe(broken.recordId)} at ${timestamp(broken.timestamp)}")
                appendLine("         reason:   ${reasonText(broken.reason)}")
                appendLine("         expected ${terminalSafe(broken.expectedHash)}")
                appendLine("         found    ${terminalSafe(broken.foundHash)}")
            } else if (verification.coverageGaps.isNotEmpty()) {
                val gaps = verification.coverageGaps.joinToString(", ") { terminalSafe(it) }
                appendLine(
                    "Chain:   INCOMPLETE - $gaps missing, " +
                        "so the chain could not be followed across that gap",
                )
            } else if (verification.verdict == "intact") {
                val oldest = verification.oldestVerifiableFile?.let(::terminalSafe)
                appendLine(
                    "Chain:   intact - oldest verifiable record is " +
                        "$oldest line ${verification.oldestVerifiableLine}",
                )
            } else {
                val explanation =
                    when {
                        verification.totalRecords == 0 -> {
                            "the ledger is empty"
                        }

                        verification.chainedRecords == 0 -> {
                            "every record predates integrity tracking; write a new record to anchor the chain"
                        }

                        else -> {
                            "${verification.unverifiableSuffixRecords} unhashed record(s) follow hashed history"
                        }
                    }
                appendLine("Chain:   UNVERIFIABLE - $explanation")
            }
            if (verification.unverifiableRecords > 0) {
                appendLine(
                    "Note:    ${verification.unverifiableRecords} record(s) carry no hash and were not " +
                        "checked (written before integrity tracking)",
                )
            }
        }.trimEnd()

    private fun reasonText(reason: McpLedgerBreakReason): String =
        when (reason) {
            McpLedgerBreakReason.RECORD_ALTERED -> "the record no longer hashes to the value it stores"
            McpLedgerBreakReason.LINK_BROKEN -> "the record chains to a different predecessor than the one before it"
        }

    fun records(
        entries: List<McpLedgerEntry>,
        totalMatches: Int,
    ): String {
        if (entries.isEmpty()) {
            return if (totalMatches == 0) "No matching ledger records." else "No records to show."
        }
        val toolWidth = entries.maxOf { terminalSafe(it.record.toolName).length }.coerceAtMost(40)
        val policyWidth = entries.maxOf { policyOf(it.record).length }
        val body =
            entries.joinToString("\n") { entry ->
                val record = entry.record
                buildString {
                    append(timestamp(record.timestamp))
                    append("  ")
                    append(terminalSafe(record.toolName).padEnd(toolWidth))
                    append("  ")
                    append(policyOf(record).padEnd(policyWidth))
                    append("  ")
                    append(record.durationMs.toString().padStart(6))
                    append("ms  ")
                    append(if (record.isError) "error" else "ok   ")
                    append("  hash ")
                    append(record.hash?.take(12)?.let(::terminalSafe) ?: "unverifiable")
                    record.errorSnippet?.let { append("\n    error: ").append(terminalSafe(it)) }
                }
            }
        val footer =
            if (entries.size < totalMatches) {
                "\n\nShowing ${entries.size} of $totalMatches matching records."
            } else {
                "\n\n${entries.size} matching record(s)."
            }
        return body + footer
    }

    fun verificationJson(
        ledgerFile: File,
        verification: McpLedgerVerification,
    ): JsonObject {
        val broken: JsonElement =
            verification.firstBreak?.let { entry ->
                buildJsonObject {
                    put("reason", entry.reason.name.lowercase(Locale.ROOT))
                    put("file", entry.fileName)
                    put("line", entry.lineNumber)
                    put("recordIndex", entry.recordIndex)
                    put("recordId", entry.recordId)
                    put("timestamp", entry.timestamp)
                    put("timestampIso", timestamp(entry.timestamp))
                    put("expectedHash", entry.expectedHash)
                    put("foundHash", entry.foundHash)
                }
            } ?: JsonNull
        return buildJsonObject {
            put("path", ledgerFile.absolutePath.replace('\\', '/'))
            put("verdict", verification.verdict)
            put("files", buildJsonArray { verification.files.forEach { add(it) } })
            put("totalRecords", verification.totalRecords)
            put("chainedRecords", verification.chainedRecords)
            put("unverifiableRecords", verification.unverifiableRecords)
            put("unverifiableSuffixRecords", verification.unverifiableSuffixRecords)
            put("oldestVerifiableFile", verification.oldestVerifiableFile)
            put("oldestVerifiableLine", verification.oldestVerifiableLine)
            put("coverageGaps", buildJsonArray { verification.coverageGaps.forEach { add(it) } })
            put("firstBreak", broken)
        }
    }

    fun recordsJson(
        ledgerFile: File,
        entries: List<McpLedgerEntry>,
        totalMatches: Int,
    ): JsonObject =
        buildJsonObject {
            put("path", ledgerFile.absolutePath.replace('\\', '/'))
            put("matched", totalMatches)
            put("shown", entries.size)
            put("records", buildJsonArray { entries.forEach { add(recordJson(it)) } })
        }

    private fun policyOf(record: McpOperationRecord): String = "${record.policyApplied}/${record.approvalDisposition}"

    private fun recordJson(entry: McpLedgerEntry): JsonObject {
        val record = entry.record
        return buildJsonObject {
            put("id", record.id)
            put("timestamp", record.timestamp)
            put("timestampIso", timestamp(record.timestamp))
            put("toolName", record.toolName)
            put("providerId", record.providerId)
            put("policyApplied", record.policyApplied.name)
            put("approvalDisposition", record.approvalDisposition.name)
            put("durationMs", record.durationMs)
            put("isError", record.isError)
            put(
                "sanitizedArgs",
                buildJsonObject {
                    record.sanitizedArgs.toSortedMap().forEach { (key, value) -> put(key, value) }
                },
            )
            put("errorSnippet", record.errorSnippet)
            put("hash", record.hash)
            put("parentHash", record.parentHash)
            put("file", entry.file.name)
            put("line", entry.lineNumber)
        }
    }
}
