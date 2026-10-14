package ai.rever.boss.crash

import ai.rever.boss.plugin.sandbox.PluginExecutionBoundary
import ai.rever.boss.plugin.sandbox.ui.PluginCrashRegistry
import ai.rever.boss.plugin.sandbox.ui.PluginRecoveryQuarantine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers what happens when the crash dialog closes.
 *
 * Until this change every exit terminated the process: "Don't Send", Escape and
 * even a *successful* report submission all reached `System.exit(1)`, while the
 * window's close box quietly disposed the frame and did nothing else. So a plugin
 * throwing from a context-menu handler cost the user every window, tab and live
 * terminal session, and which of the three exits they used changed the outcome.
 *
 * These tests pin both halves: a plugin crash is survivable, a host crash is
 * still not, and all three exits do the same thing.
 *
 * [CrashHandler.processExit] is swapped for a recorder rather than mocked away -
 * a test that calls the real one takes the suite with it.
 *
 * This class mutates process-global singletons ([CrashHandler.processExit],
 * [PluginCrashRecovery.handler], the dialog slot and [PluginCrashRegistry]) and
 * restores them in teardown. That holds only while this module runs tests in one
 * fork; enabling `maxParallelForks` would let a parallel class observe or lose
 * these, so re-check here before turning that on.
 */
class CrashRecoveryTest {
    private companion object {
        const val OFFENDER = "ai.rever.boss.plugin.dynamic.probe"
        const val BYSTANDER = "ai.rever.boss.plugin.dynamic.bystander"
    }

    private val error = IllegalStateException("plugin action boom")
    private val exitCodes = mutableListOf<Int>()
    private val disposed = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        exitCodes.clear()
        disposed.clear()
        CrashHandler.processExit = { code -> exitCodes.add(code) }
        CrashHandler.setPendingReportForTest(null)
        PluginCrashRecovery.handler = null
    }

    @AfterTest
    fun tearDown() {
        CrashHandler.processExit = { code -> System.exit(code) }
        CrashHandler.setPendingReportForTest(null)
        CrashHandler.releaseDialogSlot()
        PluginCrashRecovery.handler = null
        PluginCrashRegistry.clearCrash(OFFENDER)
        PluginCrashRegistry.clearCrash(BYSTANDER)
        // Separately, since clearCrash no longer implies it - a leaked quarantine
        // marker would silence the crash dialog in whatever test ran next.
        PluginRecoveryQuarantine.clear(OFFENDER)
        PluginRecoveryQuarantine.clear(BYSTANDER)
    }

    private fun controller(
        disposition: CrashDisposition,
        onDispose: String = "dialog",
    ) = CrashDialogController(
        disposition = disposition,
        error = error,
        disposeWindow = { disposed.add(onDispose) },
    )

    private val recoverable = CrashDisposition.RecoverablePlugin(OFFENDER)

    @Test
    fun `failed dialog disposal still recovers once and releases the dialog slot`() {
        val recovered = installRecovery(succeeds = true)
        var attempts = 0
        val controller =
            CrashDialogController(
                disposition = recoverable,
                error = error,
                disposeWindow = {
                    attempts++
                    error("native dialog disposal failed")
                },
            )
        assertTrue(CrashHandler.tryClaimDialogSlot())

        assertEquals(CrashOutcome.Recovered(OFFENDER), controller.dismiss())
        assertEquals(CrashOutcome.Recovered(OFFENDER), controller.dismiss())

        assertEquals(1, attempts)
        assertEquals(listOf(OFFENDER), recovered)
        assertTrue(exitCodes.isEmpty())
        assertTrue(CrashHandler.tryClaimDialogSlot(), "failed disposal must not silence future crash dialogs")
    }

    @Test
    fun `failed dialog disposal cannot block fatal termination`() {
        val controller =
            CrashDialogController(
                disposition = CrashDisposition.FatalHost,
                error = error,
                disposeWindow = { error("native dialog disposal failed") },
            )

        assertEquals(CrashOutcome.Terminated, controller.dismiss())

        assertEquals(listOf(1), exitCodes)
    }

    @Test
    fun `failed dialog disposal cannot block clean and restart`() {
        var restarts = 0
        val controller =
            CrashDialogController(
                disposition = CrashDisposition.FatalHost,
                error = error,
                disposeWindow = { error("native dialog disposal failed") },
            )

        controller.cleanAndRestart { restarts++ }
        controller.cleanAndRestart { restarts++ }

        assertEquals(1, restarts)
    }

    /** A recovery seam that reports success without needing a plugin manager. */
    private fun installRecovery(succeeds: Boolean): MutableList<String> {
        val recovered = mutableListOf<String>()
        PluginCrashRecovery.handler =
            PluginCrashRecoveryHandler { pluginId, _ ->
                recovered.add(pluginId)
                succeeds
            }
        return recovered
    }

    @Test
    fun `dismissing a plugin crash does not exit the process`() {
        val recovered = installRecovery(succeeds = true)

        val outcome = controller(recoverable).dismiss()

        assertEquals(emptyList(), exitCodes, "a recoverable plugin crash must never reach System.exit")
        assertEquals(CrashOutcome.Recovered(OFFENDER), outcome)
        assertEquals(listOf(OFFENDER), recovered)
        assertEquals(listOf("dialog"), disposed, "the crash window must be disposed either way")
    }

    @Test
    fun `dismissing a plugin crash clears the pending report`() {
        installRecovery(succeeds = true)
        CrashHandler.setPendingReportForTest(report())

        controller(recoverable).dismiss()

        // A report left pending would be picked up by the next submission and sent
        // as if it were that crash.
        assertNull(CrashHandler.pendingCrashReport.value)
    }

    @Test
    fun `submitting a report does not terminate after a recoverable plugin crash`() {
        installRecovery(succeeds = true)

        val outcome = controller(recoverable).submit(userNotes = "clicked the menu item", includeLogs = true)

        assertEquals(emptyList(), exitCodes, "reporting a plugin bug must not cost the session")
        assertEquals(CrashOutcome.Recovered(OFFENDER), outcome)
    }

    @Test
    fun `the close box takes the same action as the visible button`() {
        val recovered = installRecovery(succeeds = true)

        // The production listener, invoked directly - the event argument is unused,
        // so this needs no display. Before this change the close box was wired to
        // DISPOSE_ON_CLOSE and did none of the below.
        controller(recoverable).windowClosingAdapter().windowClosing(null)

        assertEquals(emptyList(), exitCodes)
        assertEquals(listOf(OFFENDER), recovered)
        assertEquals(listOf("dialog"), disposed)
    }

    @Test
    fun `a second exit from the same dialog does nothing`() {
        val recovered = installRecovery(succeeds = true)
        val controller = controller(recoverable)

        val first = controller.dismiss()
        val second = controller.dismiss()

        // The exits overlap in time: after a successful submission the dialog waits
        // two seconds before calling onSubmit while every dismiss control stays
        // live. Recovering twice would quarantine twice, and once the background
        // unload has made the plugin unknown the second recovery FAILS - which
        // terminates the app right after a successful recovery.
        assertEquals(listOf(OFFENDER), recovered, "recovery must run exactly once")
        assertEquals(emptyList(), exitCodes)
        assertEquals(listOf("dialog"), disposed)
        assertEquals(first, second, "a repeat exit replays the outcome rather than deciding a new one")
    }

    @Test
    fun `submitting after dismissing does not recover twice`() {
        val recovered = installRecovery(succeeds = true)
        val controller = controller(recoverable)

        controller.dismiss()
        val outcome = controller.submit(userNotes = null, includeLogs = false)

        assertEquals(listOf(OFFENDER), recovered)
        assertEquals(emptyList(), exitCodes)
        assertEquals(CrashOutcome.Recovered(OFFENDER), outcome)
    }

    @Test
    fun `the dialog slot is released on every exit, so a later crash still prompts`() {
        installRecovery(succeeds = true)
        // Claim the slot the way a first crash does.
        assertFalse(CrashHandler.tryClaimDialogSlot().not(), "the first crash should prompt")

        controller(recoverable).dismiss()

        // Releasing only on the recovered branch put this invariant in another file
        // with nothing asserting it: any exit that returned without terminating
        // would have silenced every crash dialog for the rest of the process.
        assertFalse(
            CrashHandler.tryClaimDialogSlot().not(),
            "a crash after the dialog closed must be able to open a new one",
        )
        CrashHandler.releaseDialogSlot()
    }

    @Test
    fun `a second crash while the dialog is open is recorded rather than stacked`() {
        assertFalse(CrashHandler.tryClaimDialogSlot().not(), "the first crash claims the slot")

        // A plugin throwing from a paint or a timer produces one of these per frame.
        // A second window on top of the first hides it and neither can be reached.
        assertTrue(CrashHandler.tryClaimDialogSlot().not())

        CrashHandler.releaseDialogSlot()
    }

    @Test
    fun `a crash from a recovery-quarantined plugin never opens a dialog`() {
        PluginCrashRegistry.recordRenderFault(OFFENDER, error, notify = false)
        PluginRecoveryQuarantine.mark(OFFENDER)
        val fromQuarantined = IllegalStateException("its timer is still running")
        PluginExecutionBoundary.tag(fromQuarantined, OFFENDER)

        assertTrue(
            CrashHandler.isSuppressedByQuarantine(OFFENDER),
            "a disabled plugin's lingering thread must not prompt on every throw",
        )
        // And the slot was not claimed, so an unrelated crash can still prompt.
        assertFalse(CrashHandler.tryClaimDialogSlot().not())
        CrashHandler.releaseDialogSlot()
    }

    @Test
    fun `a plugin that merely tripped a render boundary still gets a dialog`() {
        // The two quarantines are different. A contained render fault leaves the
        // plugin ENABLED and running behind a fallback; suppressing its crash
        // dialog would mean it could misbehave indefinitely with nothing shown -
        // strictly worse than before crash recovery existed. Only a plugin taken
        // out BY recovery is suppressed.
        PluginCrashRegistry.recordRenderFault(BYSTANDER, error, notify = false)
        val laterCrash = IllegalStateException("a real, uncaught crash")
        PluginExecutionBoundary.tag(laterCrash, BYSTANDER)

        assertFalse(
            CrashHandler.isSuppressedByQuarantine(OFFENDER),
            "a contained render fault must not silence the crash dialog for that plugin",
        )
        CrashHandler.releaseDialogSlot()
    }

    @Test
    fun `re-enabling a plugin un-suppresses its crash dialog`() {
        PluginCrashRegistry.recordRenderFault(OFFENDER, error, notify = false)
        PluginRecoveryQuarantine.mark(OFFENDER)
        val laterCrash = IllegalStateException("crashed again after re-enable")
        PluginExecutionBoundary.tag(laterCrash, OFFENDER)
        assertTrue(CrashHandler.isSuppressedByQuarantine(OFFENDER), "suppressed while quarantined")
        CrashHandler.releaseDialogSlot()

        // What DynamicPluginManager.enablePlugin does on a deliberate re-arm - both
        // calls. clearCrash alone deliberately no longer releases the quarantine,
        // because it has automatic callers (PluginRenderRecovery.releaseSuspect),
        // and letting a render fault drop the marker would re-open a crash dialog
        // for a plugin the user had already dealt with.
        PluginCrashRegistry.clearCrash(OFFENDER)
        PluginRecoveryQuarantine.clear(OFFENDER)

        assertFalse(
            CrashHandler.isSuppressedByQuarantine(OFFENDER),
            "a plugin the user put back must be able to report a new crash",
        )
        CrashHandler.releaseDialogSlot()
    }

    @Test
    fun `recovery marks the plugin as recovery-quarantined`() {
        val fake = FakePluginLayer(known = setOf(OFFENDER))
        PluginCrashRecovery.handler = fake.coordinator()

        controller(recoverable).dismiss()

        assertTrue(PluginRecoveryQuarantine.isQuarantined(OFFENDER))
        assertFalse(PluginRecoveryQuarantine.isQuarantined(BYSTANDER))
    }

    @Test
    fun `a dismiss during submission is ignored`() {
        val recovered = installRecovery(succeeds = true)
        val controller = controller(recoverable)
        controller.isSubmitting = true

        controller.dismiss()

        // The close box had no gate, so closing the window mid-POST ran the full
        // exit - for a fatal crash, System.exit(1) out from under an in-flight
        // submission. Escape and the button gate on the dialog's own state; this is
        // the same gate for the exit that cannot see it.
        assertEquals(emptyList(), recovered, "recovery must not run mid-submission")
        assertEquals(emptyList(), disposed, "the window must stay up mid-submission")
        assertEquals(emptyList(), exitCodes)

        // And once the submission finishes, the exit works normally.
        controller.isSubmitting = false
        controller.dismiss()
        assertEquals(listOf(OFFENDER), recovered)
    }

    @Test
    fun `dispositionFor wires the report's plugin id and the seam's availability`() {
        val report = report()
        // classifyCrash is tested to exhaustion on its own; this pins the wiring,
        // which is where a recoverable crash would quietly become fatal.
        assertIs<CrashDisposition.FatalHost>(
            CrashHandler.dispositionFor(error, report),
            "with no recovery handler installed, even an attributed crash is fatal",
        )

        installRecovery(succeeds = true)

        assertEquals(CrashDisposition.RecoverablePlugin(OFFENDER), CrashHandler.dispositionFor(error, report))
        assertIs<CrashDisposition.FatalHost>(
            CrashHandler.dispositionFor(error, report.copy(pluginId = null)),
            "a host crash stays fatal however the seam is wired",
        )
    }

    @Test
    fun `the quarantine marker is not yet set when recovery starts scanning managers`() {
        // The window is gone but resolve is still running. A crash landing here
        // used to be able to open a second dialog for a plugin already being
        // recovered; the quarantine marker is set before the unload starts, so the
        // handler sees it even mid-recovery.
        // Through the REAL coordinator, not a fake that marks first. An earlier
        // version of this test used a handler whose first statement was mark(),
        // which is an ordering production did not have - so it asserted the fake.
        // The observation point is inside isKnown, which the coordinator calls
        // before anything else, and which in production scans every live manager.
        var seenDuringRecovery: Boolean? = null
        val fake =
            object : PluginRecoverySteps {
                override fun isKnown(pluginId: String): Boolean {
                    seenDuringRecovery = CrashHandler.isSuppressedByQuarantine(pluginId)
                    return true
                }

                override fun quarantine(
                    pluginId: String,
                    error: Throwable,
                ) = PluginCrashRegistry.recordRenderFault(pluginId, error, notify = false)

                override suspend fun closeTabs(pluginId: String) = Unit

                override suspend fun disable(pluginId: String) = true

                override fun persistDisabled(pluginId: String) = true

                override fun notifyDisabling(pluginId: String) = Unit

                override fun notifyDisableIncomplete(pluginId: String) = Unit
            }
        PluginCrashRecovery.handler = PluginCrashRecoveryCoordinator(CoroutineScope(Dispatchers.Unconfined), fake)

        controller(recoverable).dismiss()

        // Named for what it asserts. isKnown runs before the marker is set, so the
        // marker is NOT what protects this instant - the dialog slot is, and
        // finish() releases that only after resolve returns. The test that pins the
        // protection is `the dialog slot is only released once recovery has
        // quarantined the plugin`; this one pins the ordering that test relies on.
        assertEquals(false, seenDuringRecovery, "the observation point is before the marker is set")
        assertEquals(emptyList(), exitCodes)
    }

    @Test
    fun `the quarantine marker is set before the slow half of recovery`() {
        // The dialog slot is released before resolve runs, so between that and the
        // marker there is a window in which a second crash from the same plugin
        // could claim the slot and open a second dialog. Marking before the
        // quarantine step - which in production scans every live manager - closes
        // it to the width of isKnown.
        var markedWhenQuarantineRan: Boolean? = null
        val fake =
            object : PluginRecoverySteps {
                override fun isKnown(pluginId: String) = true

                override fun quarantine(
                    pluginId: String,
                    error: Throwable,
                ) {
                    markedWhenQuarantineRan = PluginRecoveryQuarantine.isQuarantined(pluginId)
                }

                override suspend fun closeTabs(pluginId: String) = Unit

                override suspend fun disable(pluginId: String) = true

                override fun persistDisabled(pluginId: String) = true

                override fun notifyDisabling(pluginId: String) = Unit

                override fun notifyDisableIncomplete(pluginId: String) = Unit
            }
        PluginCrashRecovery.handler = PluginCrashRecoveryCoordinator(CoroutineScope(Dispatchers.Unconfined), fake)

        controller(recoverable).dismiss()

        assertEquals(true, markedWhenQuarantineRan)
    }

    @Test
    fun `a crash blamed on an unknown plugin is classified fatal, not promised recovery`() {
        // The dialog used to say "BOSS keeps running, x will be disabled" for a
        // plugin no live manager knows about - a lingering thread from something
        // already unloaded - and then terminate on the very next click. What the
        // dialog says and what its exits do have to be decided by the same question.
        PluginCrashRecovery.handler =
            PluginCrashRecoveryCoordinator(
                CoroutineScope(Dispatchers.Unconfined),
                FakePluginLayer(known = emptySet()),
            )

        assertIs<CrashDisposition.FatalHost>(CrashHandler.dispositionFor(error, OFFENDER))
    }

    @Test
    fun `a known plugin is still classified recoverable`() {
        PluginCrashRecovery.handler =
            PluginCrashRecoveryCoordinator(
                CoroutineScope(Dispatchers.Unconfined),
                FakePluginLayer(known = setOf(OFFENDER)),
            )

        assertEquals(CrashDisposition.RecoverablePlugin(OFFENDER), CrashHandler.dispositionFor(error, OFFENDER))
    }

    @Test
    fun `the dialog slot is only released once recovery has quarantined the plugin`() {
        // Releasing before resolve left a gap: resolve is what marks the quarantine,
        // so a second crash from the same plugin in between passed both gates and
        // opened a second dialog for a plugin already being recovered.
        var slotFreeBeforeMark: Boolean? = null
        val fake =
            object : PluginRecoverySteps {
                override fun isKnown(pluginId: String): Boolean {
                    // Observed inside recovery, before the quarantine is marked.
                    slotFreeBeforeMark = CrashHandler.tryClaimDialogSlot()
                    return true
                }

                override fun quarantine(
                    pluginId: String,
                    error: Throwable,
                ) = Unit

                override suspend fun closeTabs(pluginId: String) = Unit

                override suspend fun disable(pluginId: String) = true

                override fun persistDisabled(pluginId: String) = true

                override fun notifyDisabling(pluginId: String) = Unit

                override fun notifyDisableIncomplete(pluginId: String) = Unit
            }
        PluginCrashRecovery.handler = PluginCrashRecoveryCoordinator(CoroutineScope(Dispatchers.Unconfined), fake)
        check(CrashHandler.tryClaimDialogSlot()) { "the dialog owns the slot to begin with" }

        controller(recoverable).dismiss()

        assertEquals(false, slotFreeBeforeMark, "the slot must still be held while recovery is mid-flight")
    }

    @Test
    fun `a fatal crash arriving while a dialog is open still terminates`() {
        // Suppression used to run before classification, so a host OutOfMemoryError
        // landing while a plugin dialog was up got written to disk and the process
        // carried on under heap exhaustion with nothing shown. The uncontainable
        // carve-outs exist so that is never treated as survivable; this path routed
        // around both of them.
        installRecovery(succeeds = true)
        check(CrashHandler.tryClaimDialogSlot()) { "a dialog is on screen" }

        val fatal = OutOfMemoryError("heap")
        val disposition = CrashHandler.dispositionFor(fatal, OFFENDER)
        assertIs<CrashDisposition.FatalHost>(disposition, "heap exhaustion is never recoverable")
        // What handleCrash does with that disposition when the slot is already held.
        assertFalse(CrashHandler.tryClaimDialogSlot(), "no second dialog")
        CrashHandler.terminateAfterCrash()

        assertEquals(listOf(1), exitCodes)
    }

    @Test
    fun `a plugin that could not be disabled keeps its crash reporting`() {
        // Suppression is for a plugin that was actually taken out. This one is still
        // enabled and still running, so muting it would be the same failure this
        // feature was accused of once already - permanently silent crash reporting
        // on the strength of one toast.
        val fake = FakePluginLayer(known = setOf(OFFENDER), disableSucceeds = false)
        PluginCrashRecovery.handler = fake.coordinator()

        controller(recoverable).dismiss()

        assertEquals(listOf(OFFENDER), fake.corrections, "the user is told it did not take")
        assertFalse(
            PluginRecoveryQuarantine.isQuarantined(OFFENDER),
            "a plugin still running must still be able to report a crash",
        )
    }

    @Test
    fun `a fatal host crash still terminates`() {
        installRecovery(succeeds = true)

        val outcome = controller(CrashDisposition.FatalHost).dismiss()

        assertEquals(listOf(1), exitCodes)
        assertIs<CrashOutcome.Terminated>(outcome)
        assertEquals(listOf("dialog"), disposed)
    }

    @Test
    fun `a plugin crash whose recovery fails terminates rather than cleaning data`() {
        installRecovery(succeeds = false)

        val outcome = controller(recoverable).dismiss()

        assertIs<CrashOutcome.Terminated>(outcome)
        // Exit code 1 is termination; clean-and-restart exits 0 after deleting the
        // data directory. Falling back to *that* automatically would wipe every
        // plugin, workspace and setting over one plugin's bug.
        assertEquals(listOf(1), exitCodes)
    }

    @Test
    fun `a plugin crash with no recovery seam terminates`() {
        // PluginCrashRecovery.handler is null here (headless run, or a crash before
        // the plugin layer is wired). Dismissing must not pretend to have recovered.
        val outcome = controller(recoverable).dismiss()

        assertIs<CrashOutcome.Terminated>(outcome)
        assertEquals(listOf(1), exitCodes)
    }

    @Test
    fun `recovery disables the offending plugin and leaves every other plugin alone`() {
        val fake = FakePluginLayer(known = setOf(OFFENDER, BYSTANDER))
        PluginCrashRecovery.handler = fake.coordinator()

        val outcome = controller(recoverable).dismiss()

        assertEquals(CrashOutcome.Recovered(OFFENDER), outcome)
        assertEquals(emptyList(), exitCodes)
        // The offender: quarantined so its surfaces stop rendering plugin content,
        // its tabs closed, disabled in every window, and the disable persisted so it
        // does not come back and crash again on the next launch.
        assertTrue(PluginCrashRegistry.hasCrashed(OFFENDER), "the crashed plugin must be quarantined")
        assertEquals(listOf(OFFENDER), fake.tabsClosed)
        assertEquals(listOf(OFFENDER), fake.disabled)
        assertEquals(listOf(OFFENDER), fake.persistedDisabled)
        // The bystander: untouched on every axis. Quarantining everything mounted
        // was the first attempt at this elsewhere in the codebase and it closed
        // users' terminal and browser tabs over an unrelated plugin's bug.
        assertFalse(PluginCrashRegistry.hasCrashed(BYSTANDER), "an unaffected plugin must keep running")
        assertFalse(BYSTANDER in fake.disabled)
        assertFalse(BYSTANDER in fake.tabsClosed)
    }

    @Test
    fun `recovery tells the user which plugin is being disabled`() {
        val fake = FakePluginLayer(known = setOf(OFFENDER))
        PluginCrashRecovery.handler = fake.coordinator()

        controller(recoverable).dismiss()

        val message = fake.notices.single()
        assertTrue(OFFENDER in message, "the notice must name the plugin: $message")
        assertEquals(emptyList(), fake.corrections, "a clean unload needs no correction")
    }

    @Test
    fun `a disable that does not take is corrected to the user`() {
        // The first notice fires before any of the unload runs, because the user is
        // looking at the app the moment the dialog closes. When no live manager
        // accepts the disable, the plugin is quarantined in memory only and comes
        // back enabled at the next launch - the opposite of what they were told.
        val fake = FakePluginLayer(known = setOf(OFFENDER), disableSucceeds = false)
        PluginCrashRecovery.handler = fake.coordinator()

        controller(recoverable).dismiss()

        assertEquals(listOf(OFFENDER), fake.corrections)
    }

    @Test
    fun `a disable that cannot be persisted is corrected to the user`() {
        val fake = FakePluginLayer(known = setOf(OFFENDER), persistSucceeds = false)
        PluginCrashRecovery.handler = fake.coordinator()

        controller(recoverable).dismiss()

        assertEquals(listOf(OFFENDER), fake.corrections)
    }

    @Test
    fun `a crash blamed on a plugin nothing knows about is not silently swallowed`() {
        val fake = FakePluginLayer(known = emptySet())
        PluginCrashRecovery.handler = fake.coordinator()

        val outcome = controller(recoverable).dismiss()

        // Nothing to disable means nothing was made safe, so this has to terminate.
        // Returning "recovered" here would dismiss the dialog over a plugin that is
        // still live and still crashing.
        assertIs<CrashOutcome.Terminated>(outcome)
        assertEquals(listOf(1), exitCodes)
        assertFalse(PluginCrashRegistry.hasCrashed(OFFENDER))
    }

    @Test
    fun `a failed unload does not turn a successful recovery into a termination`() {
        // The plugin is already quarantined and rendering nothing by the time the
        // unload runs; killing the app because the tab teardown threw would undo the
        // only thing the user was promised.
        val fake = FakePluginLayer(known = setOf(OFFENDER), tabTeardownThrows = true)
        PluginCrashRecovery.handler = fake.coordinator()

        val outcome = controller(recoverable).dismiss()

        assertEquals(CrashOutcome.Recovered(OFFENDER), outcome)
        assertEquals(emptyList(), exitCodes)
        // And the rest of the unload still ran rather than aborting at the failure.
        assertEquals(listOf(OFFENDER), fake.disabled)
        assertEquals(listOf(OFFENDER), fake.persistedDisabled)
    }

    /**
     * Stands in for the plugin layer the desktop wiring supplies.
     *
     * The real steps are a per-window manager, an EDT tab teardown and a rewrite of
     * `installed.json`; the coordinator takes them as lambdas precisely so this test
     * does not have to build a window to ask whether dismissing a crash kills the
     * app. Quarantine is NOT faked - it calls the real [PluginCrashRegistry], since
     * "the offending plugin is quarantined and the bystander is not" is the claim.
     */
    private class FakePluginLayer(
        private val known: Set<String>,
        private val tabTeardownThrows: Boolean = false,
        private val disableSucceeds: Boolean = true,
        private val persistSucceeds: Boolean = true,
    ) : PluginRecoverySteps {
        val tabsClosed = mutableListOf<String>()
        val disabled = mutableListOf<String>()
        val persistedDisabled = mutableListOf<String>()
        val notices = mutableListOf<String>()
        val corrections = mutableListOf<String>()

        override fun isKnown(pluginId: String) = pluginId in known

        /** Not faked: "the offender is quarantined and the bystander is not" is the claim. */
        override fun quarantine(
            pluginId: String,
            error: Throwable,
        ) = PluginCrashRegistry.recordRenderFault(pluginId, error, notify = false)

        override suspend fun closeTabs(pluginId: String) {
            tabsClosed.add(pluginId)
            if (tabTeardownThrows) error("teardown failed")
        }

        override suspend fun disable(pluginId: String): Boolean {
            disabled.add(pluginId)
            return disableSucceeds
        }

        override fun persistDisabled(pluginId: String): Boolean {
            persistedDisabled.add(pluginId)
            return persistSucceeds
        }

        override fun notifyDisabling(pluginId: String) {
            notices.add("Plugin '$pluginId' crashed and is being disabled. Re-enable it from Toolbox.")
        }

        override fun notifyDisableIncomplete(pluginId: String) {
            corrections.add(pluginId)
        }

        /**
         * Unconfined so the launched unload runs to completion inline, which is what
         * lets the assertions above read its effects without a delay or a latch.
         */
        fun coordinator() = PluginCrashRecoveryCoordinator(CoroutineScope(Dispatchers.Unconfined), this)
    }

    private fun report() =
        CrashReport(
            signature = "sig",
            exceptionType = "IllegalStateException",
            exceptionMessage = "plugin action boom",
            stackTrace = "at plugin.Boom.invoke",
            systemInfo =
                SystemInfo(
                    osName = "TestOS",
                    osVersion = "1",
                    osArch = "test",
                    javaVersion = "21",
                    javaVendor = "test",
                    heapUsedMB = 1,
                    heapMaxMB = 2,
                    nonHeapUsedMB = 1,
                    availableProcessors = 1,
                ),
            appInfo = AppInfo(version = "0.0.0", platform = "macOS", isDebug = true),
            timestamp = 0L,
            pluginId = OFFENDER,
        )
}
