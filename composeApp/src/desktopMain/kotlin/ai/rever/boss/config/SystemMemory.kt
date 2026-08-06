package ai.rever.boss.config

import java.io.File
import java.lang.management.ManagementFactory

/**
 * Physical-memory readings for the host machine.
 *
 * Uses a **direct cast** to `com.sun.management.OperatingSystemMXBean`, matching
 * `PerformanceMonitor.sunOSBean`. This started out reflective and that was a mistake worth
 * recording, because it looked like it worked:
 *
 * `ManagementFactory.getOperatingSystemMXBean()` returns
 * `com.sun.management.internal.OperatingSystemImpl`, whose package `jdk.management` neither
 * exports nor opens. Resolving a method on that **implementation class** and calling
 * `setAccessible(true)` throws `InaccessibleObjectException` on JDK 17, 22 and 26 alike, and
 * invoking without it throws `IllegalAccessException`. The reflective version nonetheless
 * returned the right number, purely because `getTotalPhysicalMemorySize` is a *deprecated
 * default method on the exported interface* and happened to be tried first; its modern
 * replacement `getTotalMemorySize` is declared on the impl and could never have worked. So the
 * "try both spellings so a JDK bump cannot break us" fallback was backwards: the fallback was
 * the broken one, and the day the deprecated shim is finally removed the reading would have
 * silently become 0.
 *
 * Silently is the operative word. Every caller treats 0 as "unknown", so automatic tier
 * selection and the whole memory-pressure watchdog would have quietly become dead code with
 * nothing in the logs and every test still green. `SystemMemoryTest` now pins the real reading.
 *
 * Every method still returns 0 rather than throwing when a reading is unavailable. Callers must
 * treat 0 as "unknown" and not as "no memory" - see [ResourceModeConfig.detectResourceMode],
 * where a failed read deliberately does *not* trigger a reduced tier.
 */
object SystemMemory {
    private val osBean: com.sun.management.OperatingSystemMXBean? =
        ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean

    /** Total installed RAM in bytes, or 0 when it cannot be read. */
    fun totalPhysicalBytes(): Long = osBean?.totalMemorySize?.takeIf { it > 0L } ?: 0L

    /**
     * Memory available for allocation without swapping, in bytes, or 0 when it cannot be read.
     *
     * On Linux this reads `MemAvailable` from `/proc/meminfo` in preference to the JDK's
     * `getFreeMemorySize`, which reports `MemFree`. The two are very different numbers on a
     * healthy machine: `MemFree` excludes the page cache, so a Linux box that has been up for a
     * while routinely shows single-digit-percent `MemFree` while many gigabytes are reclaimable
     * on demand. Driving [freeFraction] off `MemFree` would make the memory-pressure watchdog
     * fire on ordinary healthy systems, and because tightening is one-way that costs the user
     * their browser cap for the rest of the session.
     */
    fun availableBytes(): Long = linuxMemAvailableBytes() ?: (osBean?.freeMemorySize ?: 0L)

    /**
     * Available RAM as a fraction of total, in `0.0..1.0`, or null when either reading failed.
     *
     * Null is distinct from 0.0 on purpose: the memory-pressure watchdog must not read an
     * unreadable bean as "no memory left" and start refusing the user's browser tabs.
     */
    fun freeFraction(): Double? {
        val total = totalPhysicalBytes()
        val available = availableBytes()
        if (total <= 0L || available <= 0L) return null
        return (available.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
    }

    /**
     * `MemAvailable` from `/proc/meminfo` in bytes, or null off Linux / when unreadable.
     *
     * The field has been present since Linux 3.14 and is the kernel's own estimate of what a new
     * allocation could get without swapping, which is the question this feature is actually
     * asking.
     */
    private fun linuxMemAvailableBytes(): Long? =
        runCatching {
            val meminfo = File("/proc/meminfo")
            if (!meminfo.exists()) return@runCatching null
            meminfo
                .useLines { lines ->
                    lines.firstOrNull { it.startsWith("MemAvailable:") }
                }?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?.times(1024L)
        }.getOrNull()
}
