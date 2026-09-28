package ai.rever.boss.cli

import ai.rever.boss.components.dialogs.McpUnsuccessfulCategory
import ai.rever.boss.components.dialogs.unsuccessfulCategory
import ai.rever.boss.mcp.McpApprovalDisposition
import ai.rever.boss.mcp.McpLedgerBreakReason
import ai.rever.boss.mcp.McpLedgerEntry
import ai.rever.boss.mcp.McpLedgerReadException
import ai.rever.boss.mcp.McpLedgerVerification
import ai.rever.boss.mcp.McpOperationLedger
import ai.rever.boss.mcp.McpOperationRecord
import ai.rever.boss.mcp.secrets.SecretField
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

/**
 * The filters `tail`, `search` and `secrets` share, already parsed and validated by the caller.
 *
 * [secret] is a selector from [McpLedgerSecrets.parseSelector]: a lowercase secret id, which
 * matches a reference to any of its fields, or `<id>.<field>`, which matches that field only.
 */
internal data class McpLedgerQuery(
    val tool: String? = null,
    val category: McpUnsuccessfulCategory? = null,
    val fromMillis: Long? = null,
    val toMillis: Long? = null,
    val secret: String? = null,
    val provider: String? = null,
)

/**
 * Whether a call with this disposition handed its arguments - and so any resolved secret value -
 * to the tool's handler.
 *
 * Exhaustive rather than a set, for the reason the activity log's `unsuccessfulCategory` is: a
 * disposition added later is a compile error here instead of silently counted either way, and
 * for a credential audit both directions are wrong. Every approval disposition means the handler
 * ran, because an approval withdrawn at the revocation or secret fence is recorded as
 * [McpApprovalDisposition.POLICY_DENIED] or [McpApprovalDisposition.SECRET_FORBIDDEN] instead;
 * [McpApprovalDisposition.CANCELLED_IN_FLIGHT] is cancelled after the handler started, so it had the
 * value too.
 *
 * Legacy [McpApprovalDisposition.CANCELLED] is the one that cannot say which side it was on: it
 * predates the split into awaiting-approval and in-flight (#430, 2026-09-09). No record this report
 * counts should carry it, since `secretRefs` arrived later (#822, 2026-09-24) and nothing writes
 * `CANCELLED` any more. Should one appear anyway, it counts as delivered: for a credential audit,
 * reporting a possible delivery that did not happen is the safe mistake, and the reverse is not.
 */
internal val McpApprovalDisposition.reachedHandler: Boolean
    get() =
        when (this) {
            McpApprovalDisposition.AUTO_ALLOWED,
            McpApprovalDisposition.APPROVED_ONCE,
            McpApprovalDisposition.SESSION_TRUSTED,
            McpApprovalDisposition.PERSISTENTLY_ALLOWED,
            McpApprovalDisposition.PROVIDER_TRUSTED,
            McpApprovalDisposition.PROVIDER_TRUST_PERSIST_FAILED,
            McpApprovalDisposition.YOLO_ALLOWED,
            McpApprovalDisposition.CANCELLED_IN_FLIGHT,
            McpApprovalDisposition.CANCELLED,
            -> true

            McpApprovalDisposition.PERSISTENTLY_DENIED,
            McpApprovalDisposition.POLICY_PERSIST_FAILED,
            McpApprovalDisposition.DENIED_BY_OPERATOR,
            McpApprovalDisposition.TIMEOUT,
            McpApprovalDisposition.POLICY_DENIED,
            McpApprovalDisposition.CANCELLED_AWAITING_APPROVAL,
            McpApprovalDisposition.QUEUE_FULL,
            McpApprovalDisposition.INVALID_ARGUMENTS,
            McpApprovalDisposition.SECRET_FORBIDDEN,
            McpApprovalDisposition.SECRET_UNRESOLVED,
            McpApprovalDisposition.YOLO_ENABLED,
            McpApprovalDisposition.YOLO_DISABLED,
            -> false
        }

/** Every recorded use of one secret, across the records a `secrets` query matched. */
internal data class McpSecretUse(
    val id: String,
    val fields: Set<String>,
    val calls: Int,
    val delivered: Int,
    val tools: Map<String, Int>,
    val providers: Set<String>,
    val firstMillis: Long,
    val lastMillis: Long,
    val lastDeliveredMillis: Long?,
) {
    val withheld: Int get() = calls - delivered
}

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
                return McpLedgerOutcome.Failed("Error: ${e.message}")
            } catch (e: Exception) {
                return McpLedgerOutcome.Failed("Error: cannot read ${ledgerFile.absolutePath}: ${e.message}")
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
            McpLedgerOutcome.Failed("Error: ${e.message}")
        } catch (e: Exception) {
            McpLedgerOutcome.Failed("Error: cannot read the MCP operation ledger: ${e.message}")
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
            McpLedgerOutcome.Failed("Error: ${e.message}")
        } catch (e: Exception) {
            McpLedgerOutcome.Failed("Error: cannot read the MCP operation ledger: ${e.message}")
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
            // A plugin's tools record `<pluginId>::<providerId>`, so the plugin id alone has to match
            // every provider it registered: an audit that silently finds nothing for a plugin reads as
            // "this plugin never received it".
            val providerMatches =
                query.provider == null ||
                    record.providerId == query.provider ||
                    record.providerId.substringBefore("::") == query.provider
            val secretMatches =
                query.secret == null ||
                    record.secretRefs.any { McpLedgerSecrets.matches(it.lowercase(Locale.ROOT), query.secret) }
            val afterFrom = query.fromMillis == null || record.timestamp >= query.fromMillis
            val beforeTo = query.toMillis == null || record.timestamp <= query.toMillis
            toolMatches && providerMatches && secretMatches && categoryMatches && afterFrom && beforeTo
        }

    /**
     * Every record on disk in write order. Missing is an error rather than an empty result: an
     * operator asking to read an audit trail needs to know there is no audit trail, not to be told
     * there is nothing in it.
     */
    internal fun readEntries(ledgerFile: File): List<McpLedgerEntry> {
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

/**
 * `boss mcp ledger secrets`, and the `--secret` selector `tail` and `search` share: which calls
 * referenced a secret, which of them handed it to a handler, and when. Its own object so the read
 * path in [McpLedgerCli] stays the size its other three actions need.
 */
internal object McpLedgerSecrets {
    /**
     * One summary per secret the matching records referenced, most recently used first: how many
     * calls named it, how many handed it to a handler and how many were withheld, which tools and
     * plugins asked, and when. The question an operator rotating a credential after an incident
     * has to answer, and which `search` could only answer one record at a time.
     *
     * Reads only what the ledger records - `secretRefs`, never a value - so it needs no vault
     * access and works with BOSS closed.
     */
    @Suppress("TooGenericExceptionCaught") // Any read failure must be reported, never passed over.
    fun secrets(
        fileOverride: String?,
        query: McpLedgerQuery,
        json: Boolean,
    ): McpLedgerOutcome =
        try {
            val ledgerFile = McpLedgerCli.resolveLedgerFile(fileOverride)
            val entries = McpLedgerCli.filterEntries(McpLedgerCli.readEntries(ledgerFile), query)
            val uses = uses(entries, query.secret)
            McpLedgerOutcome.Ok(
                if (json) {
                    McpLedgerFormat.secretUsesJson(ledgerFile, uses).toString()
                } else {
                    McpLedgerFormat.secretUses(ledgerFile, uses)
                },
            )
        } catch (e: McpLedgerReadException) {
            McpLedgerOutcome.Failed("Error: ${e.message}")
        } catch (e: Exception) {
            McpLedgerOutcome.Failed("Error: cannot read the MCP operation ledger: ${e.message}")
        }

    /**
     * Folds records into one [McpSecretUse] per secret id. With a field-level [selector], only that
     * field's references count, so `--secret <id>.username` never reports the password's use.
     */
    fun uses(
        entries: List<McpLedgerEntry>,
        selector: String?,
    ): List<McpSecretUse> {
        val byId = linkedMapOf<String, MutableList<Pair<McpOperationRecord, String>>>()
        for (entry in entries) {
            for (ref in entry.record.secretRefs) {
                val normalized = ref.lowercase(Locale.ROOT)
                if (selector != null && !matches(normalized, selector)) continue
                byId.getOrPut(normalized.substringBefore('.')) { mutableListOf() } += entry.record to normalized
            }
        }
        return byId
            .map { (id, refs) ->
                // One call referencing two fields of one secret is one call, not two.
                val calls = refs.map { it.first }.distinctBy { it.id }
                val delivered = calls.filter { it.approvalDisposition.reachedHandler }
                McpSecretUse(
                    id = id,
                    // `ledgerName` always writes `<id>.<field>`. A reference with no field names the
                    // password, as `{{secret:<id>}}` does, so that is the field it is reported under.
                    fields = refs.map { it.second.substringAfter('.', "password") }.toSortedSet(),
                    calls = calls.size,
                    delivered = delivered.size,
                    tools = calls.groupingBy { it.toolName }.eachCount().toSortedMap(),
                    providers = calls.map { it.providerId }.toSortedSet(),
                    firstMillis = calls.minOf { it.timestamp },
                    lastMillis = calls.maxOf { it.timestamp },
                    lastDeliveredMillis = delivered.maxOfOrNull { it.timestamp },
                )
            }.sortedByDescending { it.lastMillis }
    }

    /**
     * A `--secret` value: a secret id (a UUID, the only ids the vault issues), optionally with one
     * of the fields a reference can name, returned lowercase. Null when [raw] is neither, so a
     * typo is reported rather than silently matching nothing.
     */
    fun parseSelector(raw: String?): String? {
        val text = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val id = text.substringBefore('.')
        val field = text.substringAfter('.', "")
        val fieldOk = !text.contains('.') || field in SecretField.entries.map { it.wireName }
        return text.takeIf { secretIdPattern.matches(id) && fieldOk }
    }

    /** A recorded reference `<id>.<field>` against a selector: the id alone matches every field. */
    fun matches(
        ref: String,
        selector: String,
    ): Boolean = ref == selector || (!selector.contains('.') && ref.substringBefore('.') == selector)

    private val secretIdPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
}

/** Human and JSON rendering for the `boss mcp ledger` actions. */
internal object McpLedgerFormat {
    /** ISO-8601 in UTC, so a timestamp in a report cannot be misread as local time. */
    fun timestamp(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

    fun verification(
        ledgerFile: File,
        verification: McpLedgerVerification,
    ): String =
        buildString {
            appendLine("Ledger:  ${ledgerFile.absolutePath}")
            appendLine("Files:   ${verification.files.size} (${verification.files.joinToString(", ")})")
            appendLine(
                "Records: ${verification.totalRecords} " +
                    "(${verification.chainedRecords} chained, " +
                    "${verification.unverifiableRecords} unverifiable)",
            )
            val broken = verification.firstBreak
            if (broken != null) {
                appendLine(
                    "Chain:   BROKEN - first break at record ${broken.recordIndex} " +
                        "of ${verification.totalRecords}, ${broken.fileName} line ${broken.lineNumber}",
                )
                appendLine("         ${broken.recordId} at ${timestamp(broken.timestamp)}")
                appendLine("         reason:   ${reasonText(broken.reason)}")
                appendLine("         expected ${broken.expectedHash}")
                appendLine("         found    ${broken.foundHash}")
            } else if (verification.coverageGaps.isNotEmpty()) {
                appendLine(
                    "Chain:   INCOMPLETE - ${verification.coverageGaps.joinToString(", ")} missing, " +
                        "so the chain could not be followed across that gap",
                )
            } else if (verification.verdict == "intact") {
                appendLine(
                    "Chain:   intact - oldest verifiable record is " +
                        "${verification.oldestVerifiableFile} line ${verification.oldestVerifiableLine}",
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
        val toolWidth = entries.maxOf { it.record.toolName.length }.coerceAtMost(40)
        val policyWidth = entries.maxOf { policyOf(it.record).length }
        val body =
            entries.joinToString("\n") { entry ->
                val record = entry.record
                buildString {
                    append(timestamp(record.timestamp))
                    append("  ")
                    append(record.toolName.padEnd(toolWidth))
                    append("  ")
                    append(policyOf(record).padEnd(policyWidth))
                    append("  ")
                    append(record.durationMs.toString().padStart(6))
                    append("ms  ")
                    append(if (record.isError) "error" else "ok   ")
                    append("  hash ")
                    append(record.hash?.take(12) ?: "unverifiable")
                    record.errorSnippet?.let { append("\n    error: ").append(it) }
                    if (record.secretRefs.isNotEmpty()) {
                        append("\n    secrets: ").append(record.secretRefs.joinToString())
                    }
                    if (record.escalated) {
                        append("\n    escalated: rated CRITICAL, so a saved allow did not cover it")
                    }
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

    fun secretUses(
        ledgerFile: File,
        uses: List<McpSecretUse>,
    ): String {
        // Said on an empty report too: "nothing found" is the answer an operator rotating a
        // credential acts on, and it only covers what rotation has kept.
        val scope =
            "\n\nLedger: ${ledgerFile.absolutePath} and its rotated backups. A use older than the oldest " +
                "backup is not counted.\nRun `boss mcp ledger verify` to check that this history is intact."
        if (uses.isEmpty()) return "No matching ledger record references a secret.$scope"
        val body =
            uses.joinToString("\n\n") { use ->
                buildString {
                    append(use.id)
                    append("  ")
                    append("${use.calls} call(s): ${use.delivered} delivered to a handler, ${use.withheld} withheld")
                    append("\n  fields:    ").append(use.fields.joinToString())
                    append("\n  tools:     ")
                    append(use.tools.entries.joinToString { (tool, count) -> "$tool ($count)" })
                    append("\n  providers: ").append(use.providers.joinToString())
                    append("\n  first:     ").append(timestamp(use.firstMillis))
                    append("\n  last:      ").append(timestamp(use.lastMillis))
                    append("\n  delivered: ").append(use.lastDeliveredMillis?.let(::timestamp) ?: "never")
                }
            }
        return body + "\n\n${uses.size} secret(s). Only references are recorded; no value is read or shown.$scope"
    }

    fun secretUsesJson(
        ledgerFile: File,
        uses: List<McpSecretUse>,
    ): JsonObject =
        buildJsonObject {
            put("path", ledgerFile.absolutePath.replace('\\', '/'))
            put(
                "secrets",
                buildJsonArray {
                    uses.forEach { use ->
                        add(
                            buildJsonObject {
                                put("id", use.id)
                                put("fields", buildJsonArray { use.fields.forEach { add(it) } })
                                put("calls", use.calls)
                                put("delivered", use.delivered)
                                put("withheld", use.withheld)
                                put("tools", buildJsonObject { use.tools.forEach { (tool, n) -> put(tool, n) } })
                                put("providers", buildJsonArray { use.providers.forEach { add(it) } })
                                put("firstTimestamp", use.firstMillis)
                                put("lastTimestamp", use.lastMillis)
                                put("lastDeliveredTimestamp", use.lastDeliveredMillis)
                            },
                        )
                    }
                },
            )
        }

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
            put("secretRefs", buildJsonArray { record.secretRefs.forEach { add(it) } })
            put("escalated", record.escalated)
            put("hash", record.hash)
            put("parentHash", record.parentHash)
            put("file", entry.file.name)
            put("line", entry.lineNumber)
        }
    }
}
