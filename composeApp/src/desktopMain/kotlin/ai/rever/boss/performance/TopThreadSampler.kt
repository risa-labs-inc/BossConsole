package ai.rever.boss.performance

import java.lang.management.ThreadMXBean

/**
 * Samples the busiest JVM threads for [CpuMetrics.threads].
 *
 * A naive scan - `allThreadIds` + `getThreadInfo(all)` + a per-thread CPU and user
 * time call - costs three MXBean calls per live thread. On the 2s CPU tick that is
 * hundreds of calls a minute to produce a list only the top few entries of are ever
 * shown, and the full result is then copied into every retained history snapshot.
 *
 * Two bounds keep that proportional to what is displayed rather than to the total
 * thread count:
 *
 * - [minScanIntervalMs] throttles full enumerations; calls between scans return the
 *   previous result unchanged. The CPU tick may fire at 2s while the thread table
 *   only refreshes every [DEFAULT_SCAN_INTERVAL_MS].
 * - A scan ranks threads by cumulative CPU time - one call per thread - and only
 *   fetches `ThreadInfo` metadata (names, states, blocked/waited counts) for the
 *   top [CANDIDATE_COUNT]. User time is fetched only for the [THREAD_LIMIT]
 *   survivors. Thread metadata is the expensive part; per-id CPU time is the cheap
 *   ranking signal.
 *
 * Called only from the monitor's sampling loop, so no synchronization.
 */
internal class TopThreadSampler(
    private val threadMXBean: ThreadMXBean,
    private val minScanIntervalMs: Long = DEFAULT_SCAN_INTERVAL_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private var lastScanMs = 0L
    private var cached: List<ThreadInfo>? = null

    /**
     * The hottest threads as of the last scan. Re-enumerates the JVM thread table
     * at most once per [minScanIntervalMs]; otherwise returns the previous list.
     */
    fun sample(): List<ThreadInfo> {
        val existing = cached
        val now = nowMs()
        if (existing != null && now - lastScanMs < minScanIntervalMs) return existing

        val scanned = scan()
        cached = scanned
        lastScanMs = now
        return scanned
    }

    private fun scan(): List<ThreadInfo> {
        val ids = threadMXBean.allThreadIds
        if (ids.isEmpty()) return emptyList()

        val cpuTimeSupported =
            threadMXBean.isThreadCpuTimeSupported && threadMXBean.isThreadCpuTimeEnabled

        // Rank every thread by cumulative CPU time (one cheap call each), then fetch
        // full metadata only for the top candidates. Dead threads report -1 and sort
        // to the bottom, so they are only ever reached when few threads are alive.
        val candidates: List<Pair<Long, Long>> =
            if (cpuTimeSupported) {
                ids
                    .map { id -> id to threadMXBean.getThreadCpuTime(id) }
                    .sortedByDescending { it.second }
                    .take(CANDIDATE_COUNT)
            } else {
                ids.take(CANDIDATE_COUNT).map { it to 0L }
            }

        val candidateIds = candidates.map { it.first }.toLongArray()
        val infos = threadMXBean.getThreadInfo(candidateIds)

        return candidates
            .zip(infos.asIterable())
            .mapNotNull { (candidate, info) ->
                // info is null when the thread died between the two reads.
                if (info == null) null else Triple(candidate.first, candidate.second, info)
            }.take(THREAD_LIMIT)
            .map { (id, cpuTimeNs, info) ->
                ThreadInfo(
                    id = id,
                    name = info.threadName,
                    state = info.threadState.name,
                    cpuTimeMs = if (cpuTimeNs > 0) cpuTimeNs / 1_000_000 else 0L,
                    userTimeMs =
                        if (cpuTimeSupported) {
                            threadMXBean.getThreadUserTime(id).coerceAtLeast(0L) / 1_000_000
                        } else {
                            0L
                        },
                    blockedCount = info.blockedCount,
                    waitedCount = info.waitedCount,
                )
            }
    }

    companion object {
        /** Minimum wall time between full thread-table enumerations. */
        const val DEFAULT_SCAN_INTERVAL_MS = 10_000L

        /** How many top-by-CPU-time threads get full metadata fetched per scan. */
        const val CANDIDATE_COUNT = 40

        /** How many threads a snapshot carries - what the panel displays. */
        const val THREAD_LIMIT = 20
    }
}
