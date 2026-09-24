package ai.rever.boss.mcp

import ai.rever.boss.health.WorkspaceHealthReport
import ai.rever.boss.health.toJson
import ai.rever.boss.performance.PerformanceHealth
import ai.rever.boss.performance.PerformanceSnapshot
import ai.rever.boss.plugin.api.McpToolDefinition
import ai.rever.boss.plugin.api.McpToolHandler
import ai.rever.boss.plugin.api.McpToolProvider
import ai.rever.boss.plugin.api.McpToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What the reading of [PerformanceSnapshot] found, including finding nothing.
 *
 * A null [snapshot] is not an error: [ai.rever.boss.performance.PerformanceMonitor]
 * publishes nothing until `start()` has run and the first sampling tick has landed, and
 * a headless or just-booted host is legitimately in that state.
 */
internal data class PerformanceReading(
    val snapshot: PerformanceSnapshot?,
    val health: PerformanceHealth?,
)

/**
 * Host MCP tool provider answering "what condition is BOSS in right now?".
 *
 * **The gap this closes.** BOSS already computes both halves of its own condition and
 * exposes neither to a tool caller. [WorkspaceHealthReport] is reachable only through
 * `SingleInstanceManager`, i.e. over the single-instance socket from a CLI process
 * OUTSIDE the host - `boss doctor` was built for a human at a terminal (#418, #579).
 * `PerformanceMonitor` publishes its snapshot to a settings panel and nowhere else. So
 * the agent sitting inside the host, which is the party that has to decide whether to
 * open another browser tab or spawn another terminal, had no way to ask whether the
 * host was already degraded or near a ceiling. It found out by causing the failure.
 *
 * **One contract, two surfaces.** `get_workspace_health` returns
 * [WorkspaceHealthReport.toJson], the same object `boss status --json` emits, rather
 * than a second shape for one concept. A script written against `boss doctor --json`
 * and an agent calling this tool read the same codes, and they cannot drift apart.
 *
 * **Magnitudes, not contents.** [ai.rever.boss.performance.ResourceMetrics] carries
 * `browserTabs`, `terminals` and `editorTabs`, and `CpuMetrics` carries a thread list -
 * URLs, file paths and thread names. None of it is emitted here. A caller deciding
 * whether there is headroom needs to know there are eleven browser tabs, not which
 * pages they are on, and a tool that answered the second question would be a much
 * larger disclosure than the one it was asked. Counts and aggregates only.
 *
 * **Read-only by declaration.** Both tools observe. They declare `readOnly = true`
 * rather than relying on the mutating gate's fail-closed default, so the policy engine
 * classifies them from the declaration.
 *
 * Both sources live in `desktopMain`; this provider and the registry are `commonMain`.
 * The suppliers are therefore injected at startup exactly as
 * [WorkspaceMcpToolProvider.windowCreator] is, which keeps this file free of any
 * platform assumption and lets the tests drive it with fakes.
 */
object IntrospectionMcpToolProvider : McpToolProvider {
    override val providerId: String = "boss-introspection"

    /** Supplies the workspace health report. Wired from `main.kt`; null before startup. */
    internal var healthSupplier: (() -> WorkspaceHealthReport)? = null

    /** Supplies the current performance reading. Wired from `main.kt`; null before startup. */
    internal var performanceSupplier: (() -> PerformanceReading)? = null

    override fun tools(): List<McpToolDefinition> =
        listOf(
            healthTool("get_workspace_health"),
            healthTool("workspace_health"),
            performanceTool("get_performance_metrics"),
            performanceTool("performance_metrics"),
        )

    private fun healthTool(name: String): McpToolDefinition =
        McpToolDefinition(
            name = name,
            description =
                "Report whether this BOSS workspace is degraded, as `boss doctor` does: each " +
                    "finding carries a stable code, a severity, the subject it concerns and a " +
                    "suggested remedy. Areas that could not be read are listed as unchecked, and " +
                    "areas read from several sources where one failed are listed as partial - in " +
                    "both cases an empty findings list is NOT a clean bill of health. Read-only.",
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {}
                }
                """.trimIndent(),
            handler = McpToolHandler { McpToolResult(healthJson().toString()) },
            readOnly = true,
        )

    private fun performanceTool(name: String): McpToolDefinition =
        McpToolDefinition(
            name = name,
            description =
                "Report this BOSS process's current resource use: heap and non-heap memory, CPU " +
                    "load, GC activity, and how many windows, browser tabs, terminals and editor " +
                    "tabs are open. Intended to be called BEFORE opening another tab or spawning " +
                    "another terminal. Returns sampling=false when the monitor has not produced a " +
                    "reading yet, which is not the same as an idle host. Counts only - no URLs, " +
                    "file paths or thread names. Read-only.",
            inputSchema =
                """
                {
                    "type": "object",
                    "properties": {}
                }
                """.trimIndent(),
            handler = McpToolHandler { McpToolResult(performanceJson().toString()) },
            readOnly = true,
        )

    /**
     * The health report, or an explicit "not available" when the host has not wired a
     * supplier yet.
     *
     * `available: false` rather than an empty report, for the reason the report itself
     * separates `unchecked` from "no findings": an agent told nothing is wrong when
     * nothing was actually read would act on a guarantee that was never made.
     */
    internal fun healthJson(): JsonObject {
        val supplier = healthSupplier
        return if (supplier == null) {
            unavailable("workspace health has not been wired on this host")
        } else {
            runCatching { supplier() }.fold(
                onSuccess = { report ->
                    buildJsonObject {
                        put("available", true)
                        // Nested, not flattened: this value is byte-identical to what
                        // `boss status --json` puts under its own "health" key, so the two
                        // surfaces can be diffed on one machine and an agent and a script
                        // read the same object. Flattening would also let a future field on
                        // the report collide with "available".
                        put("health", report.toJson())
                    }
                },
                onFailure = { unavailable("workspace health could not be read: ${it.reason()}") },
            )
        }
    }

    /**
     * The performance reading, or an explicit "not sampling yet".
     *
     * Zeros are never emitted for an absent snapshot. A caller that read 0 bytes used
     * and concluded it had headroom would be worse off than one told the monitor has
     * not reported.
     */
    internal fun performanceJson(): JsonObject {
        val supplier = performanceSupplier
        return if (supplier == null) {
            unavailable("performance monitoring has not been wired on this host")
        } else {
            runCatching { supplier() }.fold(
                onSuccess = ::readingJson,
                onFailure = { unavailable("performance metrics could not be read: ${it.reason()}") },
            )
        }
    }

    private fun readingJson(reading: PerformanceReading): JsonObject {
        val snapshot = reading.snapshot
        return if (snapshot == null) {
            buildJsonObject {
                put("available", true)
                put("sampling", false)
                put(
                    "detail",
                    "the performance monitor has not produced a reading yet; this is not an idle host",
                )
            }
        } else {
            buildJsonObject {
                put("available", true)
                put("sampling", true)
                putSnapshot(snapshot)
                reading.health?.let { health ->
                    put(
                        "health",
                        buildJsonObject {
                            put("overall", health.overall.name.lowercase())
                            put("memory", health.memoryStatus.name.lowercase())
                            put("cpu", health.cpuStatus.name.lowercase())
                        },
                    )
                }
            }
        }
    }

    /**
     * The snapshot's own fields, split out only because detekt caps a method at 60
     * lines; it is one continuous shape and belongs with [readingJson].
     */
    private fun JsonObjectBuilder.putSnapshot(snapshot: PerformanceSnapshot) {
        put("timestamp", snapshot.timestamp)
        put(
            "memory",
            buildJsonObject {
                put("heapUsedBytes", snapshot.memory.heapUsedBytes)
                put("heapMaxBytes", snapshot.memory.heapMaxBytes)
                put("heapCommittedBytes", snapshot.memory.heapCommittedBytes)
                put("heapUsagePercent", snapshot.memory.heapUsagePercent)
                put("nonHeapUsedBytes", snapshot.memory.nonHeapUsedBytes)
                put("nonHeapCommittedBytes", snapshot.memory.nonHeapCommittedBytes)
            },
        )
        put(
            "cpu",
            buildJsonObject {
                put("processLoadPercent", snapshot.cpu.processLoadPercent)
                put("systemLoadPercent", snapshot.cpu.systemLoadPercent)
                put("availableProcessors", snapshot.cpu.availableProcessors)
                put("activeThreadCount", snapshot.cpu.activeThreadCount)
            },
        )
        put(
            "gc",
            buildJsonObject {
                put("collectionCount", snapshot.gc.collectionCount)
                put("collectionTimeMs", snapshot.gc.collectionTimeMs)
                put("gcTimeSinceLastSampleMs", snapshot.gc.gcTimeSinceLastSampleMs)
            },
        )
        // Counts only. The per-tab and per-terminal lists deliberately do not travel.
        put(
            "resources",
            buildJsonObject {
                put("windowCount", snapshot.resources.windowCount)
                put("browserTabCount", snapshot.resources.browserTabCount)
                put("terminalCount", snapshot.resources.terminalCount)
                put("editorTabCount", snapshot.resources.editorTabCount)
                put("panelCount", snapshot.resources.panelCount)
            },
        )
    }

    /** Said out loud, so a caller never reads silence as "nothing is wrong". */
    private fun unavailable(detail: String): JsonObject =
        buildJsonObject {
            put("available", false)
            put("detail", detail)
        }

    /** A cause worth printing, without letting a null message become the string "null". */
    private fun Throwable.reason(): String = message?.takeIf { it.isNotBlank() } ?: this::class.simpleName.orEmpty()
}
