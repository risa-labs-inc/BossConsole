package ai.rever.boss.mcp.telemetry

import ai.rever.boss.performance.ProcessFootprint
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.sun.tools.attach.VirtualMachine
import java.io.File
import java.util.concurrent.TimeUnit

private val logger = BossLogger.forComponent("TelemetryTargets")

/** The runtime families [TelemetryTargets.classify] can tell apart. */
internal enum class TargetRuntime(
    val wireName: String,
) {
    JVM("jvm"),
    NODE("node"),
    PYTHON("python"),
    NATIVE("native"),
    ;

    companion object {
        fun fromFilter(value: String?): TargetRuntime? =
            when (value?.lowercase()) {
                null, "", "all" -> null
                "jvm" -> JVM
                "node" -> NODE
                "python" -> PYTHON
                "native" -> NATIVE
                else -> null
            }
    }
}

/** One inspectable process. */
internal data class TelemetryTarget(
    val pid: Long,
    val command: String,
    val runtime: TargetRuntime,
    /** True when the profiling tools can actually reach it. Only JVMs can be. */
    val attachable: Boolean,
    val cpuSeconds: Double?,
    val memoryRssMb: Double?,
    val isBossHost: Boolean,
)

/**
 * Finds processes worth pointing a diagnostic tool at.
 *
 * `ProcessHandle.allProcesses()` is the whole machine, so the interesting work here is narrowing:
 * an agent asking "what can I profile" wants the JVM it just started, not 400 OS services.
 */
internal object TelemetryTargets {
    /**
     * Upper bound on reported targets.
     *
     * Two reasons, and the second is the load bearing one. A response listing every process on
     * the machine would swamp the model's context, and the RSS lookup below passes its pids to a
     * subprocess: `ProcessFootprint` documents that an unbounded pid list deadlocks, because the
     * child blocks writing to a full pipe while nothing drains it.
     */
    const val MAX_TARGETS: Int = 100

    private const val RSS_QUERY_TIMEOUT_SECONDS = 5L
    private const val BYTES_PER_MB = 1024.0 * 1024.0

    /**
     * Which runtime a command line belongs to.
     *
     * Pure and separately tested. Matching is on the executable's base name, with an argv fallback
     * for launchers: a Gradle or Maven wrapper is `java` under the hood and an agent profiling a
     * build wants it reported as a JVM.
     */
    fun classify(
        command: String?,
        arguments: List<String>,
    ): TargetRuntime {
        val exe =
            command
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
                ?.removeSuffix(".exe")
                ?.lowercase()
                .orEmpty()

        return when {
            exe == "java" || exe == "javaw" -> TargetRuntime.JVM

            exe == "node" -> TargetRuntime.NODE

            exe == "python" || exe == "pythonw" || exe == "py" || exe.matches(PYTHON_VERSIONED) -> TargetRuntime.PYTHON

            // A wrapper script that is really a JVM. `-jar` alone is not enough (a shell can pass
            // it to anything), so require a JVM shaped flag.
            arguments.any { it.startsWith("-XX:") || it.startsWith("-Xm") || it == "-jar" } -> TargetRuntime.JVM

            else -> TargetRuntime.NATIVE
        }
    }

    private val PYTHON_VERSIONED = Regex("""python\d(\.\d+)?""")

    /**
     * Live targets, optionally filtered to one runtime.
     *
     * Ordered so the answer is useful without reading all of it: attachable JVMs first, then by
     * CPU time consumed, which puts the process an agent is most likely asking about at the top.
     */
    @Suppress("TooGenericExceptionCaught")
    fun list(filter: TargetRuntime?): List<TelemetryTarget> {
        val bossPid = ProcessHandle.current().pid()
        val attachablePids = attachablePids()

        val candidates =
            ProcessHandle
                .allProcesses()
                .toList()
                .mapNotNull { handle ->
                    runCatching {
                        val info = handle.info()
                        val command = info.command().orElse(null)
                        val arguments = info.arguments().map { it.toList() }.orElse(emptyList())
                        val runtime = classify(command, arguments)
                        TelemetryTarget(
                            pid = handle.pid(),
                            command = info.commandLine().orElse(command ?: "(unknown)"),
                            runtime = runtime,
                            // A JVM we cannot see in the attach list is still not attachable, so
                            // this is measured rather than inferred from the runtime.
                            attachable = handle.pid() == bossPid || handle.pid() in attachablePids,
                            cpuSeconds = info.totalCpuDuration().map { it.toMillis() / 1000.0 }.orElse(null),
                            memoryRssMb = null,
                            isBossHost = handle.pid() == bossPid,
                        )
                    }.getOrNull()
                }.filter { filter == null || it.runtime == filter }
                .sortedWith(
                    compareByDescending<TelemetryTarget> { it.attachable }
                        .thenByDescending { it.cpuSeconds ?: -1.0 }
                        .thenBy { it.pid },
                ).take(MAX_TARGETS)

        val rss = rssBytesFor(candidates.map { it.pid })
        return candidates.map { target ->
            target.copy(memoryRssMb = rss[target.pid]?.let { it / BYTES_PER_MB })
        }
    }

    /**
     * Pids the attach mechanism can actually see, from jvmstat.
     *
     * Reported rather than guessed from the executable name: a JVM started with
     * `-XX:+DisableAttachMechanism`, or running as another user, is a `java` process that cannot
     * be profiled, and telling an agent otherwise sends it to a tool call that can only fail.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun attachablePids(): Set<Long> =
        try {
            VirtualMachine.list().mapNotNullTo(mutableSetOf()) { it.id().toLongOrNull() }
        } catch (t: Throwable) {
            logger.warn(LogCategory.SYSTEM, "Could not enumerate attachable JVMs", error = t)
            emptySet()
        }

    /**
     * Resident set size per pid, or an empty map when it cannot be read.
     *
     * Reuses `ProcessFootprint`'s parsers rather than re-deriving them, because each encodes a
     * platform quirk found the hard way: PowerShell instead of `tasklist`, whose memory column is
     * localised and thousands separated, and `smaps_rollup` in preference to `status` on Linux.
     *
     * RSS is a nicety, so every failure here degrades to "unknown" rather than failing the tool.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun rssBytesFor(pids: List<Long>): Map<Long, Long> {
        if (pids.isEmpty()) return emptyMap()
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return try {
            when {
                os.startsWith("linux") -> {
                    pids
                        .mapNotNull { pid ->
                            runCatching {
                                val rollup = File("/proc/$pid/smaps_rollup")
                                val kb =
                                    if (rollup.exists()) {
                                        ProcessFootprint.parseProcKb(rollup.readText(), "Pss:")
                                    } else {
                                        ProcessFootprint.parseProcKb(File("/proc/$pid/status").readText(), "VmRSS:")
                                    }
                                kb?.times(1024L)
                            }.getOrNull()?.let { pid to it }
                        }.toMap()
                }

                os.startsWith("mac") -> {
                    ProcessFootprint.parsePsRssOutput(
                        runQuery(listOf("/bin/ps", "-o", "pid=,rss=", "-p", pids.joinToString(","))),
                    )
                }

                // startsWith, not contains: "darwin" contains "win".
                os.startsWith("windows") -> {
                    ProcessFootprint.parseWindowsCsv(
                        runQuery(
                            listOf(
                                "powershell.exe",
                                "-NoProfile",
                                "-NonInteractive",
                                "-Command",
                                "Get-Process -Id ${pids.joinToString(",")} -ErrorAction SilentlyContinue | " +
                                    "Select-Object Id,WorkingSet64 | ConvertTo-Csv -NoTypeInformation",
                            ),
                        ),
                    )
                }

                else -> {
                    emptyMap()
                }
            }
        } catch (t: Throwable) {
            logger.warn(LogCategory.SYSTEM, "Could not read RSS for targets", error = t)
            emptyMap()
        }
    }

    /**
     * Runs a query and returns its output, or null.
     *
     * Waits before draining, which is safe only because the pid list is explicitly bounded by
     * [MAX_TARGETS]. With unbounded output this ordering deadlocks. Do not substitute an
     * unfiltered `Get-Process` or `ps -A` here.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun runQuery(command: List<String>): String? =
        runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            try {
                if (!process.waitFor(RSS_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    return@runCatching null
                }
                process.inputStream.bufferedReader().use { it.readText() }
            } finally {
                process.inputStream.close()
                process.errorStream.close()
                process.outputStream.close()
            }
        }.getOrNull()
}
