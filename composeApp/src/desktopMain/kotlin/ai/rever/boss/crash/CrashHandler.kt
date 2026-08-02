package ai.rever.boss.crash

import ai.rever.boss.plugin.loader.PluginClassLoader
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.AppVersion
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.LogSanitizer
import androidx.compose.ui.awt.ComposePanel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.Dimension
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * Global uncaught exception handler for BOSS.
 *
 * Captures unhandled exceptions and creates crash reports that can be
 * submitted to GitHub Issues. The crash dialog is shown via Compose UI
 * by observing [pendingCrashReport].
 *
 * ## Usage
 * ```kotlin
 * // In main.kt, after BossLogger.initialize()
 * CrashHandler.install()
 * ```
 *
 * ## Behavior
 * - Captures uncaught exceptions on any thread
 * - Collects system information (memory, OS, Java version)
 * - Sanitizes sensitive data from stack traces
 * - Exposes pending crash report via StateFlow for UI
 * - Chains to original handler for proper JVM termination
 */
object CrashHandler {
    private val logger = BossLogger.forComponent("CrashHandler")

    /** Where contained (non-fatal, recovered) reports are written, under the BOSS data dir. */
    private const val CONTAINED_REPORT_DIR = "crash-reports"

    /** Contained reports kept on disk; older ones are swept after each write. */
    private const val CONTAINED_REPORT_RETENTION = 20

    /**
     * Ceiling on [containedSignatures]. Past this the dedupe is dropped and starts
     * over rather than growing without bound: a session that has produced this many
     * *distinct* render faults has bigger problems than a duplicate report, and the
     * retention sweep bounds the disk cost either way.
     */
    private const val MAX_CONTAINED_SIGNATURES = 200

    /**
     * Signatures already written this session.
     *
     * A corrupt scene throws every frame, so without this the render-fault path
     * would write ~60 files a second of full stack traces until the user noticed.
     * One file per distinct fault is all the diagnostic value there is; repeats
     * only bump a log counter.
     */
    private val containedSignatures = ConcurrentHashMap<String, Int>()

    /**
     * Report directory override for tests; the same shape as
     * `SingleInstanceFiles.runtimeDirOverride`.
     *
     * Not a convenience: [sweepOldReports] deletes files, so a test running against
     * the real data root would destroy the user's actual crash reports.
     */
    @Volatile
    internal var containedReportDirOverride: File? = null

    private fun containedReportDir(): File = containedReportDirOverride ?: BossDirectories.resolve(CONTAINED_REPORT_DIR)

    /** Lets a test start from a clean dedupe. */
    internal fun resetContainedStateForTest() = containedSignatures.clear()

    /**
     * Single thread for writing contained reports.
     *
     * The render-fault path runs on the AWT event thread, in the middle of a
     * repaint storm; a synchronous writeText there stalls the UI on a slow or
     * full disk. Daemon so it never holds shutdown open.
     */
    private val containedWriter =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "boss-contained-crash-writer").apply { isDaemon = true }
        }

    private val _pendingCrashReport = MutableStateFlow<CrashReport?>(null)

    /**
     * Pending crash report to display in the UI.
     * Observe this in BossApp to show the crash dialog.
     */
    val pendingCrashReport: StateFlow<CrashReport?> = _pendingCrashReport.asStateFlow()

    private var originalHandler: Thread.UncaughtExceptionHandler? = null
    private var isInstalled = false

    /**
     * Install the global crash handler.
     * Safe to call multiple times - only installs once.
     */
    fun install() {
        if (isInstalled) return

        // Store original handler to chain to it later
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleCrash(thread, throwable)
        }

        isInstalled = true
        logger.debug(LogCategory.SYSTEM, "Crash handler installed")
    }

    /**
     * Whether [throwable] (or anything in its cause chain) is a benign, expected
     * exception that should be logged and swallowed rather than reported as a
     * crash: dropped network sockets (broken pipe / connection reset), closed
     * ktor channels, coroutine cancellations, and Supabase session-refresh
     * failures (supabase-kt throws TokenExpiredException into its own internal
     * coroutines when an authenticated request races an expired session — the
     * auth layer recovers on its own, see CoreAuthService.startSessionRecovery).
     * Matched by class-name suffix + message so we don't need a compile
     * dependency on ktor/coroutines here.
     */
    internal fun isIgnorable(throwable: Throwable): Boolean {
        var t: Throwable? = throwable
        var depth = 0
        while (t != null && depth < 12) {
            val name = t.javaClass.name
            val msg = t.message ?: ""
            val benign =
                name.endsWith("ClosedWriteChannelException") ||
                    name.endsWith("ClosedReceiveChannelException") ||
                    name.endsWith("ClosedChannelException") ||
                    name.endsWith("CancellationException") ||
                    name == "io.github.jan.supabase.auth.exception.TokenExpiredException" ||
                    (
                        t is java.io.IOException && (
                            msg.contains("Broken pipe", ignoreCase = true) ||
                                msg.contains("Connection reset", ignoreCase = true) ||
                                msg.contains("Socket closed", ignoreCase = true) ||
                                msg.contains("Stream closed", ignoreCase = true) ||
                                msg.contains("Connection refused", ignoreCase = true)
                        )
                    )
            if (benign) return true
            t = t.cause
            depth++
        }
        return false
    }

    /**
     * Record a crash report for something already contained and recovered from.
     *
     * The window exception handler cannot use [handleCrash]: that shows a dialog
     * whose every exit terminates — dismiss, submit and Escape all reach
     * [terminateAfterCrash], and clean-and-restart deletes the data directory
     * first — so a recovered fault would end the session on Escape.
     *
     * It must not skip reporting either. That handler sees *all* unattributed
     * Compose exceptions, so a host-side layout bug would otherwise produce a log
     * line and a toast where it used to produce a full report.
     *
     * The report is written to disk. It is deliberately **not** put in
     * [pendingCrashReport]: that slot belongs to the interactive dialog, is
     * single-valued, and nothing else reads it. Writing there achieved nothing —
     * no consumer persists or uploads it — and could actively corrupt a
     * submission in progress: a fatal crash on a background thread opens the
     * dialog while the EDT keeps running, so a contained fault landing mid-typing
     * would replace the report and Submit would send the wrong crash, losing the
     * fatal one.
     */
    fun recordContained(throwable: Throwable) {
        if (isIgnorable(throwable)) return
        try {
            // Signature first, report second. createCrashReport sanitizes the whole
            // stack with a regex sweep, walks up to twelve causes asking the plugin
            // classloaders about every frame, and reads two JMX beans — all on the
            // AWT event thread, and previously once per fault during exactly the
            // repaint storm this exists to survive. The signature is the cheap part
            // and the only thing the dedupe needs.
            val signature = CrashSignature.generate(throwable)
            if (containedSignatures.size >= MAX_CONTAINED_SIGNATURES) containedSignatures.clear()
            val seen = containedSignatures.merge(signature, 1, Int::plus) ?: 1
            if (seen > 1) {
                // Log on a curve, not every frame. The file dedupe protects the
                // disk; this protects the log buffers, which back recentLogs on the
                // next real crash report — a per-frame warn would evict the very
                // context that explains the fault.
                if (seen == 2 || seen == 10 || seen % 100 == 0) {
                    logger.warn(
                        LogCategory.SYSTEM,
                        "Contained render fault recurring",
                        mapOf(
                            "signature" to signature,
                            "errorType" to throwable.javaClass.simpleName,
                            "occurrences" to seen.toString(),
                        ),
                    )
                }
                return
            }
            // Building the report is part of the off-thread work now.
            containedWriter.execute { writeContainedReport(signature, throwable) }
        } catch (e: Exception) {
            // Reporting a contained fault must never itself become a fault.
            logger.warn(
                LogCategory.SYSTEM,
                "Failed to record a contained crash report",
                mapOf("errorType" to throwable.javaClass.simpleName),
                e,
            )
        }
    }

    /** Off the EDT — see [containedWriter]. */
    private fun writeContainedReport(
        signature: String,
        throwable: Throwable,
    ) {
        try {
            val report = createCrashReport(throwable)
            val dir = containedReportDir()
            dir.mkdirs()
            val file = File(dir, "contained-${report.timestamp}-$signature.txt")
            // Owner-only on both, and on the file too — writeText alone leaves it at
            // the umask, typically 0644, holding sanitized traces and host details.
            // Directory perms are not enough: 0711 still lets others traverse to a
            // known path.
            restrictToOwner(dir, directory = true)
            file.writeText(renderContainedReport(report))
            restrictToOwner(file, directory = false)
            sweepOldReports(dir)
            logger.warn(
                LogCategory.SYSTEM,
                "Contained render fault recorded",
                mapOf("signature" to signature, "path" to file.absolutePath),
            )
        } catch (e: Exception) {
            // The signature was claimed before this ran, so a failed write would
            // otherwise suppress the fault for the rest of the session. Release it
            // so the next occurrence can retry.
            containedSignatures.remove(signature)
            logger.warn(
                LogCategory.SYSTEM,
                "Failed to write a contained crash report",
                mapOf("signature" to signature),
                e,
            )
        }
    }

    /**
     * Keep the newest [CONTAINED_REPORT_RETENTION] reports and delete the rest.
     *
     * The session dedupe bounds writes *within* one run; nothing bounded them
     * across runs, and this directory has no other janitor. Sorted by name rather
     * than mtime: the filename carries the report timestamp, so it survives a copy
     * that resets modification times, and one distinct-signature write per sweep
     * makes the cost trivial.
     *
     * Best effort — a failed sweep must not fail the write that triggered it.
     */
    private fun sweepOldReports(dir: File) {
        runCatching {
            val reports =
                dir
                    .listFiles { f -> f.isFile && f.name.startsWith("contained-") && f.name.endsWith(".txt") }
                    ?.sortedByDescending { it.name }
                    ?: return
            reports.drop(CONTAINED_REPORT_RETENTION).forEach { it.delete() }
        }
    }

    /** POSIX first, File flags as fallback — the same shape SingleInstanceFiles uses. */
    private fun restrictToOwner(
        target: File,
        directory: Boolean,
    ) {
        val posix =
            runCatching {
                val perms =
                    if (directory) {
                        java.nio.file.attribute.PosixFilePermissions
                            .fromString("rwx------")
                    } else {
                        java.nio.file.attribute.PosixFilePermissions
                            .fromString("rw-------")
                    }
                java.nio.file.Files
                    .setPosixFilePermissions(target.toPath(), perms)
            }
        if (posix.isSuccess) return
        // Non-POSIX filesystem: best effort, and note the execute bit matters for a
        // directory or others can still traverse into it.
        runCatching {
            target.setReadable(false, false)
            target.setReadable(true, true)
            target.setWritable(false, false)
            target.setWritable(true, true)
            if (directory) {
                target.setExecutable(false, false)
                target.setExecutable(true, true)
            }
        }
    }

    /** Plain text, so the file is useful without any tooling to read it. */
    private fun renderContainedReport(report: CrashReport): String =
        buildString {
            appendLine("BOSS contained render fault")
            appendLine("signature:  ${report.signature}")
            appendLine("timestamp:  ${report.timestamp}")
            appendLine("plugin:     ${report.pluginId ?: "(unattributed)"}")
            appendLine("type:       ${report.exceptionType}")
            appendLine("message:    ${report.exceptionMessage}")
            appendLine("app:        ${report.appInfo}")
            appendLine("system:     ${report.systemInfo}")
            appendLine()
            appendLine("This fault was contained and recovered from; the app kept running.")
            appendLine()
            appendLine(report.stackTrace)
        }

    /**
     * Handle an uncaught exception.
     *
     * Terminal by design, even though this function does not call
     * [terminateAfterCrash] itself: every exit from the dialog it shows does —
     * dismiss, submit, and Escape all end the process, and clean-and-restart
     * deletes the data directory first. Anything that has already been contained
     * and recovered from must go to [recordContained] instead.
     */
    private fun handleCrash(
        thread: Thread,
        throwable: Throwable,
    ) {
        // Benign, non-fatal exceptions (dropped sockets, cancellations) reach the
        // global handler routinely — e.g. hot-swapping a plugin jar drops the MCP
        // server's ktor writer with a "Broken pipe". These must NOT pop the crash
        // dialog or terminate the app; log and swallow so the app keeps running.
        if (isIgnorable(throwable)) {
            logger.warn(
                LogCategory.SYSTEM,
                "Ignoring benign uncaught exception on thread ${thread.name}: " +
                    "${throwable.javaClass.simpleName}: ${throwable.message}",
            )
            return
        }
        try {
            logger.error(
                LogCategory.SYSTEM,
                "Uncaught exception on thread ${thread.name}",
                error = throwable,
            )

            // Create crash report
            val report = createCrashReport(throwable)
            _pendingCrashReport.value = report

            // Show dialog in a separate window (works even if main UI is broken)
            // If already on EDT, show directly; otherwise use invokeAndWait to ensure
            // the dialog is shown before the app can exit
            if (SwingUtilities.isEventDispatchThread()) {
                showCrashDialogWindow(report)
            } else {
                SwingUtilities.invokeAndWait {
                    showCrashDialogWindow(report)
                }
            }
        } catch (e: Exception) {
            // If crash handling itself fails, log to stderr and chain
            System.err.println("CrashHandler failed: ${e.message}")
            e.printStackTrace()
            // Still try to exit cleanly
            System.exit(1)
        }
    }

    /**
     * Show the crash dialog in a separate AWT/Swing window.
     * This window is independent of the main Compose UI, so it will display
     * even when the main UI thread has crashed.
     */
    private fun showCrashDialogWindow(report: CrashReport) {
        try {
            val frame = JFrame("BOSS - Crash Report")
            frame.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
            frame.preferredSize = Dimension(550, 700)
            frame.minimumSize = Dimension(450, 500)

            val composePanel = ComposePanel()
            composePanel.setContent {
                CrashReportDialog(
                    crashReport = report,
                    onDismiss = {
                        logger.info(LogCategory.SYSTEM, "User dismissed crash report without submitting")
                        frame.dispose()
                        terminateAfterCrash()
                    },
                    onSubmit = { userNotes, includeLogs ->
                        logger.info(
                            LogCategory.SYSTEM,
                            "Crash report submitted",
                            mapOf(
                                "hasNotes" to (userNotes != null),
                                "includedLogs" to includeLogs,
                            ),
                        )
                        frame.dispose()
                        terminateAfterCrash()
                    },
                    onCleanAndRestart = {
                        logger.info(LogCategory.SYSTEM, "User requested clean data and restart")
                        frame.dispose()
                        cleanDataAndRestart()
                    },
                )
            }

            frame.contentPane.add(composePanel)
            frame.pack()
            frame.setLocationRelativeTo(null) // Center on screen
            frame.isVisible = true

            // Bring to front
            frame.toFront()
            frame.requestFocus()
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Failed to show crash dialog window", error = e)
            System.err.println("Failed to show crash dialog: ${e.message}")
            e.printStackTrace()
            // If we can't show the dialog, just exit
            terminateAfterCrash()
        }
    }

    /**
     * Create a crash report from an exception.
     */
    private fun createCrashReport(throwable: Throwable): CrashReport {
        val signature = CrashSignature.generate(throwable)
        val stackTrace = getStackTraceString(throwable)
        val sanitizedStackTrace = LogSanitizer.sanitizeStackTrace(stackTrace)
        val sanitizedMessage = LogSanitizer.sanitizeExceptionMessage(throwable.message)

        return CrashReport(
            signature = signature,
            exceptionType = throwable.javaClass.simpleName,
            exceptionMessage = sanitizedMessage,
            stackTrace = sanitizedStackTrace,
            systemInfo = collectSystemInfo(),
            appInfo = collectAppInfo(),
            timestamp = System.currentTimeMillis(),
            pluginId = attributePluginId(throwable),
        )
    }

    /**
     * Attribute a crash to a dynamically loaded plugin: the first stack frame
     * (root cause first, so the crash origin wins over wrapping layers) whose
     * class was defined by a [PluginClassLoader] names the culprit. Host
     * crashes return null. Best-effort — attribution must never make crash
     * handling itself fail.
     */
    internal fun attributePluginId(throwable: Throwable): String? {
        return try {
            val chain = mutableListOf<Throwable>()
            var t: Throwable? = throwable
            while (t != null && chain.size < 12 && t !in chain) {
                chain.add(t)
                t = t.cause
            }
            for (cause in chain.asReversed()) {
                (cause.javaClass.classLoader as? PluginClassLoader)?.let { return it.pluginId }
                for (frame in cause.stackTrace) {
                    PluginClassLoader.findPluginForClass(frame.className)?.let { return it }
                }
            }
            null
        } catch (e: Throwable) {
            logger.warn(LogCategory.SYSTEM, "Plugin attribution failed: ${e.message}")
            null
        }
    }

    /**
     * Get the full stack trace as a string.
     */
    private fun getStackTraceString(throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    /**
     * Collect system information using JMX.
     */
    private fun collectSystemInfo(): SystemInfo {
        val memoryMXBean = ManagementFactory.getMemoryMXBean()
        val heapUsage = memoryMXBean.heapMemoryUsage
        val nonHeapUsage = memoryMXBean.nonHeapMemoryUsage
        val osMXBean = ManagementFactory.getOperatingSystemMXBean()

        return SystemInfo(
            osName = System.getProperty("os.name", "Unknown"),
            osVersion = System.getProperty("os.version", "Unknown"),
            osArch = System.getProperty("os.arch", "Unknown"),
            javaVersion = System.getProperty("java.version", "Unknown"),
            javaVendor = System.getProperty("java.vendor", "Unknown"),
            heapUsedMB = heapUsage.used / (1024 * 1024),
            heapMaxMB = if (heapUsage.max > 0) heapUsage.max / (1024 * 1024) else -1,
            nonHeapUsedMB = nonHeapUsage.used / (1024 * 1024),
            availableProcessors = osMXBean.availableProcessors,
        )
    }

    /**
     * Collect application information.
     */
    private fun collectAppInfo(): AppInfo {
        val platform =
            when {
                System.getProperty("os.name").contains("Mac", ignoreCase = true) -> "macOS"
                System.getProperty("os.name").contains("Windows", ignoreCase = true) -> "Windows"
                else -> "Linux"
            }

        val isDebug =
            System.getProperty("boss.dev.mode")?.toBoolean() == true ||
                System.getenv("BOSS_DEV_MODE")?.toBoolean() == true

        return AppInfo(
            version = AppVersion.CURRENT.toString(),
            platform = platform,
            isDebug = isDebug,
        )
    }

    /**
     * Clear the pending crash report.
     * Called after user dismisses or submits the crash dialog.
     */
    fun clearPendingReport() {
        _pendingCrashReport.value = null
    }

    /**
     * Get recent logs for inclusion in crash report (with user consent).
     *
     * @param limit Maximum number of log entries to include
     * @return List of sanitized log entries
     */
    fun getRecentLogsForReport(limit: Int = 50): List<SanitizedLogEntry> =
        BossLogger
            .getRecentLogs(limit = limit)
            .map { SanitizedLogEntry.fromLogEntry(it) }

    /**
     * Update the pending crash report with user notes and optional logs.
     */
    fun updateReportWithUserInput(
        userNotes: String?,
        includeLogs: Boolean,
    ): CrashReport? {
        val currentReport = _pendingCrashReport.value ?: return null

        val updatedReport =
            currentReport.copy(
                userNotes = userNotes?.takeIf { it.isNotBlank() },
                recentLogs = if (includeLogs) getRecentLogsForReport() else null,
            )

        _pendingCrashReport.value = updatedReport
        return updatedReport
    }

    /**
     * Terminate the JVM after crash handling is complete.
     */
    fun terminateAfterCrash() {
        clearPendingReport()
        logger.info(LogCategory.SYSTEM, "Terminating application after crash")
        System.exit(1)
    }

    /**
     * Delete the BOSS data directory and restart the application.
     *
     * This gives users a clean slate when corrupted plugins or cached data
     * cause persistent crashes (e.g., after a BOSS version upgrade with
     * incompatible plugins).
     */
    private fun cleanDataAndRestart() {
        try {
            val dataDir = BossDirectories.rootDir
            logger.info(
                LogCategory.SYSTEM,
                "Cleaning BOSS data directory",
                mapOf(
                    "path" to dataDir.absolutePath,
                ),
            )

            // Delete everything in the data dir
            if (dataDir.exists()) {
                dataDir.deleteRecursively()
                logger.info(LogCategory.SYSTEM, "Deleted BOSS data directory")
            }

            // Restart the app by launching a new process
            val javaBin =
                ProcessHandle
                    .current()
                    .info()
                    .command()
                    .orElse(null)
            if (javaBin != null) {
                logger.info(LogCategory.SYSTEM, "Restarting application")
                ProcessBuilder(javaBin, *getRestartArgs())
                    .inheritIO()
                    .start()
            } else {
                logger.warn(LogCategory.SYSTEM, "Cannot determine Java binary for restart")
            }
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Failed to clean data and restart", error = e)
        }

        clearPendingReport()
        System.exit(0)
    }

    /**
     * Get the command-line arguments for restarting the application.
     */
    private fun getRestartArgs(): Array<String> {
        val args = mutableListOf<String>()

        // Pass through system properties that were set
        val relevantProps = listOf("boss.dev.mode", "boss.log.level")
        for (prop in relevantProps) {
            System.getProperty(prop)?.let { value ->
                args.add("-D$prop=$value")
            }
        }

        // Add the JAR or classpath
        val sunCommand = System.getProperty("sun.java.command")
        if (sunCommand != null) {
            args.addAll(sunCommand.split(" "))
        }

        return args.toTypedArray()
    }

    /**
     * Trigger a test crash for debugging/testing the crash reporter.
     */
    fun triggerTestCrash() {
        logger.info(LogCategory.SYSTEM, "Triggering test crash for crash reporter verification")
        throw RuntimeException("Test crash triggered via CrashHandler.triggerTestCrash()")
    }
}
