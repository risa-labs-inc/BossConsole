package ai.rever.boss.mcp.telemetry

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the noise filter against the exact artefacts a real attach produced. See [SamplingFilters]
 * for the measured spike output these cases come from.
 */
class SamplingFiltersTest {
    private fun frame(name: String) =
        SampledFrame(
            declaringClass = name.substringBeforeLast('.'),
            methodName = name.substringAfterLast('.'),
            lineNumber = 1,
        )

    @Test
    fun `the sampler does not report itself`() {
        // The observer effect: attaching creates the thread that would dominate the profile.
        assertTrue(SamplingFilters.isNoise("main", frame("sun.management.ThreadImpl.dumpThreads0")))
    }

    @Test
    fun `jvm infrastructure idling in RUNNABLE is dropped`() {
        assertTrue(
            SamplingFilters.isNoise(
                "Reference Handler",
                frame("java.lang.ref.Reference.waitForReferencePendingList"),
            ),
        )
        assertTrue(SamplingFilters.isNoise("RMI TCP Accept-0", frame("sun.nio.ch.Net.accept")))
        assertTrue(SamplingFilters.isNoise("Common-Cleaner", frame("jdk.internal.misc.Unsafe.park")))
    }

    @Test
    fun `an infrastructure thread is dropped even when its top frame is unknown`() {
        // Second axis: some JDKs park accept loops in frames not in the frame set.
        assertTrue(SamplingFilters.isNoise("RMI TCP Accept-0", frame("com.example.Whatever.run")))
    }

    @Test
    fun `real application work survives`() {
        assertFalse(SamplingFilters.isNoise("main", frame("com.example.DataRouter.processPayload")))
        assertFalse(SamplingFilters.isNoise("pool-1-thread-3", frame("java.util.zip.GZIPInputStream.read")))
    }

    @Test
    fun `a genuinely busy nio frame is not confused with an idle accept`() {
        // Exact match, not prefix: the target really working inside sun.nio.ch is a finding.
        assertFalse(SamplingFilters.isNoise("worker", frame("sun.nio.ch.SocketChannelImpl.read")))
    }

    @Test
    fun `a thread with no stack carries no information`() {
        assertTrue(SamplingFilters.isNoise("main", null))
    }
}
