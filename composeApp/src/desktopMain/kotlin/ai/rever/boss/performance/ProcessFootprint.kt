package ai.rever.boss.performance

import ai.rever.boss.plugin.browser.FluckEngine
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * How much physical memory BOSS is actually holding, summed over every process it owns.
 *
 * Exists because the JVM heap - the only memory number the status bar has ever shown - is a
 * small and unrepresentative slice of the real footprint. Measured on a loaded session: 299 MB
 * of live heap inside a 2,537 MB host process, with a further 907 MB in the Chromium tree. The
 * bar reported the 299 MB. Every memory incident this app has had lived in the other 3.1 GB:
 * orphaned plugin host JVMs (434 of them, 27 GB), recycled browser engines whose old Chromium
 * was never killed, and PartitionAlloc aborting the process when native allocation fails. None
 * of those move the heap number at all, so the one always-visible memory reading in the app was
 * structurally incapable of showing any of them.
 *
 * ## Ownership, not parentage
 *
 * Processes are claimed by matching their command line, never by walking
 * `ProcessHandle.descendants()`. This is the single most important decision here and it is not
 * a stylistic one. Measured on a real session, the descendant tree from the host JVM totalled
 * 7,858 MB, of which **4,414 MB was the user's own work**: four `claude` CLIs started in
 * terminal tabs, their Python and MCP children, and a `cloudflared` tunnel. Those are spawned
 * by BOSS in the sense that a shell spawned them, and charging them to BOSS would paint the bar
 * red for a workload BOSS does not control and cannot release.
 *
 * Parentage also fails in the other direction. Two Chromium helpers on that same machine had
 * been reparented to pid 1 and sat outside the descendant tree entirely. Orphaned engine
 * processes are a bug this indicator is specifically meant to surface, so a walk that cannot see
 * them would miss the case it exists for.
 *
 * ## What the numbers mean, and how they are biased
 *
 * Per-platform, because there is no portable answer:
 *
 *  - **Linux**: `Pss` from `/proc/<pid>/smaps_rollup`. Proportional set size divides each shared
 *    page among its mappers, so summing across the Chromium tree does not count its shared
 *    framework once per renderer. Falls back to `VmRSS` on kernels without smaps_rollup (< 4.14),
 *    which over-counts exactly as macOS does.
 *  - **macOS**: `rss` from `ps`. This **over-counts**: Mach reports shared pages in full against
 *    every task mapping them, so a seven-process Chromium tree charges its shared framework
 *    seven times. The honest figure is `phys_footprint`, which is what Activity Monitor shows,
 *    but it is reachable only through `task_info` and there is no JNI in this app to reach it.
 *  - **Windows**: `WorkingSet64`, which shares the same shared-page over-count.
 *
 * The bias is toward reporting more memory than BOSS is really holding. That is the wrong
 * direction for a threshold, which is why **the displayed number does not drive the colour**:
 * health comes from [ai.rever.boss.config.SystemMemory.freeFraction], a reading of the machine
 * rather than of us, and one that is unaffected by how we attribute shared pages. The footprint
 * answers "what is BOSS holding"; the colour answers "is this machine in trouble". Conflating
 * them would inherit this bias into the alerting.
 */
object ProcessFootprint {
    private val logger = BossLogger.forComponent("ProcessFootprint")

    /**
     * How long a measured reading may be reused.
     *
     * The work is split across two cadences because its two halves cost very different amounts.
     * Measured on a machine running 1,227 processes:
     *
     *  - enumerating processes: **~1 ms**
     *  - reading every process's command line to decide ownership: **~95 ms**
     *  - querying memory for the dozen pids we own: **~48 ms**, nearly all of it process-spawn
     *    overhead on macOS, and free on Linux where it is a `/proc` read
     *
     * Doing all of it on one 10 s cadence cost ~150 ms every 10 s, about 1.5% of a core burnt
     * continuously to render one status-bar glyph. That is a poor trade on any machine and an
     * indefensible one on the small, battery-powered machines this indicator is meant to help.
     *
     * So ownership is discovered rarely and memory is measured often: roughly 95 + 4x48 ms a
     * minute, under 0.5% of a core, and lower again on Linux.
     */
    internal const val MEASURE_TTL_MS = 15_000L

    /**
     * How often the whole process table is reclassified from scratch, ignoring what is known.
     *
     * Rare, because it is the expensive path and it is almost never the one that finds anything.
     * Ownership is normally decided from the *pid-set delta*: enumerating pids costs 0-2 ms and
     * reading a command line costs well under a millisecond each, so classifying only the handful
     * of pids that appeared since the last tick is effectively free, where reading all 1,219 of
     * them costs ~70 ms. Measured on the machine this was written on.
     *
     * The delta is exact rather than a proxy - every new pid is classified and every dead one is
     * dropped - so this exists for one residual case the delta cannot see: a process that calls
     * `exec` in place, keeping its pid while its command line changes from something we do not own
     * to something we do. Unlikely for our targets, which are all spawned fresh, but cheap to
     * cover at this interval.
     */
    internal const val FULL_RESCAN_TTL_MS = 300_000L

    /** Upper bound on how long the per-platform memory query may take before it is abandoned. */
    private const val QUERY_TIMEOUT_SECONDS = 5L

    /** Which part of BOSS a process belongs to. */
    internal enum class Owner { HOST, BROWSER, PLUGIN }

    /**
     * A footprint reading, split by the part of BOSS responsible for it.
     *
     * Split rather than a single total because the split is what makes the number actionable:
     * "3.4 GB" prompts a shrug, "2.5 GB host / 0.9 GB browser" tells you where to look, and a
     * plugin figure that climbs while nothing else does is the orphaned-host signature.
     */
    data class Reading(
        val hostBytes: Long,
        val browserBytes: Long,
        val pluginBytes: Long,
        val processCount: Int,
    ) {
        val totalBytes: Long get() = hostBytes + browserBytes + pluginBytes
    }

    @Volatile private var cached: Reading? = null

    @Volatile private var cachedAtMs: Long = 0L

    /** Owned pids and what each one is, carried forward between ticks. */
    @Volatile private var ownedPids: Map<Long, Owner> = emptyMap()

    /** Every pid seen at the last enumeration, owned or not, so the next delta is exact. */
    @Volatile private var knownPids: Set<Long> = emptySet()

    @Volatile private var fullScanAtMs: Long = 0L

    /**
     * The current footprint, or null when it cannot be read.
     *
     * Null means "unknown" and must never be rendered as zero or treated as "no memory in use".
     * Callers fall back to the heap reading, which is always available. This mirrors
     * [ai.rever.boss.config.SystemMemory], where an unreadable value deliberately does not
     * trigger any reduced behaviour.
     */
    fun current(nowMs: Long = System.currentTimeMillis()): Reading? {
        // Nobody can read a glyph that is not on screen, and this is the only consumer of the
        // reading - the pressure watchdog has its own SystemMemory path. So a hidden bar costs
        // nothing at all rather than 0.3% of a core, which is not a rare case: focus mode hides
        // the bottom bar per edge, Windows ships with it, and a bar switched off in settings
        // never comes back without the View menu.
        //
        // Returns the last reading rather than null so that re-mounting draws a number
        // immediately and refreshes within one sample tick, instead of flashing the heap
        // fallback first.
        val reusable = cached
        if (!FootprintDisplay.isOnScreen || (reusable != null && nowMs - cachedAtMs < MEASURE_TTL_MS)) {
            return reusable
        }

        val fresh = read(nowMs)
        if (fresh != null) {
            cached = fresh
            cachedAtMs = nowMs
        }
        return fresh
    }

    /**
     * Which pids need their command line read this tick.
     *
     * Pure, so the incremental rule is testable without a process table. Everything on a full
     * rescan; otherwise only what has appeared since the last enumeration, which on a settled
     * machine is nothing at all.
     */
    internal fun pidsToClassify(
        livePids: Set<Long>,
        knownPids: Set<Long>,
        fullRescan: Boolean,
    ): Set<Long> = if (fullRescan) livePids else livePids - knownPids

    /**
     * The owned-pid map carried into this tick, with the dead dropped.
     *
     * Dropping by liveness is what makes a departed process stop counting without any rescan: a
     * closed browser tab's renderer is gone from `livePids` and therefore gone from the total on
     * the very next tick. A full rescan starts from nothing instead, since it is about to
     * reclassify everything anyway.
     */
    internal fun retainLive(
        owned: Map<Long, Owner>,
        livePids: Set<Long>,
        fullRescan: Boolean,
    ): Map<Long, Owner> = if (fullRescan) emptyMap() else owned.filterKeys { it in livePids }

    private fun read(nowMs: Long): Reading? =
        runCatching {
            // 0-2 ms. Everything below is scoped by the delta this produces.
            val handles = ProcessHandle.allProcesses().toList()
            val livePids = handles.mapTo(HashSet()) { it.pid() }

            val fullRescan = nowMs - fullScanAtMs >= FULL_RESCAN_TTL_MS
            val toClassify = pidsToClassify(livePids, knownPids, fullRescan)

            if (toClassify.isNotEmpty()) {
                val hostPid = ProcessHandle.current().pid()
                // Taken from FluckEngine rather than hardcoding ~/.boss/boss-chromium, because a
                // packaged build can run the engine bundled inside the app image instead, and a
                // hardcoded cache path would silently score every such install's browser at zero.
                // Both candidates are matched; only one of them can have live processes.
                val engineDirs =
                    runCatching {
                        FluckEngine.engineLocations().map { it.toAbsolutePath().toString() }
                    }.getOrElse { emptyList() }
                val found =
                    handles
                        .filter { it.pid() in toClassify }
                        .mapNotNull { handle ->
                            val owner =
                                classify(
                                    pid = handle.pid(),
                                    commandLine = handle.info().commandLine().orElse(""),
                                    hostPid = hostPid,
                                    engineDirs = engineDirs,
                                ) ?: return@mapNotNull null
                            handle.pid() to owner
                        }
                ownedPids = retainLive(ownedPids, livePids, fullRescan) + found
            } else {
                ownedPids = retainLive(ownedPids, livePids, fullRescan)
            }

            knownPids = livePids
            if (fullRescan) fullScanAtMs = nowMs

            val owned = ownedPids
            if (owned.isEmpty()) return@runCatching null

            val memory = queryMemoryBytes(owned.keys.toList())
            // A reading that resolved no process at all is a failed query, not an empty machine:
            // we know at minimum that this very JVM is running and holding memory.
            if (memory.isEmpty()) return@runCatching null

            var host = 0L
            var browser = 0L
            var plugin = 0L
            for ((pid, owner) in owned) {
                val bytes = memory[pid] ?: continue
                when (owner) {
                    Owner.HOST -> host += bytes
                    Owner.BROWSER -> browser += bytes
                    Owner.PLUGIN -> plugin += bytes
                }
            }
            Reading(host, browser, plugin, memory.size)
        }.onFailure {
            logger.debug(LogCategory.SYSTEM, "Footprint reading failed: ${it.message}")
        }.getOrNull()

    /**
     * Which part of BOSS owns a process, or null when it is not ours.
     *
     * Pure, and internal, so the ownership rule is testable against fixture command lines rather
     * than against whatever the developer's machine happens to be running. The regression this
     * guards is specific: a rule that claimed the user's terminal children charged BOSS an extra
     * 4.4 GB on the machine this was written on.
     */
    internal fun classify(
        pid: Long,
        commandLine: String,
        hostPid: Long,
        engineDirs: List<String>,
    ): Owner? =
        when {
            pid == hostPid -> Owner.HOST

            // Matches the spawner's own marker class, so it tracks whatever the plugin host is
            // named rather than a jar-name convention.
            commandLine.contains("PluginProcessMainKt") -> Owner.PLUGIN

            // Both the Chromium main process and every helper are launched from an executable
            // inside the resolved engine directory, so a path-prefix test catches the whole tree
            // including any member that has been reparented away from us.
            engineDirs.any { it.isNotEmpty() && commandLine.startsWith(it) } -> Owner.BROWSER

            else -> null
        }

    /** Physical memory per pid, keyed by pid. Missing entries mean that pid could not be read. */
    private fun queryMemoryBytes(pids: List<Long>): Map<Long, Long> {
        if (pids.isEmpty()) return emptyMap()
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            // Plain file reads: `Pss` where the kernel offers it, `VmRSS` on kernels before 4.14.
            // Unlike the other two platforms this costs no subprocess at all, which is why Linux
            // pays almost nothing for this feature.
            os.startsWith("linux") -> {
                pids
                    .mapNotNull { pid ->
                        runCatching {
                            val rollup = File("/proc/$pid/smaps_rollup")
                            if (rollup.exists()) {
                                parseProcKb(rollup.readText(), "Pss:")?.times(1024L)
                            } else {
                                parseProcKb(File("/proc/$pid/status").readText(), "VmRSS:")?.times(1024L)
                            }
                        }.getOrNull()?.let { pid to it }
                    }.toMap()
            }

            os.startsWith("mac") -> {
                parsePsRssOutput(runQuery(listOf("/bin/ps", "-o", "pid=,rss=", "-p", pids.joinToString(","))))
            }

            // startsWith, not contains: "darwin" contains "win".
            //
            // PowerShell rather than `tasklist`, whose "Mem Usage" column is localised and
            // thousands-separated, and rather than `wmic`, which recent Windows builds no longer
            // ship. `ConvertTo-Csv` is invariant.
            os.startsWith("windows") -> {
                parseWindowsCsv(
                    runQuery(
                        listOf(
                            "powershell.exe",
                            "-NoProfile",
                            "-NonInteractive",
                            "-Command",
                            "Get-Process -Id ${pids.joinToString(",")} -ErrorAction SilentlyContinue | " +
                                "Select-Object Id,WorkingSet64 | ConvertTo-Csv -NoTypeInformation",
                        ),
                    ),
                )
            }

            else -> {
                emptyMap()
            }
        }
    }

    /** A `<Label>: <n> kB` field from a /proc file, in kB, or null when absent. */
    internal fun parseProcKb(
        text: String,
        label: String,
    ): Long? =
        text
            .lineSequence()
            .firstOrNull { it.startsWith(label) }
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
            ?.toLongOrNull()

    /** `pid rssKb` pairs from `ps -o pid=,rss=`, as pid to bytes. */
    internal fun parsePsRssOutput(output: String?): Map<Long, Long> {
        if (output == null) return emptyMap()
        return output
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 2) return@mapNotNull null
                val pid = parts[0].toLongOrNull() ?: return@mapNotNull null
                val rssKb = parts[1].toLongOrNull() ?: return@mapNotNull null
                pid to rssKb * 1024L
            }.toMap()
    }

    /** `"Id","WorkingSet64"` CSV rows from PowerShell, as pid to bytes. */
    internal fun parseWindowsCsv(output: String?): Map<Long, Long> {
        if (output == null) return emptyMap()
        return output
            .lineSequence()
            .mapNotNull { line ->
                val cells = line.trim().split(",").map { it.trim().trim('"') }
                if (cells.size < 2) return@mapNotNull null
                val pid = cells[0].toLongOrNull() ?: return@mapNotNull null
                val bytes = cells[1].toLongOrNull() ?: return@mapNotNull null
                pid to bytes
            }.toMap()
    }

    /**
     * Run a query and return its output, or null on failure or timeout.
     *
     * Waits before draining, which is only safe because every command here is filtered to an
     * explicit pid list. `SystemMemory.vmStatAvailableBytes` carries the warning this obeys: with
     * unbounded output that ordering deadlocks, because the child blocks writing to a full pipe
     * while nothing drains it and `waitFor` is therefore never reached. The bound is concrete -
     * one short line per owned process, a few dozen at the very most, against a pipe buffer
     * measured in tens of kilobytes. An unfiltered `Get-Process` or `ps -A` would break that and
     * must not be substituted in.
     */
    private fun runQuery(command: List<String>): String? =
        runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            try {
                if (!process.waitFor(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    return@runCatching null
                }
                process.inputStream.bufferedReader().use { it.readText() }
            } finally {
                // The timeout branch returns without reading either stream, and destroyForcibly
                // does not close them, so without this they are left to the finalizer.
                process.inputStream.close()
                process.errorStream.close()
                process.outputStream.close()
            }
        }.getOrNull()
}
