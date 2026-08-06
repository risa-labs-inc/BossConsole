package ai.rever.boss.performance

import ai.rever.boss.config.BossResourceMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the hysteresis in [MemoryPressureWatchdog].
 *
 * Both directions of getting this wrong are bad in a specific way. Too eager and a transient dip
 * during a build silently caps the user's browser for the rest of the session, with no way back
 * short of a restart (the tightening is deliberately one-way). Too reluctant and the watchdog
 * does nothing before PartitionAlloc aborts the process, which is the failure it exists to
 * prevent.
 */
class MemoryPressureWatchdogTest {
    private val t0 = 1_000_000L
    private val sustained = t0 + MemoryPressureWatchdog.SUSTAIN_MS
    private val low = MemoryPressureWatchdog.PRESSURE_THRESHOLD / 2
    private val fine = 0.5

    // region the sustain clock

    @Test
    fun `pressure starts the clock and plenty of memory clears it`() {
        assertEquals(t0, MemoryPressureWatchdog.nextPressureSince(low, null, t0))
        assertNull(MemoryPressureWatchdog.nextPressureSince(fine, t0, t0 + 1))
    }

    @Test
    fun `the clock keeps its original start while pressure persists`() {
        val started = MemoryPressureWatchdog.nextPressureSince(low, null, t0)
        val later = MemoryPressureWatchdog.nextPressureSince(low, started, t0 + 30_000)
        assertEquals(t0, later, "the clock must not restart on every poll")
    }

    /**
     * An unreadable MXBean reports null, which is "unknown" and not "full". Treating it as
     * pressure would let one failed reflective call cap a machine with plenty of room, and the
     * clock has to reset too - otherwise a single good reading followed by failures would
     * accumulate toward a downgrade nothing ever measured.
     */
    @Test
    fun `an unreadable reading is not pressure and resets the clock`() {
        assertNull(MemoryPressureWatchdog.nextPressureSince(null, null, t0))
        assertNull(MemoryPressureWatchdog.nextPressureSince(null, t0, sustained))
        assertFalse(
            MemoryPressureWatchdog.shouldTighten(null, t0, sustained, BossResourceMode.FULL),
        )
    }

    @Test
    fun `the threshold boundary counts as pressure`() {
        val exactly = MemoryPressureWatchdog.PRESSURE_THRESHOLD
        assertEquals(t0, MemoryPressureWatchdog.nextPressureSince(exactly, null, t0))
        // And just above it does not.
        assertNull(MemoryPressureWatchdog.nextPressureSince(exactly + 0.001, null, t0))
    }

    // endregion

    // region the downgrade decision

    @Test
    fun `sustained pressure tightens`() {
        assertTrue(
            MemoryPressureWatchdog.shouldTighten(low, t0, sustained, BossResourceMode.FULL),
        )
    }

    @Test
    fun `a brief dip does not tighten`() {
        for (elapsed in listOf(0L, 1_000L, MemoryPressureWatchdog.SUSTAIN_MS - 1)) {
            assertFalse(
                MemoryPressureWatchdog.shouldTighten(low, t0, t0 + elapsed, BossResourceMode.FULL),
                "must not act after only ${elapsed}ms of pressure",
            )
        }
    }

    @Test
    fun `plenty of memory never tightens however long it is observed`() {
        assertFalse(
            MemoryPressureWatchdog.shouldTighten(fine, t0, sustained + 1_000_000, BossResourceMode.FULL),
        )
    }

    @Test
    fun `pressure with no clock started does not tighten`() {
        assertFalse(
            MemoryPressureWatchdog.shouldTighten(low, null, sustained, BossResourceMode.FULL),
        )
    }

    /**
     * Already-reduced sessions are left alone. LITE is as far as a live downgrade can go, since
     * ULTRA_LITE's distinguishing lever is plugin gating and loaded plugins cannot be unloaded
     * to reclaim memory - so acting again would announce a saving that did not happen.
     */
    @Test
    fun `an already-reduced session is left alone`() {
        for (mode in listOf(BossResourceMode.LITE, BossResourceMode.ULTRA_LITE)) {
            assertFalse(
                MemoryPressureWatchdog.shouldTighten(low, t0, sustained, mode),
                "${mode.name} has no live lever left",
            )
        }
    }

    // endregion

    @Test
    fun `tightening is one-way`() {
        // FULL < LITE < ULTRA_LITE, increasingly constrained. The watchdog relies on this
        // ordering to refuse a loosening, so a reordering of the enum would silently let a
        // recovered memory reading hand back the caps.
        assertTrue(BossResourceMode.FULL.ordinal < BossResourceMode.LITE.ordinal)
        assertTrue(BossResourceMode.LITE.ordinal < BossResourceMode.ULTRA_LITE.ordinal)
    }
}
