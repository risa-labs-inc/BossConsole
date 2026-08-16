package ai.rever.boss.crash

import ai.rever.boss.plugin.loader.PluginClassLoader
import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.plugin.sandbox.PluginExecutionBoundary
import ai.rever.boss.plugin.sandbox.ui.PluginRecoveryQuarantine
import ai.rever.boss.utils.AppVersion
import ai.rever.boss.utils.atomicMoveFrom
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.LogSanitizer
import ai.rever.boss.window.BossWindowIcon
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
import java.util.concurrent.atomic.AtomicBoolean
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
 * - Recovers from a crash attributable to a dynamic plugin by disabling it,
 *   and terminates only for a fatal host crash (see [CrashDisposition])
 */
object CrashHandler {
    private val logger = BossLogger.forComponent("CrashHandler")

    /**
     * Preferred size of the space [CrashReportDialog] is laid out in — the **content pane**, in
     * AWT user-space units. Applied to the `ComposePanel`, so `pack()` derives the frame from it
     * and the dialog gets these dimensions rather than these dimensions minus a title bar.
     *
     * Deliberately *not* used to derive the frame minimum below. Doing that needs decoration
     * insets, which are not reliably known until the window manager has reparented the window —
     * and reading them at any point in this method is a race on X11. Rather than chase that with
     * deferred re-packs inside the one code path that runs when the app is already broken, the
     * minimum stays in frame terms: a content pane a title bar short of [FRAME_MIN_HEIGHT] is
     * something this dialog now handles by scrolling, which is the entire point of the layout.
     */
    internal const val CONTENT_PREFERRED_WIDTH = 550

    /** Height companion to [CONTENT_PREFERRED_WIDTH]. */
    internal const val CONTENT_PREFERRED_HEIGHT = 700

    /**
     * Smallest the crash **window** may be resized to, decorations included. The content pane is
     * therefore this minus the decorations; `CrashReportDialogLayoutTest` derives a deliberately
     * conservative content box from these rather than assuming the two are equal.
     */
    internal const val FRAME_MIN_WIDTH = 450

    /** Height companion to [FRAME_MIN_WIDTH]. */
    internal const val FRAME_MIN_HEIGHT = 500

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

    /** Pauses a test after the temp file is complete but before it is published. */
    @Volatile
    internal var beforeContainedReportPublishForTest: ((temp: File, target: File) -> Unit)? = null

    private fun containedReportDir(): File = containedReportDirOverride ?: BossDirectories.resolve(CONTAINED_REPORT_DIR)

    /** Lets a test start from a clean dedupe. */
    internal fun resetContainedStateForTest() {
        containedSignatures.clear()
        beforeContainedReportPublishForTest = null
    }

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
     * The crash currently being reported, for the submit path to attach notes to.
     *
     * Single-valued and NOT a UI trigger: the dialog is shown directly, in its own
     * JFrame, by [showCrashDialogWindow] - nothing observes this to decide whether
     * to render. That matters because [recordContained] deliberately does not write
     * here: a contained fault landing mid-typing would replace the report and
     * Submit would send the wrong crash.
     */
    val pendingCrashReport: StateFlow<CrashReport?> = _pendingCrashReport.asStateFlow()

    private var isInstalled = false

    /**
     * How the process is ended. Injectable because everything interesting about
     * this class is *whether* it terminates, and a test that answers that by
     * actually calling [System.exit] takes the suite with it.
     */
    @Volatile
    internal var processExit: (Int) -> Unit = { code -> System.exit(code) }

    /**
     * True while a crash window is on screen.
     *
     * Crashes repeat. A plugin that throws from a paint or a timer produces one
     * per frame, and now that dismissing is survivable the app no longer dies
     * after the first — without this, the second crash opens a second dialog on
     * top of the first and the user cannot reach either. Repeats are recorded to
     * disk through [recordContained] (deduped by signature) instead.
     */
    private val dialogVisible = AtomicBoolean(false)

    /**
     * Give the dialog slot back, once the crash window is gone.
     *
     * Called from [CrashDialogController.finish] on every exit rather than from the
     * one branch of [resolveCrash] that returns - the slot describes the window,
     * and a route that returned without exiting would otherwise silence every later
     * crash dialog for the life of the process.
     */
    internal fun releaseDialogSlot() {
        dialogVisible.set(false)
    }

    /**
     * Install the global crash handler.
     * Safe to call multiple times - only installs once.
     */
    fun install() {
        if (isInstalled) return

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
    internal fun isIgnorable(throwable: Throwable): Boolean =
        throwable.chainOfCauses().any { t ->
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
            benign
        }

    /**
     * Record a crash report for something already contained and recovered from.
     *
     * The window exception handler cannot use [handleCrash]: that shows a dialog,
     * and a fault the render path has already contained and recovered from must
     * not interrupt the user to ask about it. (Every exit from that dialog used to
     * terminate as well; a plugin-attributed crash now recovers instead - see
     * [CrashDisposition] - but a *contained* fault should still never reach it.)
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
    fun recordContained(
        throwable: Throwable,
        writeInline: Boolean = false,
    ) {
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
            //
            // The directory is resolved *here*, not on the writer thread: a test that
            // sets containedReportDirOverride clears it as soon as its body returns,
            // so a task that had not drained yet would resolve the real
            // ~/.boss/crash-reports and sweepOldReports would delete the developer's
            // actual reports — the exact hazard the override exists to prevent.
            val dir = containedReportDir()
            // Inline when the caller is about to end the process. The writer is a
            // daemon thread with nothing draining it at shutdown, so a queued task
            // is dropped or killed mid-write by the exit that follows - and the one
            // caller that passes true does so precisely because the record is the
            // justification for that branch existing.
            if (writeInline) {
                writeContainedReport(dir, signature, throwable)
            } else {
                containedWriter.execute { writeContainedReport(dir, signature, throwable) }
            }
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

    /** Off the EDT — see [containedWriter]. [dir] is resolved by the caller. */
    private fun writeContainedReport(
        dir: File,
        signature: String,
        throwable: Throwable,
    ) {
        try {
            val report = createCrashReport(throwable)
            // Owner-only on the directory *and* the file. Directory perms alone are
            // not enough — 0711 still lets others traverse to a predictable path.
            makeOwnerOnlyDir(dir)
            val file = File(dir, "contained-${report.timestamp}-$signature.txt")
            writeOwnerOnly(file, renderContainedReport(report))
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
     * across runs, and this directory has no other janitor.
     *
     * Ordered on the timestamp *parsed* out of the name rather than on the name
     * itself. Plain name order looks equivalent but is not: the name is
     * `contained-<millis>-<signature>.txt`, so when several distinct signatures land
     * in the same millisecond the tiebreak becomes the signature hash and the sweep
     * can keep older reports while deleting newer ones. Timestamps are preferred
     * over `lastModified` because they survive a copy that resets mtimes;
     * `lastModified` is only the tiebreak. Anything unparseable sorts oldest so a
     * stray file cannot outlive real reports.
     *
     * Best effort — a failed sweep must not fail the write that triggered it.
     */
    private fun sweepOldReports(dir: File) {
        runCatching {
            val reports =
                dir
                    .listFiles { f -> f.isFile && f.name.startsWith("contained-") && f.name.endsWith(".txt") }
                    ?: return
            reports
                .sortedWith(compareByDescending<File> { reportTimestamp(it) }.thenByDescending { it.lastModified() })
                .drop(CONTAINED_REPORT_RETENTION)
                .forEach { it.delete() }
        }
    }

    /** The millis embedded in `contained-<millis>-<signature>.txt`, or 0 if absent. */
    private fun reportTimestamp(file: File): Long =
        file.name
            .removePrefix("contained-")
            .substringBefore('-')
            .toLongOrNull() ?: 0L

    /** Write [text] owner-only, then atomically publish it at [file]. */
    private fun writeOwnerOnly(
        file: File,
        text: String,
    ) {
        val temp = createOwnerOnlyTempFile(file)
        try {
            temp.writeText(text)
            beforeContainedReportPublishForTest?.invoke(temp, file)
            file.atomicMoveFrom(temp)
        } finally {
            // No-op after a successful move; removes a partial temp file on failure.
            temp.delete()
        }
    }

    /** Create a unique owner-only temp file beside [target], so its move can be atomic. */
    private fun createOwnerOnlyTempFile(target: File): File {
        val createdWithPerms =
            runCatching {
                java.nio.file.Files
                    .createTempFile(
                        target.parentFile.toPath(),
                        ".${target.name}.",
                        ".tmp",
                        posixAttribute("rw-------"),
                    ).toFile()
            }.getOrNull()
        if (createdWithPerms != null) return createdWithPerms

        // Non-POSIX filesystem: restrict the temp before any report content is written.
        return File.createTempFile(".${target.name}.", ".tmp", target.parentFile).also {
            restrictToOwner(it, directory = false)
        }
    }

    private fun posixAttribute(mode: String) =
        java.nio.file.attribute.PosixFilePermissions
            .asFileAttribute(
                java.nio.file.attribute.PosixFilePermissions
                    .fromString(mode),
            )

    /**
     * Create the report directory owner-only from the outset.
     *
     * `mkdirs()` then chmod has the same exposure window as the file did, so the
     * directory is created with its permissions attached where POSIX allows it.
     */
    private fun makeOwnerOnlyDir(dir: File) {
        if (dir.isDirectory) {
            restrictToOwner(dir, directory = true)
            return
        }
        val created =
            runCatching {
                java.nio.file.Files
                    .createDirectories(dir.toPath(), posixAttribute("rwx------"))
            }.isSuccess
        if (!created) {
            dir.mkdirs()
            restrictToOwner(dir, directory = true)
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
     * Not terminal any more, and that is the point. A crash the dialog can
     * attribute to a dynamic plugin ends with that plugin disabled and the app
     * still running (see [CrashDisposition] and [resolveCrash]); only a fatal
     * host crash still ends the process. Anything already contained and recovered
     * from by the render path goes to [recordContained] instead of here.
     */
    // Throwable, not Exception: this function claims the dialog slot and only the
    // controller gives it back, so an Error escaping between the two would leave
    // the slot held for the life of the process - see the catch below.
    @Suppress("TooGenericExceptionCaught")
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

            // Resolved once and threaded through. Attribution's slow path walks
            // twelve causes and asks the plugin classloaders about every frame of
            // each, on the crashing thread; doing it here and again inside
            // createCrashReport made a repeat crash pay for it twice.
            val attributedPluginId = attributePluginId(throwable)
            // Classified BEFORE the suppression checks, because those checks used to
            // run first and could swallow a fatal crash: with a plugin dialog on
            // screen, a host OutOfMemoryError on another thread was written to disk
            // and the process carried on under heap exhaustion with nothing shown.
            // The uncontainable carve-outs exist so that can never be treated as
            // survivable, and this path routed around both.
            val disposition = dispositionFor(throwable, attributedPluginId)

            if (!claimDialogOrRecord(throwable, disposition, attributedPluginId)) return

            // Create crash report
            val report = createCrashReport(throwable, attributedPluginId)
            _pendingCrashReport.value = report

            // Show dialog in a separate window (works even if main UI is broken)
            // If already on EDT, show directly; otherwise use invokeAndWait to ensure
            // the dialog is shown before the app can exit
            if (SwingUtilities.isEventDispatchThread()) {
                showCrashDialogWindow(report, throwable, disposition)
            } else {
                SwingUtilities.invokeAndWait {
                    showCrashDialogWindow(report, throwable, disposition)
                }
            }
            // Throwable, not Exception. This block claims the dialog slot and the
            // only thing that gives it back is the controller; an Error escaping
            // between the two - NoClassDefFoundError or ExceptionInInitializerError
            // out of Compose or Skiko, UnsatisfiedLinkError, an OOM while the stack
            // is sanitised - left the process running with the slot held forever,
            // so every later crash including a fatal one went silently to disk with
            // no dialog and no exit. Before recovery existed the same throw escaped
            // too, but there was no slot to leak and the next crash still prompted.
        } catch (e: Throwable) {
            // If crash handling itself fails, log to stderr and chain
            System.err.println("CrashHandler failed: ${e.message}")
            e.printStackTrace()
            releaseDialogSlot()
            // Still try to exit cleanly
            processExit(1)
        }
    }

    /**
     * Take the dialog slot, or record the crash and explain why we are not prompting.
     *
     * Two reasons not to prompt, both about not asking the user the same question
     * twice - and neither applies to a fatal crash, which must always be acted on:
     *
     * 1. **The plugin is already quarantined.** One recovery disabled does not
     *    necessarily stop; a thread or timer of its own keeps throwing, and each
     *    throw would otherwise open a fresh dialog for something already dealt with.
     * 2. **A dialog is already up.** Stacking a second hides the first and neither
     *    can be reached.
     *
     * Either way the crash still reaches [recordContained], which dedupes by
     * signature, so a fault repeating every frame costs one file and a log line on
     * a curve.
     *
     * @return true when the caller now owns the dialog slot and must release it
     *   (through [CrashDialogController.finish]).
     */
    private fun claimDialogOrRecord(
        throwable: Throwable,
        disposition: CrashDisposition,
        attributedPluginId: String?,
    ): Boolean {
        val fatal = disposition is CrashDisposition.FatalHost
        // A fatal crash is never suppressed by a quarantine: the plugin may be gone,
        // but heap exhaustion is not survivable whoever is blamed for it.
        val quarantined = !fatal && isSuppressedByQuarantine(attributedPluginId)
        val claimed = !quarantined && tryClaimDialogSlot()
        if (!claimed) {
            // Written inline for a fatal crash: terminateAfterCrash follows
            // immediately and would otherwise kill the queued write.
            recordContained(throwable, writeInline = fatal)
            // A fatal crash cannot queue behind another dialog: the exit is the
            // point, not the prompt. (Not reached when `quarantined`, which is
            // false for every fatal crash.)
            if (fatal) {
                logger.error(
                    LogCategory.SYSTEM,
                    "Fatal crash while a crash dialog is open - terminating without a second dialog",
                    mapOf("errorType" to throwable.javaClass.simpleName),
                )
                terminateAfterCrash()
            }
        }
        return claimed
    }

    /**
     * Whether this plugin has already been taken out by crash recovery.
     *
     * A plugin recovery disabled does not necessarily stop - a thread or timer of
     * its own keeps throwing - and each throw would otherwise open a fresh dialog
     * for something the user has already dealt with.
     *
     * `isRecoveryQuarantined`, NOT `hasCrashed`: the latter is also set by the
     * ordinary contained-render-fault path, and gating on it silenced the dialog
     * for any plugin whose panel had ever shown a fallback - still enabled and
     * still running - which is worse than the behaviour this feature replaced.
     */
    internal fun isSuppressedByQuarantine(attributedPluginId: String?): Boolean {
        val quarantined = attributedPluginId?.let { PluginRecoveryQuarantine.isQuarantined(it) } == true
        if (quarantined) {
            logger.warn(
                LogCategory.SYSTEM,
                "Crash from an already-quarantined plugin - recording instead of prompting again",
                mapOf("pluginId" to attributedPluginId.orEmpty()),
            )
        }
        return quarantined
    }

    /**
     * Claim the right to put a crash window on screen, or report that one is up.
     *
     * Named for its effect rather than hidden inside a `should…` predicate: every
     * caller that claims the slot owes a release, and two review rounds found bugs
     * that were exactly a missing release on a path that did not look like it had
     * claimed anything. [CrashDialogController.finish] is the release.
     */
    internal fun tryClaimDialogSlot(): Boolean {
        val claimed = dialogVisible.compareAndSet(false, true)
        if (!claimed) {
            logger.warn(
                LogCategory.SYSTEM,
                "Crash while the crash dialog is open - recording instead of stacking a second dialog",
            )
        }
        return claimed
    }

    /**
     * Decide what a crash *is*, from a report - a convenience for tests, which have
     * one in hand. Production classifies once in [handleCrash] and threads the
     * disposition onward, so the dialog cannot be built from a different answer
     * than the one the dialog-slot decision used.
     */
    internal fun dispositionFor(
        throwable: Throwable,
        report: CrashReport,
    ): CrashDisposition = dispositionFor(throwable, report.pluginId)

    /**
     * [recoveryAvailable] is `canRecover`, not "a handler exists".
     *
     * Whether the plugin can actually be acted on used to be discovered much later,
     * inside the coordinator, long after the dialog had already told the user their
     * session was safe. A crash attributed to a plugin no live manager knows about
     * therefore rendered "BOSS keeps running" and then terminated on the very next
     * click. Classification asks the same question the recovery does, so what the
     * dialog says and what its exits do cannot disagree.
     */
    internal fun dispositionFor(
        throwable: Throwable,
        attributedPluginId: String?,
    ): CrashDisposition =
        classifyCrash(
            throwable = throwable,
            pluginId = attributedPluginId,
            recoveryAvailable = PluginCrashRecovery.canRecover(attributedPluginId),
        )

    /**
     * Carry out what the disposition promised, once the dialog is gone.
     *
     * A recoverable crash whose recovery does not take effect falls back to
     * terminating, **not** to clean-and-restart: wiping the data directory is a
     * user-initiated last resort, and reaching for it automatically would delete
     * every plugin, workspace and setting over one plugin's bug.
     */
    internal fun resolveCrash(
        disposition: CrashDisposition,
        error: Throwable,
    ): CrashOutcome {
        if (disposition is CrashDisposition.RecoverablePlugin) {
            val recovered = PluginCrashRecovery.recover(disposition.pluginId, error)
            if (recovered) {
                clearPendingReport()
                logger.info(
                    LogCategory.SYSTEM,
                    "Continued without the crashed plugin - app left running",
                    mapOf("pluginId" to disposition.pluginId),
                )
                return CrashOutcome.Recovered(disposition.pluginId)
            }
            logger.error(
                LogCategory.SYSTEM,
                "Plugin crash recovery did not take effect - terminating",
                mapOf("pluginId" to disposition.pluginId),
            )
        }
        terminateAfterCrash()
        return CrashOutcome.Terminated
    }

    /**
     * Show the crash dialog in a separate AWT/Swing window.
     * This window is independent of the main Compose UI, so it will display
     * even when the main UI thread has crashed.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun showCrashDialogWindow(
        report: CrashReport,
        throwable: Throwable,
        // Passed in rather than recomputed. Both computations call canRecover,
        // which asks the live managers, so a concurrent unload landing between them
        // could build the dialog with a different disposition than the one the slot
        // decision used. Attribution is already threaded through for exactly this
        // reason. (The failure direction was safe - recoverable to fatal - but
        // "safe" is not the same as "agrees with itself".)
        disposition: CrashDisposition,
    ) {
        // frame and controller are built OUTSIDE the try so the failure path can
        // reach them. Declared inside, the catch could only call resolveCrash
        // directly - and a throw landing after `isVisible = true` (toFront and
        // requestFocus both run after it, and both fail on a hostile window
        // manager) then recovered the crash while leaving a live dialog on screen,
        // whose every exit resolved it a *second* time. By then the background
        // unload can have made the plugin unknown, so that second pass fails and
        // terminates a session which had already been recovered - the exact
        // sequence CrashDialogController.finished exists to prevent, reintroduced
        // through the one route that skipped the controller.
        val frame = JFrame(crashWindowTitle(disposition))
        val controller =
            CrashDialogController(
                disposition = disposition,
                error = throwable,
                disposeWindow = { frame.dispose() },
            )
        try {
            // DO_NOTHING, not DISPOSE: the close box has to run the same action the
            // visible button does. Left on DISPOSE it silently dropped the report and
            // recovered nothing, which is how the three exits came to disagree.
            frame.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
            frame.addWindowListener(controller.windowClosingAdapter())

            // Inside the try, and reading a list that is empty rather than throwing when the icon
            // resource cannot be read: a missing icon must never be the reason a crash report goes
            // unshown. Empty is what every window here did before, so the fallback is status quo.
            frame.iconImages = BossWindowIcon.images

            val composePanel = ComposePanel()
            // Sized here rather than on the frame so the dialog gets these dimensions exactly;
            // pack() then derives the frame by adding the decoration insets.
            composePanel.preferredSize = Dimension(CONTENT_PREFERRED_WIDTH, CONTENT_PREFERRED_HEIGHT)
            composePanel.setContent {
                CrashReportDialog(
                    crashReport = report,
                    recoverablePluginId = (disposition as? CrashDisposition.RecoverablePlugin)?.pluginId,
                    onDismiss = { controller.dismiss() },
                    onSubmit = { userNotes, includeLogs -> controller.submit(userNotes, includeLogs) },
                    onSubmittingChanged = { controller.isSubmitting = it },
                    // Null for a recoverable plugin crash: deleting every plugin,
                    // workspace and setting is not a proportionate answer to one
                    // plugin misbehaving, and offering it beside "Continue Without
                    // Plugin" invites exactly that.
                    onCleanAndRestart =
                        if (disposition is CrashDisposition.RecoverablePlugin) {
                            null
                        } else {
                            // Through the controller, so it shares the once-only
                            // guard with the other three exits instead of keeping
                            // its own copy of dispose-then-act.
                            { controller.cleanAndRestart { cleanDataAndRestart() } }
                        },
                )
            }

            frame.minimumSize = Dimension(FRAME_MIN_WIDTH, FRAME_MIN_HEIGHT)
            frame.contentPane.add(composePanel)
            frame.pack()
            frame.setLocationRelativeTo(null) // Center on screen
            frame.isVisible = true

            // Nothing sizing-related runs past this point on purpose: this is the path that runs
            // when the app is already broken, and it shares a catch that terminates. Cosmetic
            // sizing must never be able to take the crash report down with it.

            // Bring to front
            frame.toFront()
            frame.requestFocus()
            // Throwable for the same reason as handleCrash's net: this is the path
            // that runs when the app is already broken, so an Error out of
            // ComposePanel() or pack() is realistic, and it must still reach the
            // controller that releases the dialog slot.
        } catch (e: Throwable) {
            logger.error(LogCategory.SYSTEM, "Failed to show crash dialog window", error = e)
            System.err.println("Failed to show crash dialog: ${e.message}")
            e.printStackTrace()
            // Through the controller, exactly like the three visible exits: it
            // disposes the window (which may be half-built, or fully shown), gives
            // the dialog slot back, and resolves once. A dialog left on screen here
            // would otherwise be able to resolve the same crash a second time.
            runCatching { controller.dismiss() }
                .onFailure { secondary ->
                    logger.error(LogCategory.SYSTEM, "Crash dialog teardown failed", error = secondary)
                    terminateAfterCrash()
                }
        }
    }

    /**
     * The crash window's title bar. "BOSS Has Crashed" is a lie for a fault we are
     * about to walk away from, and the title is the first thing the user reads.
     */
    private fun crashWindowTitle(disposition: CrashDisposition): String =
        when (disposition) {
            is CrashDisposition.RecoverablePlugin -> "BOSS - Plugin Crashed"
            CrashDisposition.FatalHost -> "BOSS - Crash Report"
        }

    /**
     * Create a crash report from an exception.
     */
    private fun createCrashReport(
        throwable: Throwable,
        attributedPluginId: String? = attributePluginId(throwable),
    ): CrashReport {
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
            pluginId = attributedPluginId,
        )
    }

    /**
     * Attribute a crash to a dynamically loaded plugin.
     *
     * Three sources, weakest last:
     *
     * 1. **A tag left at the execution boundary** ([PluginExecutionBoundary]).
     *    The host recorded who it was calling *before* anything threw, so this
     *    survives the plugin's frames being gone by the time we look — which is
     *    the normal case for a registered callback like a context-menu action.
     * 2. **The thread's current plugin scope**, for a crash observed while still
     *    inside a plugin call.
     * 3. **The stack**: the first frame (root cause first, so the crash origin
     *    wins over wrapping layers) whose class was defined by a
     *    [PluginClassLoader].
     *
     * Host crashes return null. Best-effort — attribution must never make crash
     * handling itself fail.
     */
    internal fun attributePluginId(throwable: Throwable): String? {
        PluginExecutionBoundary.attributionFor(throwable)?.let { return it }
        PluginExecutionBoundary.currentPluginId()?.let { return it }
        return try {
            // Root cause first: the crash origin outranks the layers that wrapped it.
            for (cause in throwable.chainOfCauses().asReversed()) {
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
     * Seed the pending-report slot.
     *
     * Only `handleCrash` fills it in production, and that path needs a real
     * uncaught exception and a window - so without this seam "recovering clears
     * the pending report" can only be asserted against a slot that was already
     * null, which passes whether or not the clearing happens.
     */
    internal fun setPendingReportForTest(report: CrashReport?) {
        _pendingCrashReport.value = report
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
     *
     * Reached for a fatal host crash, and as the fallback when a plugin crash
     * could not be recovered from. Never as a *default* for a plugin crash: see
     * [resolveCrash].
     */
    fun terminateAfterCrash() {
        clearPendingReport()
        logger.info(LogCategory.SYSTEM, "Terminating application after crash")
        processExit(1)
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
        processExit(0)
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
