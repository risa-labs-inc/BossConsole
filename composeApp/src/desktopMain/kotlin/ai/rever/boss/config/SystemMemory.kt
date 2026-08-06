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
     * Memory available for allocation without evicting the user's working set, in bytes, or 0
     * when it cannot be read.
     *
     * **Not** the JDK's `getFreeMemorySize` on Linux or macOS, and the difference is not academic.
     * That call reports genuinely-unused pages, which a healthy operating system deliberately
     * keeps near zero: it would rather cache than idle. Measured on a 128 GB Mac that macOS itself
     * described as "92% free", `getFreeMemorySize` returned 0.9 GB, a free fraction of **0.0073**.
     * Driving the watchdog off that would have tightened the tier one-way and shown an
     * interruptive modal on essentially every Mac session, one minute in, on machines with over a
     * hundred gigabytes spare. That is the precise failure this method exists to avoid, so each
     * platform gets the reading that actually answers "could a large allocation succeed".
     *
     *  - **Linux**: `MemAvailable` from `/proc/meminfo`, the kernel's own estimate. `MemFree`
     *    excludes the page cache and misleads for the same reason.
     *  - **macOS**: free + inactive + speculative + purgeable from `vm_stat`. Inactive and
     *    purgeable pages are reclaimable on demand, and on a warm Mac they are most of the
     *    reclaimable total.
     *  - **Elsewhere** (Windows): the JDK reading, which maps to `ullAvailPhys` and already means
     *    "available" rather than "untouched".
     */
    fun availableBytes(): Long =
        linuxMemAvailableBytes()
            ?: macAvailableBytes()
            ?: (osBean?.freeMemorySize ?: 0L)

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
            parseMemAvailableKb(meminfo.readText())?.times(1024L)
        }.getOrNull()

    /** `MemAvailable` in kB from `/proc/meminfo` text, or null when absent. */
    internal fun parseMemAvailableKb(meminfo: String): Long? =
        meminfo
            .lineSequence()
            .firstOrNull { it.startsWith("MemAvailable:") }
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
            ?.toLongOrNull()

    private fun macAvailableBytes(): Long? =
        runCatching {
            if (!System
                    .getProperty("os.name")
                    .orEmpty()
                    .lowercase()
                    .startsWith("mac")
            ) {
                return@runCatching null
            }
            val process =
                ProcessBuilder("/usr/bin/vm_stat")
                    .redirectErrorStream(true)
                    .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            // Do not let a wedged vm_stat hold the polling coroutine.
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@runCatching null
            }
            parseVmStatAvailableBytes(output)
        }.getOrNull()

    /**
     * Reclaimable bytes from `vm_stat` output, or null when it cannot be parsed.
     *
     * Sums free, inactive, speculative and purgeable pages. Split out and internal so the
     * arithmetic is testable against captured fixture text rather than against whatever the
     * developer's machine happens to be doing.
     */
    internal fun parseVmStatAvailableBytes(output: String): Long? {
        val pageSize = vmStatField(output, "page size of (\\d+) bytes") ?: return null

        val counts =
            listOf("Pages free", "Pages inactive", "Pages speculative", "Pages purgeable")
                .map { label -> vmStatField(output, "^\\Q$label\\E:\\s+(\\d+)") }

        // Free is the only one we insist on; the others vary by macOS version and a missing
        // optional field should narrow the estimate, not discard the reading entirely.
        return counts.first()?.let { counts.filterNotNull().sum() * pageSize }
    }

    private fun vmStatField(
        output: String,
        pattern: String,
    ): Long? =
        Regex(pattern, RegexOption.MULTILINE)
            .find(output)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
}
