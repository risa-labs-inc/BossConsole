package ai.rever.boss.mcp.telemetry

import ai.rever.boss.plugin.api.McpToolArgs
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Runtime diagnostics for processes an agent can see, exposed as `mcp__boss__telemetry_*`.
 *
 * ## Why this exists
 *
 * An agent editing code in BOSS can run a build and read stdout, and that is the whole of its
 * feedback. If the change made something slow, leaky or deadlocked, the agent is reduced to
 * reading the source and guessing. These tools let it measure instead.
 *
 * ## What it deliberately is not
 *
 * Not a second `PerformanceMonitor`. That object samples **this** process for the status bar
 * chart and has no MCP surface; this samples **other** processes on an agent's request and has no
 * UI. They overlap in subject and in nothing else.
 *
 * ## Governance
 *
 * None is implemented here, on purpose. The host already owns the kill switch
 * (`McpToolRegistryCore`), the policy engine, the approval prompt and the ledger, and a second
 * copy inside a provider would be a duplicate that can disagree with the real one. What this
 * provider does is declare itself honestly so those mechanisms classify it correctly:
 *
 * - Everything that only reads declares `readOnly = true`, which the policy engine defaults to
 *   ALLOW. Reading a thread dump changes nothing.
 * - `telemetry_capture_heap` declares `readOnly = false`, because its `force_gc` argument
 *   **mutates the target**: it makes another process stop and collect. That inherits the ASK
 *   default, so an operator is asked before an agent can provoke a GC pause in something the
 *   agent does not own.
 *
 * No `requiredPermissions` are declared, matching `WorkspaceMcpToolProvider`'s reasoning: the MCP
 * server is loopback only for the local machine's own agents, and the documented posture there is
 * that an undeclared tool is permitted.
 */
// One cohesive MCP tool provider; handlers and JSON shaping stay beside their tool definitions,
// matching WorkspaceMcpToolProvider. Splitting five tools across five files would scatter a
// contract that is read as a whole.
@Suppress("TooManyFunctions")
internal object TelemetryMcpToolProvider : McpToolProvider {
    private val logger = BossLogger.forComponent("TelemetryMcpToolProvider")

    private val json = Json { prettyPrint = false }

    override val providerId: String = "boss-telemetry"

    /**
     * The OTLP receiver, started lazily by [handleQueryTraces].
     *
     * Deliberately not started at registration: a BOSS install where nobody uses tracing should
     * never open a listening socket. Internal so tests can stop it.
     */
    internal val otlpReceiver = OtlpReceiver()

    override fun tools(): List<McpToolDefinition> =
        listOf(
            listTargetsTool(),
            profileCpuTool(),
            threadStateTool(),
            captureHeapTool(),
            queryTracesTool(),
            explainBottleneckTool(),
        )

    // ---------------------------------------------------------------- definitions

    private fun listTargetsTool() =
        McpToolDefinition(
            name = "telemetry_list_targets",
            description =
                "List local processes that can be inspected, newest and busiest first. Reports pid, command, " +
                    "runtime (jvm/node/python/native), CPU seconds, resident memory, and whether the process is " +
                    "attachable. Call this first: only targets with attachable=true can be profiled.",
            inputSchema =
                """
                {
                  "type": "object",
                  "properties": {
                    "filter_type": {
                      "type": "string",
                      "enum": ["all", "jvm", "node", "python", "native"],
                      "default": "all",
                      "description": "Restrict results to one runtime family."
                    }
                  }
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handleListTargets(args) },
            readOnly = true,
        )

    private fun profileCpuTool() =
        McpToolDefinition(
            name = "telemetry_profile_cpu",
            description =
                "Sample a JVM process's CPU usage and return the hottest call frames. Use self_time_percent to " +
                    "find the method to optimise (the CPU was executing it) and cumulative_time_percent to see " +
                    "what called it. Duration is clamped to 1-10 seconds. JVM targets only.",
            inputSchema =
                """
                {
                  "type": "object",
                  "properties": {
                    "pid": { "type": "integer", "description": "Target process id, from telemetry_list_targets." },
                    "duration_seconds": {
                      "type": "integer", "minimum": 1, "maximum": 10, "default": 3,
                      "description": "Sampling window. Values outside the range are clamped, not rejected."
                    },
                    "sampling_frequency_hz": {
                      "type": "integer", "default": 99,
                      "description": "Samples per second. 99 by default to avoid locking step with timers."
                    },
                    "top_k": { "type": "integer", "default": 20, "maximum": 100, "description": "Frames to report." }
                  },
                  "required": ["pid"]
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handleProfileCpu(args) },
            readOnly = true,
        )

    private fun threadStateTool() =
        McpToolDefinition(
            name = "telemetry_thread_state",
            description =
                "Detect deadlocks and lock contention in a JVM process. Reports deadlock cycles with the lock and " +
                    "its owner, a thread state histogram, and the most blocked threads. Use this when a process " +
                    "is unresponsive but shows no CPU: a deadlocked process profiles as completely idle.",
            inputSchema =
                """
                {
                  "type": "object",
                  "properties": {
                    "pid": { "type": "integer", "description": "Target process id." }
                  },
                  "required": ["pid"]
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handleThreadState(args) },
            readOnly = true,
        )

    private fun captureHeapTool() =
        McpToolDefinition(
            name = "telemetry_capture_heap",
            description =
                "Report heap usage, garbage collection counts and pause totals, and the classes retaining the most " +
                    "memory in a JVM process. Set force_gc to collect before measuring, which tells a real leak " +
                    "from ordinary garbage. force_gc pauses the target, so it needs operator approval.",
            inputSchema =
                """
                {
                  "type": "object",
                  "properties": {
                    "pid": { "type": "integer", "description": "Target process id." },
                    "force_gc": {
                      "type": "boolean", "default": false,
                      "description": "Request a collection first. This pauses the target process."
                    },
                    "top_classes": { "type": "integer", "default": 15, "description": "Histogram rows to report." }
                  },
                  "required": ["pid"]
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handleCaptureHeap(args) },
            // Mutating: force_gc makes another process stop and collect. See the class KDoc.
            readOnly = false,
        )

    private fun queryTracesTool() =
        McpToolDefinition(
            name = "telemetry_query_traces",
            description =
                "Query OpenTelemetry spans received from instrumented processes, newest first. Point an app at " +
                    "http://127.0.0.1:4318 with the OTLP/HTTP JSON exporter and its traces become queryable here. " +
                    "Filter by service, minimum duration or error status to find the slow or failing request. " +
                    "The receiver starts on the first call to this tool, so run it once before generating traffic.",
            inputSchema =
                """
                {
                  "type": "object",
                  "properties": {
                    "service_name": { "type": "string", "description": "Match the service.name resource attribute." },
                    "min_duration_ms": {
                      "type": "integer", "default": 0,
                      "description": "Only spans at least this slow."
                    },
                    "status_error_only": {
                      "type": "boolean", "default": false,
                      "description": "Only spans whose status is ERROR."
                    },
                    "limit": { "type": "integer", "default": 10, "maximum": 50, "description": "Spans to return." }
                  }
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handleQueryTraces(args) },
            readOnly = true,
        )

    private fun explainBottleneckTool() =
        McpToolDefinition(
            name = "telemetry_explain_bottleneck",
            description =
                "Diagnose a slow or stuck JVM process in one call: checks for deadlocks first, then profiles the " +
                    "CPU, then reads the heap, and returns a summary with a likely cause and a suggested next " +
                    "step. Start here when you do not yet know what kind of problem you are looking at.",
            inputSchema =
                """
                {
                  "type": "object",
                  "properties": {
                    "pid": { "type": "integer", "description": "Target process id." },
                    "duration_seconds": {
                      "type": "integer", "minimum": 1, "maximum": 10, "default": 3,
                      "description": "CPU sampling window."
                    }
                  },
                  "required": ["pid"]
                }
                """.trimIndent(),
            handler = McpToolHandler { args -> handleExplainBottleneck(args) },
            readOnly = true,
        )

    // ---------------------------------------------------------------- handlers

    private suspend fun handleListTargets(args: McpToolArgs): McpToolResult {
        val raw = args.string("filter_type")
        if (raw != null && raw.lowercase() !in ALLOWED_FILTERS) {
            return error("Unknown filter_type '$raw'. Expected one of: ${ALLOWED_FILTERS.joinToString(", ")}.")
        }
        val filter = TargetRuntime.fromFilter(raw)
        val targets = withContext(Dispatchers.IO) { TelemetryTargets.list(filter) }

        return ok(
            buildJsonObject {
                put("target_count", targets.size)
                putJsonArray("targets") {
                    targets.forEach { t ->
                        add(
                            buildJsonObject {
                                put("pid", t.pid)
                                put("command", t.command)
                                put("runtime", t.runtime.wireName)
                                put("attachable", t.attachable)
                                t.cpuSeconds?.let { put("cpu_seconds", round2(it)) }
                                t.memoryRssMb?.let { put("memory_rss_mb", round2(it)) }
                                if (t.isBossHost) put("is_boss_host", true)
                            },
                        )
                    }
                }
            },
        )
    }

    private suspend fun handleProfileCpu(args: McpToolArgs): McpToolResult =
        withSession(args) { pid, session ->
            val profile =
                CpuSampler.profile(
                    session = session,
                    durationSeconds = args.int("duration_seconds"),
                    hz = args.int("sampling_frequency_hz"),
                    topK = args.int("top_k") ?: CollapsedStacks.DEFAULT_TOP_K,
                )
            ok(profileJson(pid, profile))
        }

    private suspend fun handleThreadState(args: McpToolArgs): McpToolResult =
        withSession(args) { pid, session ->
            val report = withContext(Dispatchers.IO) { ThreadAnalyzer.analyze(session.threads) }
            ok(
                buildJsonObject {
                    put("pid", pid)
                    putAllThreadState(report)
                },
            )
        }

    private suspend fun handleCaptureHeap(args: McpToolArgs): McpToolResult =
        withSession(args) { pid, session ->
            val forceGc = args.boolean("force_gc") ?: false
            val report =
                withContext(Dispatchers.IO) {
                    HeapInspector.inspect(session, forceGc, args.int("top_classes") ?: DEFAULT_TOP_CLASSES)
                }
            ok(
                buildJsonObject {
                    put("pid", pid)
                    putAllHeap(report)
                },
            )
        }

    private suspend fun handleQueryTraces(args: McpToolArgs): McpToolResult {
        val startedNow = !otlpReceiver.isRunning
        val started = withContext(Dispatchers.IO) { otlpReceiver.start() }
        val boundPort =
            started.getOrElse { failure ->
                // A busy port is ordinary (a real collector may already own 4318) and must not
                // read as a broken tool. Say so, and leave the other tools untouched.
                return error(
                    "The OTLP receiver could not bind 127.0.0.1:${OtlpReceiver.DEFAULT_PORT} " +
                        "(${failure.message ?: "unknown error"}). Something else is probably already listening " +
                        "there. The other telemetry tools are unaffected.",
                )
            }

        val spans =
            otlpReceiver.buffer.query(
                serviceName = args.string("service_name"),
                minDurationMs = (args.int("min_duration_ms") ?: 0).toLong(),
                errorOnly = args.boolean("status_error_only") ?: false,
                limit = args.int("limit") ?: DEFAULT_SPAN_LIMIT,
            )
        val stats = otlpReceiver.buffer.stats()

        return ok(
            buildJsonObject {
                put("endpoint", "http://127.0.0.1:$boundPort${OtlpReceiver.TRACES_PATH}")
                put("spans_matched", spans.size)
                put("spans_buffered", stats.retained)
                put("spans_received_total", stats.acceptedTotal)
                if (startedNow) {
                    put("receiver_started_now", true)
                    put(
                        "note",
                        "The receiver was not running until this call, so nothing has been collected yet. " +
                            "Configure the target with OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:$boundPort " +
                            "and OTEL_EXPORTER_OTLP_PROTOCOL=http/json, generate traffic, then query again.",
                    )
                }
                putJsonArray("spans") {
                    spans.forEach { span ->
                        add(
                            buildJsonObject {
                                put("trace_id", span.traceId)
                                put("span_id", span.spanId)
                                span.parentSpanId?.let { put("parent_span_id", it) }
                                put("operation_name", span.name)
                                span.serviceName?.let { put("service_name", it) }
                                put("duration_ms", span.durationMs)
                                put("status", span.status)
                                span.errorMessage?.let { put("error_message", it) }
                                putJsonObject("attributes") {
                                    span.attributes.forEach { (key, value) -> put(key, value) }
                                }
                            },
                        )
                    }
                }
            },
        )
    }

    private suspend fun handleExplainBottleneck(args: McpToolArgs): McpToolResult =
        withSession(args) { pid, session ->
            // Deadlock first: a deadlocked process burns no CPU, so a profile of one is empty and
            // reads as "nothing is wrong". Answering in the wrong order is actively misleading.
            val threads = withContext(Dispatchers.IO) { ThreadAnalyzer.analyze(session.threads) }
            val profile =
                CpuSampler.profile(
                    session = session,
                    durationSeconds = args.int("duration_seconds"),
                    hz = null,
                    topK = EXPLAIN_TOP_K,
                )
            val heap = withContext(Dispatchers.IO) { HeapInspector.inspect(session, forceGc = false) }
            val verdict = Diagnosis.of(threads, profile, heap)

            ok(
                buildJsonObject {
                    put("pid", pid)
                    put("summary", verdict.summary)
                    put("root_cause_analysis", verdict.rootCause)
                    put("recommended_agent_action", verdict.recommendation)
                    put("confidence", verdict.confidence)
                    putJsonObject("evidence") {
                        putAllThreadState(threads)
                        put("cpu", profileJson(pid, profile))
                        putJsonObject("heap") { putAllHeap(heap) }
                    }
                },
            )
        }

    // ---------------------------------------------------------------- shared

    /**
     * Resolves the pid, opens a session, runs [block], and always closes.
     *
     * Every handler that touches a target goes through here so the `use {}` cannot be forgotten in
     * one of them: a leaked JMX connector holds an RMI connection and a daemon thread inside the
     * process being measured.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private suspend fun withSession(
        args: McpToolArgs,
        block: suspend (Long, DiagnosticSession) -> McpToolResult,
    ): McpToolResult {
        val pid = readPid(args) ?: return error("pid is required and must be a positive integer.")
        val opened = withContext(Dispatchers.IO) { DiagnosticSessions.open(pid) }
        val session =
            opened.getOrElse { failure ->
                return error((failure as? AttachException)?.failure?.message ?: failure.message ?: "Could not attach.")
            }
        return try {
            session.use { block(pid, it) }
        } catch (t: Throwable) {
            logger.warn(LogCategory.SYSTEM, "Telemetry tool failed for pid $pid", error = t)
            error("Diagnostics failed for process $pid: ${t.message ?: t::class.simpleName ?: "unknown error"}")
        }
    }

    /**
     * Reads `pid` from either a JSON number or a string.
     *
     * `McpToolArgs.int` refuses anything outside Int range, and a pid is a Long. Clients also
     * differ on whether they send numbers as numbers, so both shapes are accepted rather than
     * failing a call over an encoding detail the model cannot see.
     */
    private fun readPid(args: McpToolArgs): Long? {
        val value = args.string("pid")?.trim()?.toLongOrNull() ?: args.int("pid")?.toLong()
        return value?.takeIf { it > 0 }
    }

    private fun profileJson(
        pid: Long,
        profile: CpuProfile,
    ): JsonObject =
        buildJsonObject {
            put("pid", pid)
            put("duration_ms", profile.durationMs)
            put("samples_collected", profile.samplesCollected)
            put("thread_stacks_sampled", profile.threadStacksSampled)
            if (profile.partial) {
                put("partial", true)
                put("partial_reason", profile.partialReason)
            }
            if (profile.threadStacksSampled == 0) {
                put(
                    "note",
                    "No application threads were RUNNABLE during the window. The process is idle, blocked or " +
                        "deadlocked rather than CPU bound; call telemetry_thread_state next.",
                )
            }
            putJsonArray("top_bottlenecks") {
                profile.frames.forEach { f ->
                    add(
                        buildJsonObject {
                            put("frame", f.frame)
                            put("self_time_percent", f.selfPercent)
                            put("cumulative_time_percent", f.cumulativePercent)
                            put("self_samples", f.selfSamples)
                            f.hotLineNumber?.let { put("hot_line_number", it) }
                        },
                    )
                }
            }
            putJsonArray("collapsed_stacks") {
                // Folded format, directly consumable by any flamegraph renderer.
                profile.folded.take(FOLDED_LIMIT).forEach { add(it) }
            }
        }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putAllThreadState(report: ThreadStateReport) {
        put("total_threads", report.totalThreads)
        put("peak_thread_count", report.peakThreadCount)
        put("daemon_thread_count", report.daemonThreadCount)
        put("deadlock_detected", report.hasDeadlock)
        putJsonObject("thread_states") {
            report.stateHistogram.forEach { (state, count) -> put(state, count) }
        }
        putJsonArray("deadlocked_threads") {
            report.deadlockedThreads.forEach { t ->
                add(
                    buildJsonObject {
                        put("thread_id", t.threadId)
                        put("thread_name", t.threadName)
                        t.waitingOn?.let { put("waiting_on", it) }
                        t.heldBy?.let { put("held_by", it) }
                        putJsonArray("top_frames") { t.topFrames.forEach { f -> add(f) } }
                    },
                )
            }
        }
        putJsonArray("blocked_threads") {
            report.blockedThreads.forEach { t ->
                add(
                    buildJsonObject {
                        put("thread_id", t.threadId)
                        put("thread_name", t.threadName)
                        put("state", t.state)
                        t.waitingOn?.let { put("waiting_on", it) }
                        t.heldBy?.let { put("held_by", it) }
                        t.topFrame?.let { put("top_frame", it) }
                    },
                )
            }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putAllHeap(report: HeapReport) {
        put("heap_used_mb", round2(report.heapUsedMb))
        put("heap_committed_mb", round2(report.heapCommittedMb))
        report.heapMaxMb?.let { put("heap_max_mb", round2(it)) }
        put("non_heap_used_mb", round2(report.nonHeapUsedMb))
        put("gc_total_time_ms", report.gcTotalTimeMs)
        put("forced_gc", report.forcedGc)
        putJsonArray("collectors") {
            report.gc.forEach { g ->
                add(
                    buildJsonObject {
                        put("name", g.name)
                        put("collection_count", g.collectionCount)
                        put("collection_time_ms", g.collectionTimeMs)
                    },
                )
            }
        }
        putJsonArray("top_classes") {
            report.topClasses.forEach { c ->
                add(
                    buildJsonObject {
                        put("class_name", c.className)
                        put("instances", c.instances)
                        put("bytes", c.bytes)
                    },
                )
            }
        }
        report.histogramUnavailableReason?.let { put("histogram_unavailable_reason", it) }
    }

    private fun ok(payload: JsonObject) = McpToolResult(json.encodeToString(JsonObject.serializer(), payload))

    private fun error(message: String) = McpToolResult(message, isError = true)

    private fun round2(value: Double): Double = kotlin.math.round(value * 100.0) / 100.0

    private val ALLOWED_FILTERS = setOf("all", "jvm", "node", "python", "native")
    private const val DEFAULT_TOP_CLASSES = 15
    private const val EXPLAIN_TOP_K = 10
    private const val FOLDED_LIMIT = 40
    private const val DEFAULT_SPAN_LIMIT = 10
}
