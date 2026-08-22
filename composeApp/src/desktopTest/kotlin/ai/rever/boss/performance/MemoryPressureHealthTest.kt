package ai.rever.boss.performance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Guards the signal behind the status-bar colour.
 *
 * Before this, the colour came from heap-used over heap-max. On a 128 GB machine the heap
 * ceiling is ~30 GB by JVM ergonomics and a loaded session holds ~300 MB, so the indicator read
 * 1% and could not have reached its 75% warning band without 22 GB of live objects. It was green
 * during a 27 GB plugin-host leak. These tests pin the replacement signal and, more importantly,
 * pin it to the same threshold the watchdog acts on.
 */
class MemoryPressureHealthTest {
    private fun metrics(
        available: Long,
        total: Long,
        heapUsed: Long = 100L * 1024 * 1024,
        heapMax: Long = 4L * 1024 * 1024 * 1024,
    ) = MemoryMetrics(
        heapUsedBytes = heapUsed,
        heapMaxBytes = heapMax,
        heapCommittedBytes = heapUsed,
        nonHeapUsedBytes = 0,
        nonHeapCommittedBytes = 0,
        systemAvailableBytes = available,
        systemTotalBytes = total,
    )

    private fun snapshot(memory: MemoryMetrics) =
        PerformanceSnapshot(
            timestamp = 0L,
            memory = memory,
            cpu = CpuMetrics(processLoad = 0.0, systemLoad = 0.0, availableProcessors = 8, activeThreadCount = 1),
            gc =
                GcMetrics(
                    collectionCount = 0,
                    collectionTimeMs = 0,
                    gcTimeSinceLastSampleMs = 0,
                    gcCollectors = emptyList(),
                ),
            resources =
                ResourceMetrics(
                    browserTabCount = 0,
                    terminalCount = 0,
                    editorTabCount = 0,
                    panelCount = 0,
                    windowCount = 0,
                ),
        )

    private val gb = 1024L * 1024 * 1024

    @Test
    fun `the indicator threshold is the watchdog threshold`() {
        // Not a tautology now that one is defined in terms of the other: it is the guard that
        // keeps them that way. If they drift, the bar turns red at a different moment from the
        // one the watchdog starts acting on, and the modal goes back to being an ambush.
        assertEquals(MemoryPressureWatchdog.PRESSURE_THRESHOLD, MemoryPressure.CRITICAL_AVAILABLE_FRACTION)
    }

    @Test
    fun `pressure maps to health at the documented bands`() {
        assertEquals(HealthStatus.GOOD, MemoryPressure.statusFor(0.50))
        assertEquals(HealthStatus.WARNING, MemoryPressure.statusFor(MemoryPressure.WARNING_AVAILABLE_FRACTION))
        assertEquals(HealthStatus.WARNING, MemoryPressure.statusFor(0.20))
        assertEquals(HealthStatus.CRITICAL, MemoryPressure.statusFor(MemoryPressure.CRITICAL_AVAILABLE_FRACTION))
        assertEquals(HealthStatus.CRITICAL, MemoryPressure.statusFor(0.01))
    }

    @Test
    fun `an unreadable machine is not pressure`() {
        // The asymmetry SystemMemory documents: unknown must never be read as "full", or an
        // unreadable bean paints the bar red on a machine with plenty of room.
        assertEquals(HealthStatus.GOOD, MemoryPressure.statusFor(null))
        assertNull(metrics(available = 0, total = 128 * gb).systemAvailableFraction)
        assertNull(metrics(available = 8 * gb, total = 0).systemAvailableFraction)
    }

    @Test
    fun `a full machine is critical even with an idle heap`() {
        // The whole point: 100 MB of a 4 GB heap is 2.4%, nowhere near any heap threshold.
        val snap = snapshot(metrics(available = 4 * gb, total = 128 * gb))
        val health = PerformanceHealth.fromSnapshot(snap, PerformanceSettings())
        assertEquals(HealthStatus.CRITICAL, health.memoryStatus)
        assertEquals(HealthStatus.CRITICAL, health.overall)
    }

    @Test
    fun `a full heap is still critical on a machine with room`() {
        // Pressure is added to the heap signal, never substituted for it. Heap exhaustion did not
        // stop being a way to die when we started watching the machine as well.
        val heapUsed = 39L * gb / 10
        val full = metrics(available = 100 * gb, total = 128 * gb, heapUsed = heapUsed, heapMax = 4L * gb)
        val health = PerformanceHealth.fromSnapshot(snapshot(full), PerformanceSettings())
        assertEquals(HealthStatus.CRITICAL, health.memoryStatus)
    }

    @Test
    fun `a healthy machine with an idle heap is good`() {
        val snap = snapshot(metrics(available = 100 * gb, total = 128 * gb))
        assertEquals(HealthStatus.GOOD, PerformanceHealth.fromSnapshot(snap, PerformanceSettings()).overall)
    }

    @Test
    fun `an unread footprint is not displayed as zero`() {
        assertEquals(false, metrics(available = 8 * gb, total = 128 * gb).footprintKnown)
        assertEquals(true, metrics(available = 8 * gb, total = 128 * gb).copy(footprintBytes = 3 * gb).footprintKnown)
    }
}
