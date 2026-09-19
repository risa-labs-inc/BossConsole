package ai.rever.boss.mcp.loom

import ai.rever.boss.mcp.sandbox.DefaultMcpRiskEvaluator
import ai.rever.boss.plugin.api.McpToolArgs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * Bounds. A corrupt or hostile ledger must not be able to make the replay allocate without limit,
 * and a single enormous argument value must not make the artifact unopenable.
 */
private const val MAX_EVENTS = 200_000
private const val MAX_DIAGNOSTICS = 500
private const val ARG_VALUE_LIMIT = 400
private const val ERROR_SNIPPET_LIMIT = 2_000

/** What BOSS did with a call, collapsed from the recorded policy action and approval disposition. */
enum class LoomOutcome {
    /** Executed with no prompt. */
    ALLOWED,

    /** A prompt was raised and the operator allowed it, at some scope. */
    APPROVED,

    /** Refused, by policy or by the operator. */
    DENIED,

    /** Nobody answered the prompt in time. */
    TIMED_OUT,

    /** Cancelled before or during execution. */
    CANCELLED,

    /** Refused because the execution queue was full. */
    QUEUE_REJECTED,

    /** The ledger recorded a state this build does not classify. The raw value is still shown. */
    UNKNOWN,
}

/**
 * One ledger record, reconstructed for replay.
 *
 * Every field here is either read from the ledger or derived from it, and [defaulted] names the ones
 * the ledger did not carry so the viewer can print "not recorded" rather than a plausible guess.
 * [riskLevel] is the one field that is never in the ledger at all: it is re-derived from the tool
 * name and arguments by the same evaluator the runtime uses, and the viewer labels it as derived.
 */
@Serializable
data class LoomEvent(
    val index: Int,
    val id: String,
    val timestamp: Long,
    val offsetMs: Long,
    val toolName: String,
    val providerId: String,
    val policyApplied: String,
    val approvalDisposition: String,
    val outcome: String,
    val isError: Boolean,
    val durationMs: Long,
    val riskLevel: String,
    val riskReason: String,
    val args: Map<String, String>,
    val errorSnippet: String?,
    val hash: String?,
    val parentHash: String?,
    val source: String,
    val lineNumber: Int,
    val defaulted: List<String>,
)

/** A line that could not be reconstructed. Kept, never dropped silently. */
@Serializable
data class LoomDiagnostic(
    val source: String,
    val lineNumber: Int,
    val reason: String,
)

/** A labelled count, used for the tool, risk and outcome breakdowns. */
@Serializable
data class LoomBucket(
    val label: String,
    val count: Int,
)

/**
 * A whole reconstructed session, and the only thing the viewer needs.
 *
 * The viewer never reads the ledger. This structure is serialized into the exported HTML, so
 * everything the artifact can show is decided here, where it is testable.
 */
@Serializable
data class LoomSession(
    val schemaVersion: Int,
    val generatedAt: Long,
    val sources: List<String>,
    val coverageGaps: List<String>,
    val startedAt: Long?,
    val endedAt: Long?,
    val durationMs: Long,
    val events: List<LoomEvent>,
    val diagnostics: List<LoomDiagnostic>,
    val toolUsage: List<LoomBucket>,
    val riskDistribution: List<LoomBucket>,
    val outcomeDistribution: List<LoomBucket>,
    val notes: List<String>,
)

/** The result of reading ledger text: what reconstructed, and what did not. */
data class LoomParse(
    val events: List<LoomEvent>,
    val diagnostics: List<LoomDiagnostic>,
)

/**
 * Reads persisted MCP ledger records into a replayable session.
 *
 * **The ledger is the only source.** Nothing here reads runtime state, and nothing here writes to
 * the ledger. A line that cannot be reconstructed becomes a [LoomDiagnostic] and the rest of the
 * session still replays: a single corrupt line must not cost an operator the audit trail around it.
 *
 * Records are ordered by `timestamp`, with the file order that produced them as the tie-break, so a
 * session with equal-millisecond records still replays in the order they were written.
 */
// Read, parse, order, fold and summarise are the five stages of one reconstruction, and splitting
// them into collaborators would hide the pipeline the class KDoc describes.
@Suppress("TooManyFunctions")
object AgentLoom {
    /** Bumped when [LoomSession] changes shape, so an old artifact is recognisable as old. */
    const val SCHEMA_VERSION = 1

    /** The ledger file the runtime writes, resolved through the same directory the app uses. */
    const val LEDGER_FILE_NAME = "mcp-calls.jsonl"

    private val json = Json { ignoreUnknownKeys = true }

    private val riskEvaluator = DefaultMcpRiskEvaluator()

    /**
     * Read every file in [files], oldest first, and order the result chronologically.
     *
     * [files] is expected in write order, which is what the ledger's own rotation ordering gives.
     */
    fun read(files: List<File>): LoomParse {
        val events = mutableListOf<LoomEvent>()
        val diagnostics = mutableListOf<LoomDiagnostic>()
        files.forEach { file ->
            val text =
                try {
                    if (file.isFile) file.readText() else ""
                } catch (
                    @Suppress("TooGenericExceptionCaught") failure: Exception,
                ) {
                    diagnostics += LoomDiagnostic(file.name, 0, "unreadable: ${failure::class.simpleName}")
                    ""
                }
            parseInto(text, file.name, events, diagnostics)
        }
        return LoomParse(order(events), diagnostics.take(MAX_DIAGNOSTICS))
    }

    /** Parse ledger text. [source] is the file name shown next to each event. */
    fun parse(
        text: String,
        source: String,
    ): LoomParse {
        val events = mutableListOf<LoomEvent>()
        val diagnostics = mutableListOf<LoomDiagnostic>()
        parseInto(text, source, events, diagnostics)
        return LoomParse(order(events), diagnostics.take(MAX_DIAGNOSTICS))
    }

    /** Fold a parse result into the structure the viewer renders. */
    fun session(
        parse: LoomParse,
        sources: List<String>,
        coverageGaps: List<String> = emptyList(),
        generatedAt: Long = System.currentTimeMillis(),
    ): LoomSession {
        val events = parse.events
        val first = events.firstOrNull()
        val last = events.lastOrNull()
        return LoomSession(
            schemaVersion = SCHEMA_VERSION,
            generatedAt = generatedAt,
            sources = sources,
            coverageGaps = coverageGaps,
            startedAt = first?.timestamp,
            endedAt = last?.timestamp,
            durationMs = if (first == null || last == null) 0L else last.timestamp - first.timestamp,
            events = events,
            diagnostics = parse.diagnostics,
            toolUsage = bucketBy(events) { it.toolName },
            riskDistribution = distribution(events, RISK_ORDER) { it.riskLevel },
            outcomeDistribution = distribution(events, OUTCOME_ORDER) { it.outcome },
            notes = notesFor(events, parse.diagnostics, coverageGaps),
        )
    }

    /** Read [files] and fold them into a session in one step. */
    fun replay(
        files: List<File>,
        generatedAt: Long = System.currentTimeMillis(),
    ): LoomSession {
        val parse = read(files)
        return session(
            parse = parse,
            sources = files.map { it.name },
            generatedAt = generatedAt,
        )
    }

    /** Chronological, with the read order kept as the tie-break, and offsets measured from the start. */
    private fun order(events: List<LoomEvent>): List<LoomEvent> {
        val sorted = events.sortedWith(compareBy({ it.timestamp }, { it.source }, { it.lineNumber }))
        val start = sorted.firstOrNull()?.timestamp ?: 0L
        return sorted.mapIndexed { position, event ->
            event.copy(index = position, offsetMs = event.timestamp - start)
        }
    }

    private fun parseInto(
        text: String,
        source: String,
        events: MutableList<LoomEvent>,
        diagnostics: MutableList<LoomDiagnostic>,
    ) {
        var lineNumber = 0
        text.lineSequence().forEach { line ->
            lineNumber += 1
            if (line.isBlank()) return@forEach
            if (events.size >= MAX_EVENTS) {
                if (diagnostics.size < MAX_DIAGNOSTICS) {
                    diagnostics += LoomDiagnostic(source, lineNumber, "event limit reached")
                }
                return@forEach
            }
            val event = reconstruct(line, source, lineNumber, diagnostics)
            if (event != null) events += event
        }
    }

    @Suppress("ReturnCount") // One refusal per malformed shape, then the reconstruction itself.
    private fun reconstruct(
        line: String,
        source: String,
        lineNumber: Int,
        diagnostics: MutableList<LoomDiagnostic>,
    ): LoomEvent? {
        val element =
            try {
                json.parseToJsonElement(line)
            } catch (
                @Suppress("TooGenericExceptionCaught") failure: Exception,
            ) {
                diagnostics += LoomDiagnostic(source, lineNumber, "not JSON: ${failure::class.simpleName}")
                return null
            }
        val record = element as? JsonObject
        if (record == null) {
            diagnostics += LoomDiagnostic(source, lineNumber, "not a JSON object")
            return null
        }
        val toolName = record.string("toolName")
        if (toolName.isNullOrBlank()) {
            diagnostics += LoomDiagnostic(source, lineNumber, "no toolName: not an MCP call record")
            return null
        }
        return event(record, toolName, source, lineNumber)
    }

    private fun event(
        record: JsonObject,
        toolName: String,
        source: String,
        lineNumber: Int,
    ): LoomEvent {
        val defaulted = mutableListOf<String>()
        val args = argsOf(record, defaulted)
        val policy = record.string("policyApplied") ?: notRecorded(defaulted, "policyApplied")
        val disposition = record.string("approvalDisposition") ?: notRecorded(defaulted, "approvalDisposition")
        val risk = riskOf(toolName, args)
        return LoomEvent(
            index = 0,
            id = record.string("id") ?: label(defaulted, "id", "line-$lineNumber"),
            timestamp = record.long("timestamp") ?: notRecordedLong(defaulted, "timestamp"),
            offsetMs = 0L,
            toolName = toolName,
            providerId = record.string("providerId") ?: notRecorded(defaulted, "providerId"),
            policyApplied = policy,
            approvalDisposition = disposition,
            outcome = outcomeOf(policy, disposition).name,
            isError = record.boolean("isError") ?: false,
            durationMs = record.long("durationMs") ?: notRecordedLong(defaulted, "durationMs"),
            riskLevel = risk.first,
            riskReason = risk.second,
            args = args,
            errorSnippet = record.string("errorSnippet")?.take(ERROR_SNIPPET_LIMIT),
            hash = record.string("hash"),
            parentHash = record.string("parentHash"),
            source = source,
            lineNumber = lineNumber,
            defaulted = defaulted,
        )
    }

    /**
     * The sanitized arguments the ledger persisted, truncated per value.
     *
     * These are already sanitized by the runtime before they reach disk. Agent Loom does not
     * re-sanitize, because doing so would imply it trusts a different redaction rule than the one
     * that produced the file; it shows what the ledger holds and nothing else.
     */
    private fun argsOf(
        record: JsonObject,
        defaulted: MutableList<String>,
    ): Map<String, String> {
        val raw = record["sanitizedArgs"] as? JsonObject
        if (raw == null) {
            defaulted += "sanitizedArgs"
            return emptyMap()
        }
        return raw.entries
            .associate { (key, value) -> key to (value.jsonPrimitive.contentOrNull ?: "").take(ARG_VALUE_LIMIT) }
            .toSortedMap()
    }

    private fun riskOf(
        toolName: String,
        args: Map<String, String>,
    ): Pair<String, String> =
        try {
            val raw = buildJsonObject { args.forEach { (key, value) -> put(key, value) } }.toString()
            val assessment = riskEvaluator.evaluateRisk(toolName, McpToolArgs(args, raw))
            assessment.level.name to assessment.reason
        } catch (
            @Suppress("TooGenericExceptionCaught") failure: Exception,
        ) {
            "UNKNOWN" to "risk could not be derived: ${failure::class.simpleName}"
        }

    private fun bucketBy(
        events: List<LoomEvent>,
        key: (LoomEvent) -> String,
    ): List<LoomBucket> =
        events
            .groupingBy(key)
            .eachCount()
            .map { (label, count) -> LoomBucket(label, count) }
            .sortedWith(compareByDescending<LoomBucket> { it.count }.thenBy { it.label })

    /** Distribution over a fixed order, so the viewer's legend does not reshuffle between runs. */
    private fun distribution(
        events: List<LoomEvent>,
        order: List<String>,
        key: (LoomEvent) -> String,
    ): List<LoomBucket> {
        val counts = events.groupingBy(key).eachCount()
        val known = order.mapNotNull { label -> counts[label]?.let { LoomBucket(label, it) } }
        val extra =
            counts.keys
                .filterNot { it in order }
                .sorted()
                .map { LoomBucket(it, counts.getValue(it)) }
        return known + extra
    }

    private fun notesFor(
        events: List<LoomEvent>,
        diagnostics: List<LoomDiagnostic>,
        coverageGaps: List<String>,
    ): List<String> {
        val notes = mutableListOf<String>()
        notes += "The ledger does not record a session id, so the replay is identified by its source " +
            "files and time span rather than by a session name."
        notes += "Risk is not a ledger field. It is re-derived from the tool name and the sanitized " +
            "arguments by DefaultMcpRiskEvaluator, the same evaluator the runtime uses, and is labelled " +
            "as derived wherever it is shown."
        if (events.any { it.defaulted.isNotEmpty() }) {
            notes += "Some records are missing fields. Those fields are shown as not recorded rather " +
                "than filled in with a guess."
        }
        if (diagnostics.isNotEmpty()) {
            notes += "${diagnostics.size} ledger line(s) could not be reconstructed and are listed " +
                "separately. They are excluded from the timeline, not from the record."
        }
        if (coverageGaps.isNotEmpty()) {
            notes += "The ledger's rotation has a hole in it: ${coverageGaps.joinToString(", ")}. " +
                "Records written to those files are gone and are not in this replay."
        }
        if (events.isEmpty()) {
            notes += "This ledger holds no reconstructable MCP calls."
        }
        return notes
    }

    /**
     * The recorded policy action and approval disposition, collapsed into one replay outcome.
     *
     * Only names the ledger can actually contain are classified. Anything else is [LoomOutcome.UNKNOWN]
     * and keeps its raw value on the event card, because a replay that guesses at a governance
     * decision is worse than one that says it does not know.
     */
    fun outcomeOf(
        policyApplied: String,
        approvalDisposition: String,
    ): LoomOutcome =
        when (approvalDisposition) {
            "AUTO_ALLOWED" -> LoomOutcome.ALLOWED
            "APPROVED_ONCE", "SESSION_TRUSTED", "PERSISTENTLY_ALLOWED", "PROVIDER_TRUSTED" -> LoomOutcome.APPROVED
            "PROVIDER_TRUST_PERSIST_FAILED" -> LoomOutcome.APPROVED
            "DENIED_BY_OPERATOR", "PERSISTENTLY_DENIED", "POLICY_DENIED" -> LoomOutcome.DENIED
            "TIMEOUT" -> LoomOutcome.TIMED_OUT
            "CANCELLED", "CANCELLED_AWAITING_APPROVAL", "CANCELLED_IN_FLIGHT" -> LoomOutcome.CANCELLED
            "QUEUE_FULL" -> LoomOutcome.QUEUE_REJECTED
            else -> policyOutcome(policyApplied)
        }

    /** The policy action alone, for a record whose disposition this build does not classify. */
    private fun policyOutcome(policyApplied: String): LoomOutcome =
        when (policyApplied) {
            "ALLOW" -> LoomOutcome.ALLOWED
            "DENY" -> LoomOutcome.DENIED
            else -> LoomOutcome.UNKNOWN
        }

    /** Display order for the risk legend. */
    val RISK_ORDER = listOf("CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN")

    /** Display order for the outcome legend. */
    val OUTCOME_ORDER =
        listOf(
            "ALLOWED",
            "APPROVED",
            "DENIED",
            "TIMED_OUT",
            "CANCELLED",
            "QUEUE_REJECTED",
            "UNKNOWN",
        )
}

/** The label the viewer prints where the ledger carried no value. */
const val NOT_RECORDED = "not recorded"

/** Marks [name] as absent from the record, and returns the label the viewer shows for it. */
private fun notRecorded(
    defaulted: MutableList<String>,
    name: String,
): String {
    defaulted += name
    return NOT_RECORDED
}

/** Marks [name] as absent and returns [fallback], for a field the viewer renders as a label. */
private fun label(
    defaulted: MutableList<String>,
    name: String,
    fallback: String,
): String {
    defaulted += name
    return fallback
}

/** Marks [name] as absent and returns 0, for a numeric field the ledger did not carry. */
private fun notRecordedLong(
    defaulted: MutableList<String>,
    name: String,
): Long {
    defaulted += name
    return 0L
}

private fun JsonObject.string(name: String): String? {
    val value = this[name]?.jsonPrimitive?.contentOrNull
    return value?.takeIf { it.isNotBlank() }
}

private fun JsonObject.long(name: String): Long? = this[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

private fun JsonObject.boolean(name: String): Boolean? {
    val value = this[name]?.jsonPrimitive?.contentOrNull
    return value?.toBooleanStrictOrNull()
}

/**
 * Where a scrubber position lands.
 *
 * This is the tested definition of the scrub math. The exported viewer mirrors it in JavaScript,
 * because the artifact has to run without Kotlin; when the two disagree, this is the one that is
 * right and the viewer is the bug.
 */
object LoomTimeline {
    /** The last event at or before [offsetMs], or -1 when the offset precedes the session. */
    fun indexAt(
        events: List<LoomEvent>,
        offsetMs: Long,
    ): Int {
        var found = -1
        for (position in events.indices) {
            if (events[position].offsetMs <= offsetMs) found = position else break
        }
        return found
    }

    /** The offset of [index], clamped into the session. */
    fun offsetAt(
        events: List<LoomEvent>,
        index: Int,
    ): Long = events.getOrNull(index)?.offsetMs ?: 0L

    /** [offsetMs] as a 0..1 fraction of the session, for a scrubber handle. */
    fun progress(
        session: LoomSession,
        offsetMs: Long,
    ): Float {
        if (session.durationMs <= 0L) return if (session.events.isEmpty()) 0f else 1f
        return (offsetMs.toDouble() / session.durationMs.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }

    /** The inverse of [progress]. */
    fun offsetForProgress(
        session: LoomSession,
        progress: Float,
    ): Long = (session.durationMs * progress.coerceIn(0f, 1f).toDouble()).toLong()

    /** The offsets the viewer plots, one per event. */
    fun offsets(session: LoomSession): List<Long> = session.events.map { it.offsetMs }
}
