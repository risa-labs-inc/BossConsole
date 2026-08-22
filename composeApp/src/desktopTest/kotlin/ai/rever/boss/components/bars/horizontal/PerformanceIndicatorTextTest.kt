package ai.rever.boss.components.bars.horizontal

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the indicator's label, including the three degraded paths.
 *
 * Worth pinning because the two inputs genuinely can be absent at runtime and each has a
 * different "unknown" convention: the footprint is null when no platform reader could produce
 * one, while `SystemMemory` returns 0 rather than throwing. Rendering either as a zero would put
 * "0.0GB" in front of the user and read as a fact rather than a failure.
 */
class PerformanceIndicatorTextTest {
    private val gb = 1024f

    private fun text(
        footprintMB: Float? = 2.1f * gb,
        systemUsedMB: Float? = 70f * gb,
        systemTotalMB: Float? = 128f * gb,
        heapUsedMB: Float = 296f,
        heapMaxMB: Float = 2f * gb,
    ) = memoryIndicatorText(footprintMB, systemUsedMB, systemTotalMB, heapUsedMB, heapMaxMB)

    @Test
    fun `both readings show BOSS then the machine`() {
        assertEquals("2.1GB · 70/128GB", text())
    }

    @Test
    fun `a small machine keeps a decimal on both halves`() {
        assertEquals("512MB · 3.2/8.0GB", text(footprintMB = 512f, systemUsedMB = 3.2f * gb, systemTotalMB = 8f * gb))
    }

    /**
     * The pair is scaled by the total, never each half separately. Formatted independently this
     * would read "900MB/128GB", where the two numbers look comparable and are not.
     */
    @Test
    fun `a sub-gigabyte used figure still scales to the total's unit`() {
        assertEquals("2.1GB · 0.9/128GB", text(systemUsedMB = 900f))
    }

    @Test
    fun `an unreadable footprint leaves the machine pair`() {
        assertEquals("70/128GB", text(footprintMB = null))
    }

    @Test
    fun `an unreadable machine leaves the footprint`() {
        assertEquals("2.1GB", text(systemUsedMB = null, systemTotalMB = null))
    }

    @Test
    fun `losing both falls back to the heap ratio`() {
        assertEquals("296MB/2.0GB", text(footprintMB = null, systemUsedMB = null, systemTotalMB = null))
    }

    @Test
    fun `a half-known machine reading is not rendered`() {
        // Total without available, or the reverse, is not a pair - it must not print "70/0GB".
        assertEquals("2.1GB", text(systemUsedMB = 70f * gb, systemTotalMB = null))
        assertEquals("2.1GB", text(systemUsedMB = null, systemTotalMB = 128f * gb))
    }
}
