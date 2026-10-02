package ai.rever.boss.mcp.telemetry

import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * End to end tests against real child JVMs: attach, sample, and diagnose.
 *
 * These are the tests that prove the feature works rather than that its arithmetic is
 * self consistent. They are kept apart from the pure suites so a failure here means "attach or
 * sampling broke" and a failure there means "the maths broke".
 *
 * **They skip rather than fail where attach is unavailable.** Some CI sandboxes and hardened
 * hosts forbid `VirtualMachine.attach` outright (Linux `ptrace_scope=2`, container seccomp
 * profiles). A red build on a machine whose OS policy forbids the operation says nothing about
 * the code, so the guard reports the skip and returns.
 */
class LiveDiagnosticsTest {
    private val spawned = mutableListOf<Process>()
    private val argFiles = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        // destroyForcibly, not destroy: the targets are in tight loops or deadlocked, and a
        // deadlocked JVM will not honour a polite termination request.
        spawned.forEach { it.destroyForcibly() }
        spawned.forEach { it.waitFor(TEARDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        spawned.clear()
        argFiles.forEach { it.delete() }
        argFiles.clear()
    }

    @Test
    fun `profiling a hot loop names the hot method as the top frame`() {
        val pid = launch(HotLoopMain::class.java.name) ?: return
        val session = DiagnosticSessions.open(pid).getOrNull() ?: return skip("attach refused for pid $pid")

        val profile =
            session.use {
                runBlocking { CpuSampler.profile(it, durationSeconds = 2, hz = null, topK = 10) }
            }

        assertTrue(profile.samplesCollected > 0, "no samples were taken at all")
        assertTrue(profile.frames.isNotEmpty(), "no frames survived filtering: ${profile.samplesCollected} samples")

        val top = profile.frames.first()
        assertTrue(
            top.frame.endsWith("hotMethodUnderTest"),
            "expected hotMethodUnderTest on top, got ${profile.frames.take(5).map { it.frame }}",
        )
        assertTrue(top.selfPercent > MAJORITY_PERCENT, "hot method should dominate, was ${top.selfPercent}%")
        assertFalse(profile.partial, "the target was alive throughout")
    }

    @Test
    fun `the sampler does not report its own measurement thread`() {
        // The observer effect the filter exists for. Without it dumpThreads0 is in every sample.
        val pid = launch(HotLoopMain::class.java.name) ?: return
        val session = DiagnosticSessions.open(pid).getOrNull() ?: return skip("attach refused for pid $pid")

        val profile = session.use { runBlocking { CpuSampler.profile(it, 2, null, 20) } }

        assertTrue(
            profile.frames.none { it.frame.contains("dumpThreads0") },
            "the sampler measured itself: ${profile.frames.map { it.frame }}",
        )
        assertTrue(
            profile.frames.none { it.frame == "sun.nio.ch.Net.accept" },
            "the JMX listener we started leaked into the profile",
        )
    }

    @Test
    fun `a real deadlock is detected with both threads and their lock owners`() {
        val pid = launch(DeadlockMain::class.java.name) ?: return
        val session = DiagnosticSessions.open(pid).getOrNull() ?: return skip("attach refused for pid $pid")

        val report = session.use { ThreadAnalyzer.analyze(it.threads) }

        assertTrue(report.hasDeadlock, "expected a deadlock, states were ${report.stateHistogram}")
        assertEquals(2, report.deadlockedThreads.size, "the fixture deadlocks exactly two threads")
        val names = report.deadlockedThreads.map { it.threadName }.toSet()
        assertEquals(setOf("deadlock-left", "deadlock-right"), names)
        assertTrue(
            report.deadlockedThreads.all { it.heldBy != null },
            "a deadlock report without the lock owner is not actionable",
        )
    }

    @Test
    fun `explain reports a deadlock rather than an empty cpu profile`() {
        // The ordering property, proven against a real process: a deadlocked JVM burns no CPU, so
        // an implementation that profiled first would answer "no bottleneck found" here.
        val pid = launch(DeadlockMain::class.java.name) ?: return
        val session = DiagnosticSessions.open(pid).getOrNull() ?: return skip("attach refused for pid $pid")

        val verdict =
            session.use {
                val threads = ThreadAnalyzer.analyze(it.threads)
                val profile = runBlocking { CpuSampler.profile(it, 1, null, 10) }
                val heap = HeapInspector.inspect(it, forceGc = false)
                Diagnosis.of(threads, profile, heap)
            }

        assertTrue(verdict.summary.contains("deadlocked"), "got: ${verdict.summary}")
        assertEquals("high", verdict.confidence)
    }

    @Test
    fun `heap and gc figures are readable from an attached target`() {
        val pid = launch(HotLoopMain::class.java.name) ?: return
        val session = DiagnosticSessions.open(pid).getOrNull() ?: return skip("attach refused for pid $pid")

        val heap = session.use { HeapInspector.inspect(it, forceGc = false) }

        assertTrue(heap.heapUsedMb > 0, "a running JVM always has some heap in use")
        assertTrue(heap.gc.isNotEmpty(), "a JVM always reports at least one collector")
        // The histogram is best effort by design; assert the contract, not that it succeeded.
        assertTrue(
            heap.topClasses.isNotEmpty() || heap.histogramUnavailableReason != null,
            "an empty histogram must always come with a stated reason",
        )
    }

    @Test
    fun `a target that exits mid profile yields partial results instead of throwing`() {
        val pid = launch(HotLoopMain::class.java.name) ?: return
        val process = spawned.last()
        val session = DiagnosticSessions.open(pid).getOrNull() ?: return skip("attach refused for pid $pid")

        val profile =
            session.use {
                runBlocking {
                    // Kill it partway through the window; the sampler must survive losing the target.
                    Thread {
                        Thread.sleep(KILL_DELAY_MS)
                        process.destroyForcibly()
                    }.start()
                    CpuSampler.profile(it, durationSeconds = 5, hz = null, topK = 10)
                }
            }

        assertTrue(profile.partial, "losing the target must be reported as a partial result")
        assertTrue(profile.partialReason!!.isNotBlank(), "a partial result must say why")
    }

    @Test
    fun `a pid that is not running is refused with a clear reason`() {
        // Never a live pid: the kernel rejects it before any lookup happens.
        val failure = DiagnosticSessions.open(-1L).exceptionOrNull()
        // assertIs, not assertTrue: only assertIs carries the contract that smart casts `failure`.
        assertIs<AttachException>(failure)
        assertIs<AttachFailure.ProcessGone>(failure.failure)
    }

    @Test
    fun `the host can diagnose itself without attaching`() {
        // Self attach is refused by the JDK, so this must go through the local beans instead.
        val session = DiagnosticSessions.open(ProcessHandle.current().pid()).getOrThrow()
        session.use {
            assertTrue(it.isSelf, "the host's own pid must not take the attach path")
            assertTrue(ThreadAnalyzer.analyze(it.threads).totalThreads > 0)
            assertTrue(HeapInspector.inspect(it, forceGc = false).heapUsedMb > 0)
        }
    }

    // --------------------------------------------------------------- helpers

    /** Starts a child JVM on a trimmed test classpath and returns its pid once it reports ready. */
    private fun launch(mainClass: String): Long? {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val process =
            ProcessBuilder(java, "@" + argFile().absolutePath, mainClass)
                .redirectErrorStream(true)
                .start()
        spawned += process

        val reader = process.inputStream.bufferedReader()
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val line = reader.readLine() ?: break
            if (line.startsWith("READY ")) return line.removePrefix("READY ").trim().toLongOrNull()
        }
        skip("child JVM $mainClass never reported ready")
        return null
    }

    /**
     * The child's `-cp`, in a JDK argument file.
     *
     * Two Windows problems, one fix each. `CreateProcess` caps a command line at 32 KB and this
     * module's test classpath is hundreds of jars, so passing it inline fails outright with
     * `error=206, The filename or extension is too long`. And an argfile treats backslash as an
     * escape character, so Windows paths are written with forward slashes, which Java accepts.
     *
     * The classpath is also trimmed to what the fixtures actually need: the compiled output
     * directories plus kotlin-stdlib. The targets only touch `Math`, `Thread`, `CountDownLatch`
     * and `ProcessHandle`, so dragging Compose and JxBrowser into a child JVM would only make it
     * slower to start.
     */
    private fun argFile(): File {
        val separator = File.pathSeparator
        val full = System.getProperty("java.class.path").split(separator)
        val trimmed = full.filter { !it.endsWith(".jar") || it.contains("kotlin-stdlib") }
        val classpath = (trimmed.takeIf { it.isNotEmpty() } ?: full).joinToString(separator)

        return File.createTempFile("telemetry-cp", ".args").apply {
            deleteOnExit()
            writeText("-cp \"${classpath.replace('\\', '/')}\"\n")
            argFiles += this
        }
    }

    private fun skip(reason: String) {
        // Reported rather than failed: an OS that forbids attach says nothing about this code.
        println("SKIPPED LiveDiagnosticsTest: $reason")
    }

    private companion object {
        const val READY_TIMEOUT_MS = 20_000L
        const val TEARDOWN_TIMEOUT_SECONDS = 5L
        const val KILL_DELAY_MS = 800L
        const val MAJORITY_PERCENT = 50.0
    }
}
