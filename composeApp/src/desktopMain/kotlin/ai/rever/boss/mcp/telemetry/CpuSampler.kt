package ai.rever.boss.mcp.telemetry

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.lang.management.ThreadInfo

/** The result of one sampling run. */
internal data class CpuProfile(
    val samplesCollected: Int,
    val threadStacksSampled: Int,
    val durationMs: Long,
    val frames: List<FrameStat>,
    val folded: List<String>,
    /** True when sampling stopped early, typically because the target exited. */
    val partial: Boolean,
    val partialReason: String?,
)

/**
 * A wall clock sampling CPU profiler built on `ThreadMXBean.dumpAllThreads`.
 *
 * This is the same technique VisualVM's sampler uses, and it is deliberately not async-profiler:
 * that needs a per OS native library, `perf_event_paranoid` or `ptrace_scope` on Linux and a
 * signed entitlement on macOS. Everything here is pure JDK, so it works on all three platforms
 * with no native artefact to ship and nothing for an operator to configure.
 *
 * What that costs, stated so nobody over reads the numbers: samples land on safepoints, so a
 * method the JIT never lets the VM poll inside is under represented, and a thread blocked in a
 * native call reports the Java frame that called it. Good enough to answer "which of my methods
 * is burning the CPU", which is the question an agent is asking; not a substitute for a
 * production profiler when chasing a JIT or GC pathology.
 */
internal object CpuSampler {
    const val MIN_DURATION_SECONDS: Int = 1

    /**
     * Hard ceiling on a run.
     *
     * The host wraps every MCP call in a 60 second timeout, so a longer profile would be killed
     * mid flight and return nothing at all. Clamping to something that always completes is
     * strictly better than honouring a request that cannot finish.
     */
    const val MAX_DURATION_SECONDS: Int = 10

    const val DEFAULT_DURATION_SECONDS: Int = 3

    /**
     * Default rate in Hz.
     *
     * 99 rather than 100 on purpose: a rate that divides evenly into a second locks step with
     * timers and schedulers that also tick on that boundary, and the profile then over samples
     * whatever runs on the tick. A prime rate walks across the period instead.
     */
    const val DEFAULT_HZ: Int = 99

    const val MIN_HZ: Int = 1
    const val MAX_HZ: Int = 1000

    /** Clamps rather than rejects: a nonsense duration should still produce a usable profile. */
    fun clampDurationSeconds(requested: Int?): Int =
        (requested ?: DEFAULT_DURATION_SECONDS).coerceIn(MIN_DURATION_SECONDS, MAX_DURATION_SECONDS)

    /** Clamps rather than rejects, for the same reason as [clampDurationSeconds]. */
    fun clampHz(requested: Int?): Int = (requested ?: DEFAULT_HZ).coerceIn(MIN_HZ, MAX_HZ)

    /**
     * Samples [session] for the requested window and folds the result.
     *
     * Cancellation cooperative by construction: the loop suspends in [delay] between samples and
     * does its one blocking call inside `withContext(Dispatchers.IO)`, which is what the
     * `McpToolHandler` contract requires for the host's `withTimeout` to be able to interrupt it.
     * A tight loop here would keep running after the timeout fired and only stop the caller
     * waiting, which is the failure mode the api's KDoc calls out by name.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun profile(
        session: DiagnosticSession,
        durationSeconds: Int?,
        hz: Int?,
        topK: Int,
    ): CpuProfile {
        val duration = clampDurationSeconds(durationSeconds)
        val rate = clampHz(hz)
        val intervalMs = (1000L / rate).coerceAtLeast(1L)
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + duration * 1000L

        val stacks = mutableListOf<List<SampledFrame>>()
        var samples = 0
        var partialReason: String? = null

        while (System.currentTimeMillis() < deadline) {
            val dumped =
                try {
                    withContext(Dispatchers.IO) { session.threads.dumpAllThreads(false, false) }
                } catch (t: Throwable) {
                    // The target exiting mid profile is an ordinary outcome, not a fault: return
                    // what was collected and say why it stopped. Throwing here would lose every
                    // sample already taken, which is the opposite of useful.
                    partialReason = describeInterruption(t)
                    break
                }
            samples++
            dumped.forEach { info -> collect(info, stacks) }
            delay(intervalMs)
        }

        return CpuProfile(
            samplesCollected = samples,
            threadStacksSampled = stacks.size,
            durationMs = System.currentTimeMillis() - startedAt,
            frames = CollapsedStacks.aggregate(stacks, topK),
            folded = CollapsedStacks.folded(stacks),
            partial = partialReason != null,
            partialReason = partialReason,
        )
    }

    /** Keeps one thread's stack if it is RUNNABLE and is not infrastructure noise. */
    private fun collect(
        info: ThreadInfo?,
        into: MutableList<List<SampledFrame>>,
    ) {
        if (info == null || info.threadState != Thread.State.RUNNABLE) return
        val frames =
            info.stackTrace.map {
                SampledFrame(
                    declaringClass = it.className,
                    methodName = it.methodName,
                    lineNumber = it.lineNumber,
                )
            }
        if (SamplingFilters.isNoise(info.threadName.orEmpty(), frames.firstOrNull())) return
        into.add(frames)
    }

    private fun describeInterruption(t: Throwable): String =
        "Sampling stopped early: ${t.message ?: t::class.simpleName ?: "connection lost"}. " +
            "The target most likely exited; the frames below come from the samples taken before that."
}
