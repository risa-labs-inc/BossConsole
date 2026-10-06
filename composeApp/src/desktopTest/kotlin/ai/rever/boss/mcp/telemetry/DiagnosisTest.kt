package ai.rever.boss.mcp.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [Diagnosis], the one output an agent is likely to act on without reading the evidence.
 *
 * The ordering cases are the point: each asserts a conclusion that the *other* order would get
 * backwards, so reordering the checks in [Diagnosis.of] fails a named test rather than quietly
 * changing what an agent is told.
 */
class DiagnosisTest {
    private fun threads(
        deadlocked: List<DeadlockedThread> = emptyList(),
        blocked: List<BlockedThread> = emptyList(),
    ) = ThreadStateReport(
        totalThreads = 10,
        stateHistogram = mapOf("RUNNABLE" to 2, "WAITING" to 8),
        deadlockedThreads = deadlocked,
        blockedThreads = blocked,
        peakThreadCount = 12,
        daemonThreadCount = 5,
    )

    private fun profile(
        frames: List<FrameStat> = emptyList(),
        partial: Boolean = false,
    ) = CpuProfile(
        samplesCollected = 100,
        threadStacksSampled = frames.sumOf { it.selfSamples },
        durationMs = 3000,
        frames = frames,
        folded = emptyList(),
        partial = partial,
        partialReason = if (partial) "target exited" else null,
    )

    private fun frame(
        name: String,
        self: Double,
        cumulative: Double = self,
        line: Int? = null,
    ) = FrameStat(
        frame = name,
        selfSamples = self.toInt(),
        cumulativeSamples = cumulative.toInt(),
        selfPercent = self,
        cumulativePercent = cumulative,
        hotLineNumber = line,
    )

    private fun heap(
        usedMb: Double = 100.0,
        maxMb: Double? = 1024.0,
        topClasses: List<HeapClass> = emptyList(),
    ) = HeapReport(
        heapUsedMb = usedMb,
        heapCommittedMb = usedMb,
        heapMaxMb = maxMb,
        nonHeapUsedMb = 50.0,
        gc = emptyList(),
        gcTotalTimeMs = 120,
        forcedGc = false,
        topClasses = topClasses,
        histogramUnavailableReason = null,
    )

    @Test
    fun `a deadlock wins over an empty cpu profile`() {
        // The ordering bug this guards: a deadlocked process burns no CPU, so the profile is
        // empty. Checking CPU first would report "no bottleneck found" about a wedged process.
        val verdict =
            Diagnosis.of(
                threads = threads(deadlocked = listOf(deadlockedThread())),
                profile = profile(),
                heap = heap(),
            )
        assertTrue(verdict.summary.contains("deadlocked"), "got: ${verdict.summary}")
        assertEquals("high", verdict.confidence)
        assertTrue(verdict.recommendation.contains("same order"))
    }

    @Test
    fun `heap pressure wins over a hot frame`() {
        // A process thrashing the collector looks CPU bound, because GC burns CPU. Reporting the
        // hot frame would send the agent to optimise a method when the real fix is the leak.
        val verdict =
            Diagnosis.of(
                threads = threads(),
                profile = profile(listOf(frame("a.Hot.burn", self = 90.0))),
                heap =
                    heap(
                        usedMb = 980.0,
                        maxMb = 1024.0,
                        topClasses = listOf(HeapClass("java.util.HashMap\$Node", 900_000, 250L * 1024 * 1024)),
                    ),
            )
        assertTrue(verdict.summary.contains("Heap is"), "got: ${verdict.summary}")
        assertTrue(verdict.rootCause.contains("HashMap"))
    }

    @Test
    fun `a dominant frame is reported as the cpu bottleneck with high confidence`() {
        val verdict =
            Diagnosis.of(
                threads = threads(),
                profile = profile(listOf(frame("com.example.DataRouter.processPayload", self = 46.2, line = 142))),
                heap = heap(),
            )
        assertTrue(verdict.summary.contains("com.example.DataRouter.processPayload"))
        assertTrue(verdict.summary.contains("142"), "the hot line should reach the summary")
        assertEquals("high", verdict.confidence)
    }

    @Test
    fun `a diffuse profile does not claim a single culprit`() {
        val verdict =
            Diagnosis.of(
                threads = threads(),
                profile = profile(listOf(frame("a.A.m", self = 12.0), frame("a.B.m", self = 11.0))),
                heap = heap(),
            )
        assertEquals("medium", verdict.confidence)
        assertTrue(verdict.recommendation.contains("No single frame dominates"))
    }

    @Test
    fun `contention without a deadlock reads as contention`() {
        val verdict =
            Diagnosis.of(
                threads =
                    threads(
                        blocked =
                            listOf(
                                BlockedThread(
                                    threadId = 7,
                                    threadName = "worker-1",
                                    state = "BLOCKED",
                                    waitingOn = "a.Lock@1",
                                    heldBy = "worker-2 (tid 8)",
                                    topFrame = "a.Service.handle:20",
                                ),
                            ),
                    ),
                profile = profile(),
                heap = heap(),
            )
        assertTrue(verdict.summary.contains("blocked or waiting"))
        // Must not be confused with a deadlock, which needs a restart.
        assertTrue(verdict.rootCause.contains("not a deadlock"))
    }

    @Test
    fun `an idle process is reported honestly as low confidence`() {
        val verdict = Diagnosis.of(threads(), profile(), heap())
        assertEquals("low", verdict.confidence)
        assertTrue(verdict.summary.contains("No bottleneck found"))
        // The useful part: tell the agent sampling cannot see external waits.
        assertTrue(verdict.rootCause.contains("network I/O") || verdict.rootCause.contains("waiting"))
    }

    @Test
    fun `an unbounded heap never reports pressure`() {
        // heapMaxMb is null when the JVM reports -1, meaning no configured ceiling. Treating that
        // as a maximum would divide by a number that does not exist.
        val verdict =
            Diagnosis.of(
                threads(),
                profile(listOf(frame("a.Hot.burn", self = 80.0))),
                heap(usedMb = 9000.0, maxMb = null),
            )
        assertTrue(verdict.summary.contains("CPU bound"))
    }

    @Test
    fun `a partial profile says so in its reasoning`() {
        val verdict =
            Diagnosis.of(
                threads(),
                profile(listOf(frame("a.Hot.burn", self = 60.0)), partial = true),
                heap(),
            )
        assertTrue(verdict.rootCause.contains("partial"))
    }

    private fun deadlockedThread() =
        DeadlockedThread(
            threadId = 1,
            threadName = "worker-1",
            waitingOn = "a.LockB@2",
            heldBy = "worker-2 (tid 2)",
            topFrames = listOf("a.Svc.left:10"),
        )
}
