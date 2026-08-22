package ai.rever.boss.plugin.browser

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pre-warm gate, and the case it used to get wrong.
 *
 * The unforced gate reads "has this machine ever used the browser", which it answers by looking for
 * the browser profile directory - a directory the first engine boot creates. On a first install the
 * answer is therefore no, and the pre-warm was skipped on the one launch that pays most for
 * skipping it, including immediately after the user sat through a download of the browser engine.
 * Callers that already know a browser tab is coming pass `force`.
 *
 * `force` stays deliberately narrow: it creates the profile as a side effect, so making it the
 * default would flip the unforced gate on permanently and pre-warm every later launch for someone
 * who never opens a browser - the exact cost that gate exists to avoid.
 */
class EnginePrewarmGateTest {
    @Test
    fun `a machine that has used the browser pre-warms without being asked twice`() {
        assertTrue(FluckEngine.shouldPrewarm(prewarmDisabled = false, profileExists = true, force = false))
    }

    @Test
    fun `a first install does not pre-warm on its own`() {
        assertFalse(FluckEngine.shouldPrewarm(prewarmDisabled = false, profileExists = false, force = false))
    }

    @Test
    fun `a caller that knows a browser tab is coming pre-warms on a first install`() {
        // The regression this change fixes: same inputs as the test above, and the answer has to
        // flip, or a restored browser tab / a just-downloaded engine keeps paying the cold boot
        // inside the tab.
        assertTrue(FluckEngine.shouldPrewarm(prewarmDisabled = false, profileExists = false, force = true))
    }

    @Test
    fun `the opt-out outranks force`() {
        // BOSS_BROWSER_PREWARM=false is the user declining a Chromium boot they did not ask for.
        // A caller knowing a tab is coming does not overrule that - the tab boots the engine
        // itself when it gets there.
        assertFalse(FluckEngine.shouldPrewarm(prewarmDisabled = true, profileExists = false, force = true))
        assertFalse(FluckEngine.shouldPrewarm(prewarmDisabled = true, profileExists = true, force = true))
        assertFalse(FluckEngine.shouldPrewarm(prewarmDisabled = true, profileExists = true, force = false))
    }
}
