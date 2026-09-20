package ai.rever.boss.mcp.telemetry

/** A verdict an agent can act on without reading the raw evidence. */
internal data class Verdict(
    val summary: String,
    val rootCause: String,
    val recommendation: String,
    /** One of `high`, `medium`, `low`. Honest about how much the evidence supports the verdict. */
    val confidence: String,
)

/**
 * Turns three raw reports into one conclusion.
 *
 * Pure: no session, no clock, no I/O, so every branch is unit testable against fabricated
 * reports. That matters more here than anywhere else in this feature, because this is the output
 * an agent is most likely to act on directly without reading the evidence underneath it.
 *
 * **Order is load bearing.** Deadlock is checked before CPU because a deadlocked process consumes
 * no CPU at all: profile it and every frame comes back empty, which reads as "healthy, nothing
 * hot". Answering in the other order tells an agent its stuck process is fine.
 */
internal object Diagnosis {
    /** Above this share of one frame's self time, the frame is the answer rather than a hint. */
    private const val DOMINANT_SELF_PERCENT = 25.0

    /** Below this, no single frame stands out and saying one does would be a guess. */
    private const val WEAK_SELF_PERCENT = 10.0

    /** Heap this full, after the collectors have run, is pressure rather than ordinary churn. */
    private const val HEAP_PRESSURE_RATIO = 0.85

    @Suppress("ReturnCount")
    fun of(
        threads: ThreadStateReport,
        profile: CpuProfile,
        heap: HeapReport,
    ): Verdict {
        deadlock(threads)?.let { return it }
        heapPressure(heap)?.let { return it }
        cpu(profile)?.let { return it }
        return idle(threads, profile)
    }

    private fun deadlock(threads: ThreadStateReport): Verdict? {
        if (!threads.hasDeadlock) return null
        val names = threads.deadlockedThreads.joinToString(", ") { it.threadName }
        val first = threads.deadlockedThreads.first()
        return Verdict(
            summary =
                "Process is deadlocked: ${threads.deadlockedThreads.size} threads ($names) are in a cycle and " +
                    "cannot make progress.",
            rootCause =
                "Thread '${first.threadName}' waits on ${first.waitingOn ?: "a lock"} held by " +
                    "${first.heldBy ?: "another thread in the cycle"}, while that thread waits in turn. " +
                    "Nothing will break this without restarting the process.",
            recommendation =
                "Find the two lock acquisitions in the frames listed under deadlocked_threads and make every " +
                    "path take them in the same order, or replace the nested locking with a single lock or a " +
                    "concurrent collection.",
            confidence = "high",
        )
    }

    private fun heapPressure(heap: HeapReport): Verdict? {
        // A null max means the JVM reported -1: no configured ceiling, so there is no ratio to
        // take and nothing to call pressure.
        val max = heap.heapMaxMb
        val ratio = if (max == null) 0.0 else heap.heapUsedMb / max
        if (max == null || ratio < HEAP_PRESSURE_RATIO) return null
        val worst = heap.topClasses.firstOrNull()
        return Verdict(
            summary =
                "Heap is ${percent(ratio)} full (${round(heap.heapUsedMb)} MB of ${round(max)} MB) with " +
                    "${heap.gcTotalTimeMs} ms spent collecting.",
            rootCause =
                if (worst != null) {
                    "The largest retained class is ${worst.className} with ${worst.instances} instances " +
                        "holding ${round(worst.bytes / BYTES_PER_MB)} MB. A class that dominates the heap this " +
                        "way is usually accumulating in a collection nothing ever clears."
                } else {
                    "The heap stays near its ceiling after collection, so the retained set is genuinely large " +
                        "rather than uncollected garbage. The class histogram was unavailable, so the retaining " +
                        "type could not be named."
                },
            recommendation =
                "Call telemetry_capture_heap with force_gc=true. If used memory stays high after the collection " +
                    "the objects are still reachable, so look for the collection holding them; if it drops, the " +
                    "process simply needs a larger heap or allocates faster than it collects.",
            confidence = if (worst != null) "high" else "medium",
        )
    }

    private fun cpu(profile: CpuProfile): Verdict? {
        val top = profile.frames.firstOrNull()
        if (top == null || top.selfPercent < WEAK_SELF_PERCENT) return null
        val dominant = top.selfPercent >= DOMINANT_SELF_PERCENT
        val caller = profile.frames.firstOrNull { it.frame != top.frame && it.cumulativePercent >= top.selfPercent }
        return Verdict(
            summary =
                "CPU bound in ${top.frame}, which was executing in ${top.selfPercent}% of samples" +
                    (top.hotLineNumber?.let { " (hottest line $it)" } ?: "") + ".",
            rootCause =
                buildString {
                    append("${top.frame} accounts for ${top.selfPercent}% of self time and ")
                    append("${top.cumulativePercent}% cumulative.")
                    if (caller != null) {
                        append(" It is reached through ${caller.frame}, which is on ${caller.cumulativePercent}% ")
                        append("of sampled stacks, so the cost is driven by how often that path runs.")
                    }
                    if (profile.partial) append(" Note: sampling ended early, so this is a partial picture.")
                },
            recommendation =
                if (dominant) {
                    "Optimise ${top.frame}${top.hotLineNumber?.let { " around line $it" } ?: ""}: cache or hoist " +
                        "the work out of the loop, or move it off the hot path. It is the single largest cost, " +
                        "so a change there is measurable."
                } else {
                    "No single frame dominates, so cost is spread across the call tree. Read collapsed_stacks " +
                        "as a flamegraph and look for a shared caller to optimise rather than one method."
                },
            confidence = if (dominant) "high" else "medium",
        )
    }

    private fun idle(
        threads: ThreadStateReport,
        profile: CpuProfile,
    ): Verdict {
        val blocked = threads.blockedThreads.size
        return if (blocked > 0) {
            val worst = threads.blockedThreads.first()
            Verdict(
                summary = "Process is not CPU bound. $blocked threads are blocked or waiting on locks.",
                rootCause =
                    "'${worst.threadName}' is ${worst.state} on ${worst.waitingOn ?: "a lock"}" +
                        (worst.heldBy?.let { " held by $it" } ?: "") +
                        ". This is contention, not a deadlock: the threads would proceed if the holder released.",
                recommendation =
                    "Shorten the critical section the holder is inside, or replace the lock with a concurrent " +
                        "collection. Re-run telemetry_thread_state under load to confirm the contention moved.",
                confidence = "medium",
            )
        } else {
            Verdict(
                summary =
                    "No bottleneck found: no deadlock, no heap pressure, and no application thread was RUNNABLE " +
                        "across ${profile.samplesCollected} samples.",
                rootCause =
                    "The process is idle or waiting on something outside itself, such as network I/O, a " +
                        "database, or input. Sampling only sees threads that are running, so time spent waiting " +
                        "on an external call does not appear as a hot frame.",
                recommendation =
                    "Re-run this while the slow operation is actually in flight. If it is still idle, the delay " +
                        "is outside this process and the next place to look is the service it is calling.",
                confidence = "low",
            )
        }
    }

    private const val BYTES_PER_MB = 1024.0 * 1024.0

    private fun round(value: Double): Double = kotlin.math.round(value * 10.0) / 10.0

    private fun percent(ratio: Double): String = "${kotlin.math.round(ratio * 1000.0) / 10.0}%"
}
