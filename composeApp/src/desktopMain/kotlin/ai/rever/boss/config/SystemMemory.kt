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
     * How long a macOS `vm_stat` reading may be reused before spawning again.
     *
     * Chosen against the consumer, not the poll: `MemoryPressureWatchdog` requires pressure to
     * persist for a full minute before it acts, so a reading this stale cannot change any
     * decision it reaches. At the watchdog's 15 s poll this turns four spawns a minute into one.
     *
     * This replaced a free-fraction pre-filter that could never fire - `freeMemorySize` reads a
     * few thousandths of total on a perfectly healthy Mac, so it failed any sensible gate and
     * spawned every time regardless.
     */
    internal const val CACHE_TTL_MS = 30_000L

    private const val CACHE_TTL_NANOS = CACHE_TTL_MS * 1_000_000L

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
    fun availableBytes(): Long {
        // The platform branch is TOTAL. Chaining these with `?:` looks equivalent and is not: on a
        // Mac where vm_stat is slow, sandboxed or unparseable, the null meant "this reading
        // failed" and fell straight through to freeMemorySize - the 0.9 GB / 0.0073 figure this
        // whole class exists to avoid. That reads as 0.7% free below the watchdog's 12% threshold
        // on every poll, so sixty seconds later a 128 GB machine gets a one-way tighten and an
        // interruptive modal. Exactly the bug this was written to fix, reachable via its fallback.
        //
        // On a known platform an unreadable reading is 0, i.e. "unknown", which freeFraction()
        // maps to null and the watchdog ignores. Same asymmetry as the startup decision: unknown
        // must never be read as pressure.
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.startsWith("linux") -> linuxMemAvailableBytes() ?: 0L

            os.startsWith("mac") -> macAvailableBytes() ?: 0L

            // Windows and anything else: ullAvailPhys already means "available", not "untouched".
            else -> osBean?.freeMemorySize ?: 0L
        }
    }

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
        val isMac =
            System
                .getProperty("os.name")
                .orEmpty()
                .lowercase()
                .startsWith("mac")
        if (!isMac) return null

        // Cached, not pre-filtered. The previous version gated the subprocess on
        // `freeMemorySize / total > 0.25`, which cannot work: the KDoc above records that same
        // number reading 0.0073 on a machine macOS itself called 92% free, and that is precisely
        // why this method exists. A gate that a healthy machine fails is not a gate - it spawned
        // vm_stat on every single poll, the ~5,760-a-day case it was written to avoid.
        //
        // A short TTL is the honest version of the same intent. The watchdog needs sustained
        // pressure over a full minute before it acts, so a reading up to CACHE_TTL_MS stale
        // cannot change any decision it makes, and the spawn rate drops by the poll:TTL ratio.
        val now = System.nanoTime()
        val cached = macCache?.takeIf { now - it.takenAtNanos < CACHE_TTL_NANOS }
        return cached?.bytes ?: vmStatAvailableBytes()?.also { macCache = MacReading(it, now) }
    }

    /** A `vm_stat` reading and when it was taken, so it can be reused briefly. */
    private data class MacReading(
        val bytes: Long,
        val takenAtNanos: Long,
    )

    @Volatile
    private var macCache: MacReading? = null

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
            try {
                if (!process.waitFor(VM_STAT_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    return@runCatching null
                }
                parseVmStatAvailableBytes(process.inputStream.bufferedReader().use { it.readText() })
            } finally {
                // The timeout branch returns without reading either stream, which would leave
                // both descriptors to the finalizer. destroyForcibly does not close them.
                process.inputStream.close()
                process.errorStream.close()
                process.outputStream.close()
            }
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
