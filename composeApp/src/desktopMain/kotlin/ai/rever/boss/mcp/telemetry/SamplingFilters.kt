package ai.rever.boss.mcp.telemetry

/**
 * Which sampled threads are real work and which are noise.
 *
 * This exists because of a measured result, not a theory. The first attach spike against a target
 * whose only busy method was `Target.hotMethodUnderTest` reported four frames at 40 samples out of
 * 40, and three of them were artefacts:
 *
 * ```
 *   TOP 40  sun.nio.ch.Net.accept                              <- the JMX listener WE started
 *   TOP 40  Target.hotMethodUnderTest                          <- the only real answer
 *   TOP 40  java.lang.ref.Reference.waitForReferencePendingList <- idle, but reports RUNNABLE
 *   TOP 40  sun.management.ThreadImpl.dumpThreads0             <- the sampler observing itself
 * ```
 *
 * Unfiltered, a profile of an idle process is 75% noise and the one real frame is indistinguishable
 * from it. Worse, `dumpThreads0` is an **observer effect**: attaching to measure creates the thread
 * that then dominates the measurement, and it scales with sampling frequency, so raising the rate
 * to get a better answer makes the answer worse.
 *
 * Filtering is by top frame rather than by thread name alone because names are not load bearing:
 * a pool thread can be called anything, while the frame it is parked in is decided by the JDK.
 */
internal object SamplingFilters {
    /**
     * Top frames that mean "this thread is not doing the target's work".
     *
     * Matched against `declaringClass.methodName` exactly. A prefix match would be wrong here:
     * `sun.nio.ch.Net.accept` is idle, but a target that genuinely spends its time in
     * `sun.nio.ch.Net` doing reads is a real finding and must survive.
     */
    private val IDLE_TOP_FRAMES: Set<String> =
        setOf(
            // The measurement itself.
            "sun.management.ThreadImpl.dumpThreads0",
            // JVM infrastructure that sits in RUNNABLE while doing nothing.
            "java.lang.ref.Reference.waitForReferencePendingList",
            "java.lang.ref.Finalizer\$FinalizerThread.run",
            // Accept loops: the JMX/RMI plumbing the attach itself stands up.
            "sun.nio.ch.Net.accept",
            "java.net.PlainSocketImpl.socketAccept",
            "java.net.AbstractPlainSocketImpl.accept",
            // Selector waits across the three platforms.
            "sun.nio.ch.EPoll.wait",
            "sun.nio.ch.KQueue.poll",
            "sun.nio.ch.WindowsSelectorImpl\$SubSelector.poll0",
            "sun.nio.ch.Iocp.getQueuedCompletionStatus",
            // Parking and sleeping, which some JDKs still report as RUNNABLE.
            "jdk.internal.misc.Unsafe.park",
            "java.lang.Thread.sleep",
        )

    /**
     * Thread names the JVM and the attach machinery own.
     *
     * A belt and braces second axis: `RMI TCP Accept-0` only exists because we attached, and on a
     * JDK whose accept frame is not in the set above it would otherwise be reported as the
     * target's hottest thread.
     */
    private val INFRASTRUCTURE_THREAD_PREFIXES: List<String> =
        listOf(
            "Attach Listener",
            "Signal Dispatcher",
            "Reference Handler",
            "Finalizer",
            "Common-Cleaner",
            "Notification Thread",
            "process reaper",
            "RMI TCP Accept-",
            "RMI Scheduler",
            "JMX server connection timeout",
        )

    /**
     * True when this sample should be discarded.
     *
     * [topFrame] is null for a thread whose stack came back empty, which happens routinely for a
     * thread that exits between the dump and the read. Those carry no information and are dropped.
     */
    fun isNoise(
        threadName: String,
        topFrame: SampledFrame?,
    ): Boolean =
        topFrame == null ||
            INFRASTRUCTURE_THREAD_PREFIXES.any { threadName.startsWith(it) } ||
            topFrame.key in IDLE_TOP_FRAMES
}
