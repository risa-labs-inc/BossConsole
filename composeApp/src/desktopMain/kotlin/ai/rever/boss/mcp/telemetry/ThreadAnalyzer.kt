package ai.rever.boss.mcp.telemetry

import java.lang.management.ThreadInfo
import java.lang.management.ThreadMXBean

/** A thread caught in a deadlock cycle, with the lock it wants and who holds it. */
internal data class DeadlockedThread(
    val threadId: Long,
    val threadName: String,
    val waitingOn: String?,
    val heldBy: String?,
    val topFrames: List<String>,
)

/** A thread that is merely blocked, which is contention rather than a deadlock. */
internal data class BlockedThread(
    val threadId: Long,
    val threadName: String,
    val state: String,
    val waitingOn: String?,
    val heldBy: String?,
    val topFrame: String?,
)

internal data class ThreadStateReport(
    val totalThreads: Int,
    val stateHistogram: Map<String, Int>,
    val deadlockedThreads: List<DeadlockedThread>,
    val blockedThreads: List<BlockedThread>,
    val peakThreadCount: Int,
    val daemonThreadCount: Int,
) {
    val hasDeadlock: Boolean get() = deadlockedThreads.isNotEmpty()
}

/**
 * Thread level diagnosis: deadlocks first, then contention.
 *
 * This is the cheapest high value tool in the family. A deadlocked process shows no CPU at all,
 * so a CPU profile of one is uniformly empty and an agent reading that concludes "nothing is
 * slow" - the exact opposite of the truth. `findDeadlockedThreads` answers it outright.
 */
internal object ThreadAnalyzer {
    /** How many frames of a deadlocked thread to report. Enough to name the call site. */
    private const val DEADLOCK_FRAMES = 8

    /** Cap on reported blocked threads, so a heavily contended process stays readable. */
    private const val MAX_BLOCKED_REPORTED = 20

    fun analyze(threads: ThreadMXBean): ThreadStateReport {
        val allIds = threads.allThreadIds
        // Locked monitors and synchronizers are what make "who holds it" answerable, and a
        // deadlock report without the owner is not actionable.
        val infos = threads.getThreadInfo(allIds, true, true).filterNotNull()

        val deadlockedIds =
            (threads.findDeadlockedThreads() ?: threads.findMonitorDeadlockedThreads())
                ?.toSet()
                .orEmpty()

        val deadlocked =
            infos
                .filter { it.threadId in deadlockedIds }
                .map { info ->
                    DeadlockedThread(
                        threadId = info.threadId,
                        threadName = info.threadName,
                        waitingOn = info.lockInfo?.toString(),
                        heldBy = ownerLabel(info),
                        topFrames = info.stackTrace.take(DEADLOCK_FRAMES).map(::frameLabel),
                    )
                }

        val blocked =
            infos
                .filter { it.threadId !in deadlockedIds && it.threadState in CONTENDED_STATES && it.lockInfo != null }
                .sortedByDescending { it.blockedCount }
                .take(MAX_BLOCKED_REPORTED)
                .map { info ->
                    BlockedThread(
                        threadId = info.threadId,
                        threadName = info.threadName,
                        state = info.threadState.name,
                        waitingOn = info.lockInfo?.toString(),
                        heldBy = ownerLabel(info),
                        topFrame = info.stackTrace.firstOrNull()?.let(::frameLabel),
                    )
                }

        return ThreadStateReport(
            totalThreads = infos.size,
            stateHistogram =
                infos
                    .groupingBy { it.threadState.name }
                    .eachCount()
                    .toSortedMap()
                    .toMap(),
            deadlockedThreads = deadlocked,
            blockedThreads = blocked,
            peakThreadCount = threads.peakThreadCount,
            daemonThreadCount = threads.daemonThreadCount,
        )
    }

    /**
     * States worth reporting as contention.
     *
     * TIMED_WAITING is excluded on purpose: a pool thread parked on a keep alive is in that state
     * for its whole idle life, and listing every one of them would bury the BLOCKED thread that
     * actually matters.
     */
    private val CONTENDED_STATES = setOf(Thread.State.BLOCKED, Thread.State.WAITING)

    private fun ownerLabel(info: ThreadInfo): String? = info.lockOwnerName?.let { "$it (tid ${info.lockOwnerId})" }

    private fun frameLabel(element: StackTraceElement): String =
        buildString {
            append(element.className).append('.').append(element.methodName)
            if (element.lineNumber > 0) append(':').append(element.lineNumber)
        }
}
