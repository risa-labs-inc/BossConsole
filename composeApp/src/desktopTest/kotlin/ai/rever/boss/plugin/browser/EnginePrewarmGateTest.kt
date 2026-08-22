package ai.rever.boss.plugin.browser

import ai.rever.boss.plugin.browser.FluckEngine.PrewarmDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pre-warm gate, and the case it used to get wrong.
 *
 * The profile check reads "has this machine ever used the browser", answered by looking for the
 * browser profile directory - a directory the first engine boot creates. On a first install the
 * answer is therefore no, and the pre-warm was skipped on the one launch that pays most for
 * skipping it, including immediately after the user sat through a download of the browser engine.
 * Callers that already know a browser tab is coming pass `force`.
 *
 * `force` stays deliberately narrow: it creates the profile as a side effect, so making it the
 * default would flip the unforced gate on permanently and pre-warm every later launch for someone
 * who never opens a browser - the exact cost that gate exists to avoid.
 */
class EnginePrewarmGateTest {
    private fun decide(
        prewarmDisabled: Boolean = false,
        force: Boolean = false,
        engineRunning: Boolean = false,
        engineUsable: Boolean = true,
        profileExists: Boolean = false,
    ) = FluckEngine.prewarmDecision(
        prewarmDisabled = prewarmDisabled,
        force = force,
        engineRunning = { engineRunning },
        engineUsable = { engineUsable },
        profileExists = { profileExists },
    )

    @Test
    fun `a machine that has used the browser pre-warms without being asked twice`() {
        assertEquals(PrewarmDecision.RUN, decide(profileExists = true))
    }

    @Test
    fun `a first install does not pre-warm on its own`() {
        assertEquals(PrewarmDecision.NEVER_USED_BROWSER, decide(profileExists = false))
    }

    @Test
    fun `a caller that knows a browser tab is coming pre-warms on a first install`() {
        // The regression this change fixes: same inputs as the test above, and the answer has to
        // flip, or a restored browser tab / a just-downloaded engine keeps paying the cold boot
        // inside the tab.
        assertEquals(PrewarmDecision.RUN, decide(force = true, profileExists = false))
    }

    @Test
    fun `the opt-out outranks force`() {
        // BOSS_BROWSER_PREWARM=false is the user declining a Chromium boot they did not ask for.
        // A caller knowing a tab is coming does not overrule that - the tab boots the engine
        // itself when it gets there.
        assertEquals(PrewarmDecision.OPTED_OUT, decide(prewarmDisabled = true, force = true))
        assertEquals(PrewarmDecision.OPTED_OUT, decide(prewarmDisabled = true, profileExists = true))
    }

    @Test
    fun `no usable engine outranks force, because that boot could only fail`() {
        // Without an engine directory the boot walks into getChromiumDir() throwing: an error log
        // and a burnt attempt for nothing. applyWorkspace runs on every window and every workspace
        // switch, so a forced call with no gate would repeat that indefinitely on an engine-less
        // machine - and could hold the boot slot at the moment a completed download wants it.
        assertEquals(PrewarmDecision.NO_USABLE_ENGINE, decide(engineUsable = false, force = true))
        assertEquals(PrewarmDecision.NO_USABLE_ENGINE, decide(engineUsable = false, profileExists = true))
    }

    @Test
    fun `an engine already running settles it before any filesystem work`() {
        // The steady state: engine up, user switching workspaces. Without this each switch would
        // stat the engine directories, claim the boot slot and spawn a thread whose only job is to
        // notice the engine is already there. Both suppliers throw, so reaching either fails.
        val explode: () -> Boolean = { error("must not be asked") }

        assertEquals(
            PrewarmDecision.ALREADY_RUNNING,
            FluckEngine.prewarmDecision(
                prewarmDisabled = false,
                force = true,
                engineRunning = { true },
                engineUsable = explode,
                profileExists = explode,
            ),
        )
    }

    @Test
    fun `every refusal is a distinct decision, so the log cannot say the wrong reason`() {
        // One shared exit used to log "no browser profile on this machine yet" for an opt-out that
        // had nothing to do with the profile, and logged nothing at all when a forced caller was
        // overruled. Each refusal now carries its own name into the log as a structured field.
        assertEquals(
            listOf(
                PrewarmDecision.OPTED_OUT,
                PrewarmDecision.ALREADY_RUNNING,
                PrewarmDecision.NO_USABLE_ENGINE,
                PrewarmDecision.NEVER_USED_BROWSER,
            ).distinct().size,
            4,
        )
        assertEquals(PrewarmDecision.OPTED_OUT, decide(prewarmDisabled = true, engineRunning = true))
    }

    @Test
    fun `the cheap answers cost no filesystem work`() {
        // Both suppliers throw, so reaching one fails the test. An opt-out is settled before either
        // is asked, and force settles the profile question without asking it.
        val explode: () -> Boolean = { error("must not be asked") }

        assertEquals(
            PrewarmDecision.OPTED_OUT,
            FluckEngine.prewarmDecision(
                prewarmDisabled = true,
                force = false,
                engineRunning = explode,
                engineUsable = explode,
                profileExists = explode,
            ),
        )
        assertEquals(
            PrewarmDecision.RUN,
            FluckEngine.prewarmDecision(
                prewarmDisabled = false,
                force = true,
                engineRunning = { false },
                engineUsable = { true },
                profileExists = explode,
            ),
        )
    }

    @Test
    fun `the boot slot admits one thread and re-arms when it is released`() {
        // Claims and releases only what it took, rather than clearing the flag in an @AfterTest:
        // this is process-wide state, and a blanket release would hand a second boot thread to a
        // real pre-warm started by any other suite in the same JVM.
        // The slot is what keeps a second thread from parking on engineLock for the whole of
        // somebody else's boot and then logging a "pre-warmed" line for work it did not do.
        assertTrue(FluckEngine.claimPrewarmSlot(), "the first claim must win")
        assertFalse(FluckEngine.claimPrewarmSlot(), "a second claim must be refused while one runs")

        // Released in a finally, so it covers a boot that failed as well as one that succeeded.
        // Held for the process lifetime instead, an engine recycle - which drops the engine and is
        // exactly when a head start is worth something again - would refuse every later caller for
        // good, including a completed engine download.
        FluckEngine.releasePrewarmSlot()
        assertTrue(FluckEngine.claimPrewarmSlot(), "the slot must re-arm once the boot thread exits")
        FluckEngine.releasePrewarmSlot()
    }
}
