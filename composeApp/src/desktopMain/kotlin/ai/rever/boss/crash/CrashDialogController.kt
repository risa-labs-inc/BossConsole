package ai.rever.boss.crash

import ai.rever.boss.utils.CleanupRunner
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.atomic.AtomicBoolean

/** What actually happened when the crash dialog closed. */
sealed interface CrashOutcome {
    /** The offending plugin was taken out; the app kept running. */
    data class Recovered(
        val pluginId: String,
    ) : CrashOutcome

    /** The process was ended (fatal host crash, or recovery could not be done). */
    data object Terminated : CrashOutcome
}

/**
 * The crash dialog's exits, in one place.
 *
 * There are three ways out of that window — the button, Escape, and the title
 * bar's close box — and they used to disagree. Escape and the button both
 * terminated the process; the close box merely disposed the frame, leaving the
 * app running with a crash that had been reported to nobody. That divergence is
 * only possible while each route carries its own copy of the logic, so all three
 * are wired to [dismiss] here and the divergence has nowhere to live.
 *
 * A successful submission ends the same way: submitting a report is not a reason
 * to lose your session over a plugin's bug.
 *
 * @param disposition decided once, at the point the dialog is built, so every
 *   exit agrees on whether this crash is survivable.
 * @param disposeWindow attempts to tear the crash window down before resolving
 *   the outcome. A disposal failure is logged and cannot block recovery or
 *   termination after the once-only exit has been claimed.
 * @param resolve injected for tests, which need to observe termination without
 *   ending the test JVM.
 */
internal class CrashDialogController(
    private val disposition: CrashDisposition,
    private val error: Throwable,
    private val disposeWindow: () -> Unit,
    private val resolve: (CrashDisposition, Throwable) -> CrashOutcome = CrashHandler::resolveCrash,
) {
    private val logger = BossLogger.forComponent("CrashHandler")
    private val cleanup = CleanupRunner("CrashDialog")

    /**
     * True while a report is being submitted.
     *
     * The dialog gates Escape and disables the dismiss button on its own copy of
     * this, but the close box had no gate at all - so closing the window mid-POST
     * ran the full exit and, for a fatal crash, called `System.exit(1)` out from
     * under an in-flight submission, losing the report. Before this change the
     * close box was inert, so that was a regression. The gate belongs in the
     * action, not in two of the three call sites.
     */
    @Volatile
    var isSubmitting: Boolean = false

    /** Shared by "Don't Send" / "Continue Without Plugin", Escape, and the close box. */
    fun dismiss(): CrashOutcome {
        if (isSubmitting) {
            logger.info(LogCategory.SYSTEM, "Ignoring a dismiss while the crash report is still submitting")
            return lastOutcome
        }
        logger.info(
            LogCategory.SYSTEM,
            "User dismissed crash report without submitting",
            mapOf("disposition" to dispositionLabel()),
        )
        return finish()
    }

    /** Called after the report was accepted by GitHub. */
    fun submit(
        userNotes: String?,
        includeLogs: Boolean,
    ): CrashOutcome {
        logger.info(
            LogCategory.SYSTEM,
            "Crash report submitted",
            mapOf(
                "hasNotes" to (userNotes != null),
                "includedLogs" to includeLogs,
                "disposition" to dispositionLabel(),
            ),
        )
        return finish()
    }

    /**
     * The window's close box, wired to the same [dismiss] the visible button runs.
     *
     * Returned as an adapter rather than wired inline so a test can invoke the
     * production listener directly — constructing a `WindowAdapter` needs no
     * display, and the event argument is unused.
     */
    fun windowClosingAdapter(): WindowAdapter =
        object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                dismiss()
            }
        }

    /**
     * Run the exit exactly once.
     *
     * The exits are not mutually exclusive in time: after a successful submission
     * the dialog waits two seconds before calling `onSubmit`, and the dismiss
     * button, Escape and the post-submit Close button all stay live through it.
     * A second pass would recover a second time - and if the background unload had
     * already made the plugin unknown, that second [PluginCrashRecovery.recover]
     * returns false and terminates the app immediately after a *successful*
     * recovery. Compose's disposal probably cancels the delayed callback first,
     * but "does the session survive" is not something to leave resting on that.
     */
    private val finished = AtomicBoolean(false)

    /**
     * The destructive escape hatch, routed through the same once-only guard.
     *
     * It used to run inline in the handler, which is the "every route carries its
     * own copy of the logic" shape this class exists to remove - and it sat outside
     * the guard, so it could interleave with another exit. [action] terminates, so
     * nothing here needs to resolve an outcome.
     */
    fun cleanAndRestart(action: () -> Unit) {
        if (!finished.compareAndSet(false, true)) {
            logger.debug(LogCategory.SYSTEM, "Ignoring clean-and-restart on a crash dialog already closed")
            return
        }
        logger.info(LogCategory.SYSTEM, "User requested clean data and restart")
        cleanup.run("dispose crash window", disposeWindow)
        action()
    }

    private fun finish(): CrashOutcome {
        if (!finished.compareAndSet(false, true)) {
            logger.debug(LogCategory.SYSTEM, "Ignoring a second exit from a crash dialog already closed")
            return lastOutcome
        }
        cleanup.run("dispose crash window", disposeWindow)
        // Released AFTER resolve, not before. The slot is about the window, which is
        // normally gone by now, but releasing first left a gap: resolve is what eventually
        // marks the recovery quarantine, so between the release and the mark a
        // second crash from the same plugin passed both gates and opened a second
        // dialog for a plugin already being recovered. Marking earlier inside
        // recover() narrowed that window; releasing afterwards closes it, and costs
        // nothing because resolve always either exits or returns.
        //
        // Still not tied to "recovery succeeded", which was the other bug: that put
        // the invariant in another file with nothing asserting it, and any exit that
        // returned without terminating suppressed every later dialog for the life of
        // the process.
        return try {
            resolve(disposition, error).also { lastOutcome = it }
        } finally {
            CrashHandler.releaseDialogSlot()
        }
    }

    /**
     * What the single real exit decided; replayed to any later caller.
     *
     * **Advisory.** A second exit arriving while the first is still inside
     * [resolve] reads the initial `Terminated` rather than the outcome that is
     * about to be produced. No production caller reads the return value - the
     * exits are wired as `() -> Unit` - so this is a shape for tests and for
     * whoever wires the next caller: what the guard actually guarantees is that
     * the *effects* happen once, not that every caller learns the result.
     */
    @Volatile
    private var lastOutcome: CrashOutcome = CrashOutcome.Terminated

    private fun dispositionLabel(): String =
        when (disposition) {
            is CrashDisposition.RecoverablePlugin -> "recoverable:${disposition.pluginId}"
            CrashDisposition.FatalHost -> "fatal"
        }
}
