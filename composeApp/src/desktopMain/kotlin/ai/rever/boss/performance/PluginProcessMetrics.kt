package ai.rever.boss.performance

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.Tlhelp32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Resident memory and thread count for one process, either of which may be unreadable.
 *
 * Null means the value could not be read. The two are independent, so a failure to read one does
 * not discard the other.
 */
internal data class OsProcessMetrics(
    val rssBytes: Long?,
    val threadCount: Int?,
)

/**
 * Reports a failing metrics source once, not on every sample.
 *
 * The Performance panel samples every 5 s, so logging each failure would repeat the same line
 * indefinitely, while logging none leaves a broken source indistinguishable from a real zero: a JNA
 * load failure on Windows shows 0 bytes and 0 threads with nothing in the log. A source is reported
 * on its first failure and again only after it has succeeded in between.
 */
internal class FailureLog(
    private val report: (String) -> Unit,
) {
    private val failing = ConcurrentHashMap.newKeySet<String>()

    fun failed(
        source: String,
        cause: Throwable?,
    ) {
        if (failing.add(source)) report("$source failed: ${cause?.message ?: cause?.javaClass?.name ?: "no detail"}")
    }

    fun succeeded(source: String) {
        failing.remove(source)
    }
}

/** Runs a short-lived command with a bound on how long the caller can be held. */
internal object BoundedCommand {
    /**
     * A command's combined output, or a failure when it could not start or did not finish within
     * [timeoutMillis].
     *
     * The output is drained on its own thread while this one waits with a timeout. Neither order on
     * one thread is safe here. Draining first makes the timeout unreachable, since a wedged child
     * blocks the read and the wait is never reached. Waiting first, as [ProcessFootprint.runQuery]
     * does, is safe only for output bounded well under the pipe buffer. `ps -M` prints a line per
     * thread, about 60 bytes each, so its size grows with every plugin's thread count rather than
     * with the pid list, and past the pipe buffer the child would block writing until the timeout.
     *
     * The wait for the child is bounded by [timeoutMillis]; once the child has exited, the drain is
     * given a second bounded wait of the same length, so the caller can be held for up to twice
     * [timeoutMillis] in the worst case.
     *
     * The exit status is ignored, as before: `ps -p` exits non-zero when one pid has exited but still
     * prints the others. An interrupt while waiting is reported as a failure with the interrupt
     * flag restored, and the child is destroyed, as on the timeout path.
     */
    fun run(
        command: List<String>,
        timeoutMillis: Long,
    ): Result<String> {
        val result =
            runCatching {
                val process = ProcessBuilder(command).redirectErrorStream(true).start()
                try {
                    val drain = FutureTask { process.inputStream.bufferedReader().use { it.readText() } }
                    Thread(drain, "plugin-metrics-drain").apply { isDaemon = true }.start()
                    if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly()
                        error("${command.first()} did not finish within $timeoutMillis ms")
                    }
                    // The child has exited, so its end of the pipe is closed and the drain reaches EOF.
                    drain.get(timeoutMillis, TimeUnit.MILLISECONDS)
                } finally {
                    // An interrupted wait or a failed reader start never destroys the child, and
                    // destroyForcibly does not close the streams, so without both lines a
                    // non-timeout failure leaves the child running and the streams to the finalizer.
                    if (process.isAlive) process.destroyForcibly()
                    process.inputStream.close()
                    process.errorStream.close()
                    process.outputStream.close()
                }
            }
        if (result.exceptionOrNull() is InterruptedException) Thread.currentThread().interrupt()
        return result
    }
}

/**
 * OS-level metrics for the Performance panel's out-of-process plugin rows.
 *
 * Each platform reads these differently, and none of them can use one `ps` invocation. `ps -M` is
 * the macOS thread listing, but on Linux procps `-M` adds a security-label column and prints one
 * line per *process*, so every plugin reported exactly 1 thread. Windows has no `ps` at all: the
 * spawn fails, or Git for Windows' MSYS `ps` rejects `-o` and `-M` with `unknown option`, and every
 * plugin reported 0 bytes and 0 threads.
 *
 *  - **Linux**: `VmRSS` and `Threads` from `/proc/<pid>/status`. A file read, no subprocess.
 *  - **macOS**: `/bin/ps -o pid=,rss=` and `/bin/ps -M`, counting thread lines per pid. There is
 *    no `thcount` or `nlwp` keyword on macOS (`ps: thcount: keyword not found`), so the count
 *    cannot come from `-o`. Each `ps` run is bounded by [PS_TIMEOUT_MILLIS], because this is
 *    called on `Dispatchers.Main` and a wedged `ps` would otherwise freeze the app.
 *  - **Windows**: Win32 through JNA, which the app already ships. Thread counts come from one
 *    Toolhelp process snapshot, and memory is `WorkingSetSize` from `GetProcessMemoryInfo`, the
 *    figure [ProcessFootprint] reports as `WorkingSet64`. PowerShell, which [ProcessFootprint] uses,
 *    is not an option here: this runs every 5 s from the panel's snapshot collector, and a
 *    PowerShell start costs far more than that budget allows.
 *
 * Resident memory rather than `Pss` on Linux, unlike [ProcessFootprint]: this row has always shown
 * RSS on every platform, and a per-plugin figure has no shared framework to double-count.
 */
internal object PluginProcessMetrics {
    private const val PROCESS_VM_READ = 0x0010
    private const val BYTES_PER_KB = 1024L

    /** Upper bound on one `ps` run, matching [ProcessFootprint]'s 5 s query bound. */
    internal const val PS_TIMEOUT_MILLIS = 5_000L

    private val logger = BossLogger.forComponent("PluginProcessMetrics")
    private val failures = FailureLog { logger.warn(LogCategory.SYSTEM, it) }

    /** Metrics per pid. A pid that could not be read at all is absent. */
    fun query(
        pids: List<Long>,
        osName: String = System.getProperty("os.name").orEmpty(),
        readProcStatus: (Long) -> String? = ::procStatusText,
        queryMac: (List<Long>) -> Map<Long, OsProcessMetrics> = ::macMetrics,
        queryWindows: (List<Long>) -> Map<Long, OsProcessMetrics> = ::windowsMetrics,
    ): Map<Long, OsProcessMetrics> {
        if (pids.isEmpty()) return emptyMap()
        val os = osName.lowercase()
        return when {
            os.startsWith("linux") -> linuxMetrics(pids, readProcStatus)

            os.startsWith("mac") -> queryMac(pids)

            // startsWith, not contains: "darwin" contains "win".
            os.startsWith("windows") -> queryWindows(pids)

            // BOSS ships for these three platforms only, so any other OS reads nothing.
            else -> emptyMap()
        }
    }

    /** Both figures from each pid's `/proc/<pid>/status`, skipping pids whose file is gone. */
    internal fun linuxMetrics(
        pids: List<Long>,
        readProcStatus: (Long) -> String?,
    ): Map<Long, OsProcessMetrics> =
        pids
            .mapNotNull { pid ->
                val status = readProcStatus(pid) ?: return@mapNotNull null
                pid to
                    OsProcessMetrics(
                        rssBytes = ProcessFootprint.parseProcKb(status, "VmRSS:")?.times(BYTES_PER_KB),
                        threadCount = parseProcThreads(status),
                    )
            }.toMap()

    /** The `Threads:` field of a `/proc/<pid>/status` file. */
    internal fun parseProcThreads(status: String): Int? =
        status
            .lineSequence()
            .firstOrNull { it.startsWith("Threads:") }
            ?.substringAfter(':')
            ?.trim()
            ?.toIntOrNull()

    /**
     * Thread lines per pid from macOS `ps -M`.
     *
     * The pid is read from the output columns: the second token on a process's first line, whose
     * first token is the user name, and the first token on each further thread line, where the
     * user, terminal and command columns are blank. Measured on macOS 26.6.2 (arm64): a JVM with
     * 29 threads printed one user line and 28 pid-first lines, for both a one-pid and a two-pid
     * `-p` list. The header is skipped, and a blank line or one with no pid in either position is
     * not counted.
     */
    internal fun parseMacPsThreads(output: String): Map<Long, Int> =
        output
            .lines()
            .drop(1) // skip header
            .mapNotNull { line ->
                val tokens = line.trim().split(Regex("\\s+"))
                tokens[0].toLongOrNull() ?: tokens.getOrNull(1)?.toLongOrNull()
            }.groupingBy { it }
            .eachCount()

    private fun procStatusText(pid: Long): String? = runCatching { File("/proc/$pid/status").readText() }.getOrNull()

    /**
     * Both figures from two `ps` runs. `/bin/ps` rather than `ps` resolved through `PATH`, as
     * [ProcessFootprint] does. A pid neither run reports is absent, as on the other platforms.
     */
    internal fun macMetrics(
        pids: List<Long>,
        run: (source: String, command: List<String>) -> String? = ::runPs,
    ): Map<Long, OsProcessMetrics> {
        val pidList = pids.joinToString(",")
        val rss = ProcessFootprint.parsePsRssOutput(run("ps -o", listOf("/bin/ps", "-o", "pid=,rss=", "-p", pidList)))
        val threads = run("ps -M", listOf("/bin/ps", "-M", "-p", pidList))?.let(::parseMacPsThreads).orEmpty()
        return pids
            .mapNotNull { pid ->
                val metrics = OsProcessMetrics(rss[pid], threads[pid])
                if (metrics.rssBytes == null && metrics.threadCount == null) null else pid to metrics
            }.toMap()
    }

    private fun runPs(
        kind: String,
        command: List<String>,
    ): String? {
        val output = BoundedCommand.run(command, PS_TIMEOUT_MILLIS)
        if (output.isSuccess) failures.succeeded(kind) else failures.failed(kind, output.exceptionOrNull())
        return output.getOrNull()
    }

    private fun windowsMetrics(pids: List<Long>): Map<Long, OsProcessMetrics> {
        val threads =
            runCatching { windowsThreadCounts(pids.toSet()) }
                .onSuccess { failures.succeeded("Windows thread snapshot") }
                .onFailure { failures.failed("Windows thread snapshot", it) }
                .getOrDefault(emptyMap())
        var memoryFailure: Throwable? = null
        val metrics =
            pids
                .mapNotNull { pid ->
                    val rss = runCatching { windowsWorkingSetBytes(pid) }.onFailure { memoryFailure = it }.getOrNull()
                    val threadCount = threads[pid]
                    if (rss == null && threadCount == null) null else pid to OsProcessMetrics(rss, threadCount)
                }.toMap()
        // One outcome per sample, so a sample mixing a failed and a readable pid cannot log each time.
        memoryFailure?.let { failures.failed("Windows working set", it) } ?: failures.succeeded("Windows working set")
        return metrics
    }

    /** `cntThreads` for the wanted pids, from a single snapshot of the process table. */
    private fun windowsThreadCounts(pids: Set<Long>): Map<Long, Int> {
        val kernel32 = Kernel32.INSTANCE
        val snapshot = kernel32.CreateToolhelp32Snapshot(Tlhelp32.TH32CS_SNAPPROCESS, WinDef.DWORD(0))
        if (snapshot == null || snapshot == WinBase.INVALID_HANDLE_VALUE) return emptyMap()
        try {
            val counts = HashMap<Long, Int>()
            val entry = Tlhelp32.PROCESSENTRY32.ByReference()
            var more = kernel32.Process32First(snapshot, entry)
            while (more) {
                val pid = entry.th32ProcessID.toLong()
                if (pid in pids) counts[pid] = entry.cntThreads.toInt()
                more = kernel32.Process32Next(snapshot, entry)
            }
            return counts
        } finally {
            kernel32.CloseHandle(snapshot)
        }
    }

    private fun windowsWorkingSetBytes(pid: Long): Long? {
        val kernel32 = Kernel32.INSTANCE
        val handle =
            kernel32.OpenProcess(WinNT.PROCESS_QUERY_LIMITED_INFORMATION or PROCESS_VM_READ, false, pid.toInt())
                ?: return null
        try {
            val counters = ProcessMemoryCounters()
            counters.cb = counters.size()
            return if (Psapi.INSTANCE.GetProcessMemoryInfo(handle, counters, counters.cb)) {
                counters.workingSetSize.toLong()
            } else {
                null
            }
        } finally {
            kernel32.CloseHandle(handle)
        }
    }

    /** `GetProcessMemoryInfo`, which jna-platform's own `Psapi` mapping does not include. */
    internal interface Psapi : StdCallLibrary {
        @Suppress("ktlint:standard:function-naming", "FunctionNaming") // JNA maps by native symbol name
        fun GetProcessMemoryInfo(
            process: WinNT.HANDLE,
            counters: ProcessMemoryCounters,
            cb: Int,
        ): Boolean

        companion object {
            val INSTANCE: Psapi by lazy { Native.load("psapi", Psapi::class.java, W32APIOptions.DEFAULT_OPTIONS) }
        }
    }

    /** `PROCESS_MEMORY_COUNTERS`. JNA lays fields out in [Structure.FieldOrder], not by name. */
    @Structure.FieldOrder(
        "cb",
        "pageFaultCount",
        "peakWorkingSetSize",
        "workingSetSize",
        "quotaPeakPagedPoolUsage",
        "quotaPagedPoolUsage",
        "quotaPeakNonPagedPoolUsage",
        "quotaNonPagedPoolUsage",
        "pagefileUsage",
        "peakPagefileUsage",
    )
    internal class ProcessMemoryCounters : Structure() {
        @JvmField var cb: Int = 0

        @JvmField var pageFaultCount: Int = 0

        @JvmField var peakWorkingSetSize: BaseTSD.SIZE_T = BaseTSD.SIZE_T()

        @JvmField var workingSetSize: BaseTSD.SIZE_T = BaseTSD.SIZE_T()

        @JvmField var quotaPeakPagedPoolUsage: BaseTSD.SIZE_T = BaseTSD.SIZE_T()

        @JvmField var quotaPagedPoolUsage: BaseTSD.SIZE_T = BaseTSD.SIZE_T()

        @JvmField var quotaPeakNonPagedPoolUsage: BaseTSD.SIZE_T = BaseTSD.SIZE_T()

        @JvmField var quotaNonPagedPoolUsage: BaseTSD.SIZE_T = BaseTSD.SIZE_T()

        @JvmField var pagefileUsage: BaseTSD.SIZE_T = BaseTSD.SIZE_T()

        @JvmField var peakPagefileUsage: BaseTSD.SIZE_T = BaseTSD.SIZE_T()
    }
}
