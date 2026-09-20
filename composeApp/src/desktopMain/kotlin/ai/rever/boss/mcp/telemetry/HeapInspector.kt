package ai.rever.boss.mcp.telemetry

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import javax.management.ObjectName

private val logger = BossLogger.forComponent("TelemetryHeap")

/** One collector's tally. */
internal data class GcStat(
    val name: String,
    val collectionCount: Long,
    val collectionTimeMs: Long,
)

/** One row of the class histogram. */
internal data class HeapClass(
    val className: String,
    val instances: Long,
    val bytes: Long,
)

internal data class HeapReport(
    val heapUsedMb: Double,
    val heapCommittedMb: Double,
    val heapMaxMb: Double?,
    val nonHeapUsedMb: Double,
    val gc: List<GcStat>,
    val gcTotalTimeMs: Long,
    val forcedGc: Boolean,
    val topClasses: List<HeapClass>,
    /** Why [topClasses] is empty, when it is. Null means it was populated. */
    val histogramUnavailableReason: String?,
)

/**
 * Heap, GC and retained object reporting.
 *
 * Heap and GC figures come from the standard platform beans and are always available. The class
 * histogram is best effort: it is the one part with no typed platform interface, so it goes
 * through the DiagnosticCommand MBean and degrades to a stated reason rather than failing the
 * whole tool. An agent chasing a leak can still act on "heap climbing, GC time climbing" without
 * the histogram; failing the call would leave it with nothing.
 */
internal object HeapInspector {
    private const val BYTES_PER_MB = 1024.0 * 1024.0
    private const val DEFAULT_TOP_CLASSES = 15

    /** jcmd's histogram, exposed as a supported MBean operation. */
    private const val DIAGNOSTIC_COMMAND_BEAN = "com.sun.management:type=DiagnosticCommand"
    private const val HISTOGRAM_OPERATION = "gcClassHistogram"

    @Suppress("TooGenericExceptionCaught")
    fun inspect(
        session: DiagnosticSession,
        forceGc: Boolean,
        topClasses: Int = DEFAULT_TOP_CLASSES,
    ): HeapReport {
        if (forceGc) {
            // A hint, not a guarantee: System.gc() is advisory and a collector may ignore it.
            // Reported as `forcedGc` so the reader knows it was requested, not that it happened.
            runCatching { session.memory.gc() }
                .onFailure { logger.warn(LogCategory.SYSTEM, "Requested GC was refused", error = it) }
        }

        val heap = session.memory.heapMemoryUsage
        val nonHeap = session.memory.nonHeapMemoryUsage
        val gcStats =
            session.garbageCollectors.map {
                GcStat(
                    name = it.name,
                    collectionCount = it.collectionCount.coerceAtLeast(0),
                    collectionTimeMs = it.collectionTime.coerceAtLeast(0),
                )
            }

        var unavailable: String? = null
        val classes =
            try {
                parseHistogram(rawHistogram(session), topClasses)
            } catch (t: Throwable) {
                unavailable = t.message ?: t::class.simpleName ?: "unknown error"
                logger.warn(LogCategory.SYSTEM, "Class histogram unavailable", error = t)
                emptyList()
            }

        return HeapReport(
            heapUsedMb = heap.used / BYTES_PER_MB,
            heapCommittedMb = heap.committed / BYTES_PER_MB,
            // -1 means "no maximum configured", which must not be reported as a real ceiling.
            heapMaxMb = heap.max.takeIf { it > 0 }?.let { it / BYTES_PER_MB },
            nonHeapUsedMb = nonHeap.used / BYTES_PER_MB,
            gc = gcStats,
            gcTotalTimeMs = gcStats.sumOf { it.collectionTimeMs },
            forcedGc = forceGc,
            topClasses = classes,
            histogramUnavailableReason = unavailable.takeIf { classes.isEmpty() },
        )
    }

    private fun rawHistogram(session: DiagnosticSession): String {
        val result =
            session.mBeans.invoke(
                ObjectName(DIAGNOSTIC_COMMAND_BEAN),
                HISTOGRAM_OPERATION,
                arrayOf<Any>(arrayOf<String>()),
                arrayOf("[Ljava.lang.String;"),
            )
        return result as? String ?: error("DiagnosticCommand returned no histogram text")
    }

    /**
     * Parses `jcmd GC.class_histogram` output.
     *
     * Rows look like `   1:        924000       29568000  java.util.HashMap$Node`, followed by a
     * `Total` line. Anything that does not match that shape is skipped rather than guessed at:
     * the header, the separator and the total are all non rows, and a future JDK adding a column
     * should degrade to fewer rows, never to wrong numbers.
     */
    fun parseHistogram(
        raw: String,
        limit: Int,
    ): List<HeapClass> =
        raw
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split(WHITESPACE)
                if (parts.size < MIN_ROW_COLUMNS) return@mapNotNull null
                if (!parts[0].endsWith(':')) return@mapNotNull null
                val instances = parts[1].toLongOrNull() ?: return@mapNotNull null
                val bytes = parts[2].toLongOrNull() ?: return@mapNotNull null
                HeapClass(className = parts[3], instances = instances, bytes = bytes)
            }.take(limit)
            .toList()

    private const val MIN_ROW_COLUMNS = 4
    private val WHITESPACE = Regex("""\s+""")
}
