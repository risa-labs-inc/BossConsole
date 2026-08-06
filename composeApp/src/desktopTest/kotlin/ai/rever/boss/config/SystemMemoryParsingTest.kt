package ai.rever.boss.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the per-platform "available memory" parsers against captured fixture text.
 *
 * Separate from [SystemMemoryTest], which reads the live machine: these run the arithmetic
 * against known input, so the Linux and macOS paths are covered on a CI runner that is neither.
 *
 * The numbers below are real, captured from the 128 GB Mac where the original bug was found.
 * `getFreeMemorySize` reported 0.9 GB there, a free fraction of 0.0073, while macOS itself
 * described the machine as 92% free - which would have tightened the tier one-way and shown a
 * modal on essentially every Mac session.
 */
class SystemMemoryParsingTest {
    private val vmStat =
        """
        Mach Virtual Memory Statistics: (page size of 16384 bytes)
        Pages free:                                    38262.
        Pages active:                                3804162.
        Pages inactive:                              3831697.
        Pages speculative:                             11861.
        Pages throttled:                                   0.
        Pages wired down:                             421306.
        Pages purgeable:                              132089.
        """.trimIndent()

    @Test
    fun `vm_stat available counts free, inactive, speculative and purgeable`() {
        val expectedPages = 38262L + 3831697L + 11861L + 132089L
        assertEquals(expectedPages * 16384L, SystemMemory.parseVmStatAvailableBytes(vmStat))
    }

    /**
     * The regression this whole parser exists for. Against the same machine's readings, the JDK's
     * free-pages number is 0.7% and would trip the 12% threshold; counting reclaimable pages puts
     * it around 50%, which is the truth.
     */
    @Test
    fun `the mac reading is nowhere near the pressure threshold on a healthy machine`() {
        val total = 128.0 * 1024 * 1024 * 1024
        val available = SystemMemory.parseVmStatAvailableBytes(vmStat)!!
        val fraction = available / total
        assertTrue(
            fraction > MemoryPressureThresholdRef.value,
            "a healthy Mac must not read as under pressure, but got $fraction",
        )
        assertTrue(fraction > 0.3, "expected roughly half the machine reclaimable, got $fraction")
    }

    @Test
    fun `a missing free count is unparseable rather than zero`() {
        // 0 would be indistinguishable from "no memory left" and would trip the watchdog.
        assertNull(SystemMemory.parseVmStatAvailableBytes("page size of 16384 bytes\nPages active: 5."))
        assertNull(SystemMemory.parseVmStatAvailableBytes(""))
    }

    @Test
    fun `optional vm_stat fields narrow the estimate instead of discarding it`() {
        val minimal = "Mach Virtual Memory Statistics: (page size of 4096 bytes)\nPages free: 100."
        assertEquals(100L * 4096L, SystemMemory.parseVmStatAvailableBytes(minimal))
    }

    @Test
    fun `meminfo reads MemAvailable rather than MemFree`() {
        val meminfo =
            """
            MemTotal:       65790084 kB
            MemFree:         1234567 kB
            MemAvailable:   48765432 kB
            """.trimIndent()
        assertEquals(48765432L, SystemMemory.parseMemAvailableKb(meminfo))
    }

    @Test
    fun `meminfo without MemAvailable is null rather than a guess`() {
        val meminfo = "MemTotal:       65790084 kB\nMemFree:         1234567 kB"
        assertNull(SystemMemory.parseMemAvailableKb(meminfo))
    }
}

/** Indirection so this file does not depend on the watchdog's package internals. */
private object MemoryPressureThresholdRef {
    val value = ai.rever.boss.performance.MemoryPressureWatchdog.PRESSURE_THRESHOLD
}
