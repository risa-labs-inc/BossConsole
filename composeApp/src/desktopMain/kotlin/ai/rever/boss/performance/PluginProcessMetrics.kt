package ai.rever.boss.performance

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
 * OS-level metrics for the Performance panel's out-of-process plugin rows.
 *
 * Each platform reads these differently, and none of them can use one `ps` invocation. `ps -M` is
 * the macOS thread listing, but on Linux procps `-M` adds a security-label column and prints one
 * line per *process*, so every plugin reported exactly 1 thread. Windows has no `ps` at all: the
 * spawn fails, or Git for Windows' MSYS `ps` rejects `-o` and `-M` with `unknown option`, and every
 * plugin reported 0 bytes and 0 threads.
 *
 *  - **Linux**: `VmRSS` and `Threads` from `/proc/<pid>/status`. A file read, no subprocess.
 *  - **macOS**: `ps -o pid=,rss=` and `ps -M`, the same commands and counting rule as before.
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
     * macOS `ps -M` ignores `-o` formatting, so the pid is read from the output columns: the first
     * token on a thread line, or the second when the first is the user name. The same rule as the
     * loop this replaces in `PerformanceDataProviderImpl`: the header is skipped, and a blank line or
     * one with no pid in either position is not counted.
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

    private fun macMetrics(pids: List<Long>): Map<Long, OsProcessMetrics> {
        val pidStr = pids.joinToString(",")
        val rss = runPs("ps", "-o", "pid=,rss=", "-p", pidStr)?.let { ProcessFootprint.parsePsRssOutput(it) }.orEmpty()
        val threads = runPs("ps", "-M", "-p", pidStr)?.let { parseMacPsThreads(it) }.orEmpty()
        return pids.associateWith { OsProcessMetrics(rss[it], threads[it]) }
    }

    /**
     * A `ps` run's output, or null when it could not be started.
     *
     * Drains before waiting. `ps -M` prints a line per thread, so its output is not bounded the way
     * [ProcessFootprint]'s one-line-per-pid queries are, and waiting first could deadlock on a full
     * pipe.
     */
    private fun runPs(vararg command: String): String? =
        runCatching {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output
        }.getOrNull()

    private fun windowsMetrics(pids: List<Long>): Map<Long, OsProcessMetrics> {
        val threads = runCatching { windowsThreadCounts(pids.toSet()) }.getOrDefault(emptyMap())
        return pids
            .mapNotNull { pid ->
                val rss = runCatching { windowsWorkingSetBytes(pid) }.getOrNull()
                val threadCount = threads[pid]
                if (rss == null && threadCount == null) null else pid to OsProcessMetrics(rss, threadCount)
            }.toMap()
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
