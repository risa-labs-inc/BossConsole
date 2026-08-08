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
 * returned the right number, purely because the *deprecated* `getTotalPhysicalMemorySize` is a
 * default method the impl does not override, so `getMethod` resolved it to the exported
 * interface - and it happened to be tried first. Its modern replacement `getTotalMemorySize` is
 * declared on that same interface (JDK 14+) but **is** overridden by the impl, so `getMethod`
 * resolves it to the non-exported override and access fails. So the "try both spellings so a JDK
 * bump cannot break us" fallback was backwards: the fallback was the broken one, and the day the
 * deprecated shim is finally removed the reading would have silently become 0.
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
    /**
     * Free fraction above which macOS skips the `vm_stat` spawn entirely.
     *
     * Well clear of `MemoryPressureWatchdog.PRESSURE_THRESHOLD`, because `freeMemorySize` is a
     * strict undercount of what is really available: if even that reads comfortable, the true
     * figure is comfortable too.
     */
    private const val MAC_PREFILTER_FRACTION = 0.25

    private const val VM_STAT_TIMEOUT_SECONDS = 5L

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

    private fun macAvailableBytes(): Long? {
        if (!System
                .getProperty("os.name")
                .orEmpty()
                .lowercase()
                .startsWith("mac")
        ) {
            return null
        }

        // Cheap pre-filter. `freeMemorySize` is useless as the decision input (see the KDoc
        // above - it read 0.0073 on a healthy machine) but that is exactly what makes it a good
        // gate: it is always at or below the real figure, so a comfortable reading here proves
        // there is no pressure without spawning anything. At a 15 s poll the alternative is
        // roughly 5,760 subprocesses a day, in a feature whose whole purpose is holding
        // footprint down.
        val total = totalPhysicalBytes()
        val cheap = osBean?.freeMemorySize ?: 0L
        val comfortable = total > 0 && cheap.toDouble() / total > MAC_PREFILTER_FRACTION

        return if (comfortable) cheap else vmStatAvailableBytes()
    }

    private fun vmStatAvailableBytes(): Long? =
        runCatching {
            val process =
                ProcessBuilder("/usr/bin/vm_stat")
                    .redirectErrorStream(true)
                    .start()
            // Safe for vm_stat specifically: its output is a fixed ~1 KB table, well under the
            // OS pipe buffer, so it cannot block writing while nothing drains. Do not copy this
            // ordering to a command with unbounded output - there it would deadlock until the
            // timeout. It is this way round because:
            //
            // waitFor FIRST, then drain. Reading to EOF before waiting made the timeout
            // unreachable: a wedged vm_stat blocks in readText(), so waitFor was never called
            // and the guard described an intent the code did not implement.
            if (!process.waitFor(VM_STAT_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@runCatching null
            }
            parseVmStatAvailableBytes(process.inputStream.bufferedReader().use { it.readText() })
        }.getOrNull()

    /**
     * Reclaimable bytes from `vm_stat` output, or null when it cannot be parsed.
     *
     * Sums free, inactive, speculative and purgeable pages. Split out and internal so the
     * arithmetic is testable against captured fixture text rather than against whatever the
     * developer's machine happens to be doing.
     *
     * **This over-counts.** Mach's buckets are not disjoint: speculative and purgeable pages
     * overlap the others, so the sum is an upper bound on what is really reclaimable. The bias
     * is deliberately toward "plenty available", which makes the watchdog under-fire rather than
     * false-alarm, and under-firing is the safe direction for a one-way tighten. But it does
     * compound the uncertainty in `MemoryPressureWatchdog.PRESSURE_THRESHOLD`, which is already
     * uncalibrated - so whoever calibrates it should know the input is biased high.
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
