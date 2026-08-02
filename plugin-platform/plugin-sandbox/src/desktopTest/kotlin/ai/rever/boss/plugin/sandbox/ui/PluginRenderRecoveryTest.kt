package ai.rever.boss.plugin.sandbox.ui

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Covers recovery from a render exception nobody could attribute from the stack.
 *
 * Note this mutates process-global state in [PluginRenderRecovery] and
 * [PluginCrashRegistry], and relies on the resets below. That holds only while
 * this module runs tests in one fork; a future `maxParallelForks > 1` would turn
 * it into a mystery flake.
 *
 * This is the half [PluginRenderBoundary] cannot reach. A real crash arrived via
 * `MeasureAndLayoutDelegate.remeasureIfNeeded` — Compose re-measuring the
 * plugin's node straight from its dirty list, with neither the boundary nor the
 * plugin anywhere on the stack. Containing that kept the window alive but left
 * it broken and silent, because a repaint over a subtree that still reproduces
 * the fault changes nothing.
 *
 * The two properties that fix it pull against each other: rebuild, so a
 * recoverable fault recovers; but stop rebuilding once it is clear the content
 * reproduces the fault every time, or the window loops forever.
 */
class PluginRenderRecoveryTest {
    private val error = IllegalArgumentException("""Key "coll-dup" was already used.""")

    @BeforeTest
    fun setUp() = PluginRenderRecovery.reset()

    @AfterTest
    fun tearDown() {
        PluginRenderRecovery.reset()
        PluginCrashRegistry.clearCrash("plugin.a")
        PluginCrashRegistry.clearCrash("plugin.b")
    }

    @Test
    fun `with no plugin mounted the fault is not blamed on a plugin`() {
        val outcome = PluginRenderRecovery.onUnattributedRenderException(error, now = 1_000)

        assertEquals(
            PluginRenderRecovery.Outcome.NotPluginRelated,
            outcome,
            "a host render fault must not quarantine plugins that are not even on screen",
        )
    }

    @Test
    fun `the first failure rebuilds rather than disabling anything`() {
        PluginRenderRecovery.registerMounted("plugin.a")

        val outcome = PluginRenderRecovery.onUnattributedRenderException(error, now = 1_000)

        assertIs<PluginRenderRecovery.Outcome.Rebuilt>(outcome, "the first failure deserves a retry")
        assertEquals(setOf("plugin.a"), outcome.plugins)
        assertTrue(
            !PluginCrashRegistry.hasCrashed("plugin.a"),
            "a plugin must not be disabled before a rebuild has been tried",
        )
    }

    @Test
    fun `failing again inside the grace window quarantines instead of looping`() {
        PluginRenderRecovery.registerMounted("plugin.a")
        PluginRenderRecovery.onUnattributedRenderException(error, now = 1_000)

        val outcome =
            PluginRenderRecovery.onUnattributedRenderException(
                error,
                now = 1_000 + PluginRenderRecovery.REBUILD_GRACE_MILLIS,
            )

        assertIs<PluginRenderRecovery.Outcome.Quarantined>(outcome, "the rebuild did not take; stop retrying")
        assertEquals(setOf("plugin.a"), outcome.plugins)
        assertTrue(
            PluginCrashRegistry.hasCrashed("plugin.a"),
            "quarantine has to record the crash — that is what swaps the panel to its fallback " +
                "and stops the plugin rendering, which is what actually ends the loop",
        )
    }

    @Test
    fun `a failure long after the rebuild gets its own retry`() {
        PluginRenderRecovery.registerMounted("plugin.a")
        PluginRenderRecovery.onUnattributedRenderException(error, now = 1_000)

        // Well outside the grace window: an unrelated fault later on is not
        // evidence that the earlier rebuild failed.
        val outcome =
            PluginRenderRecovery.onUnattributedRenderException(
                error,
                now = 1_000 + PluginRenderRecovery.REBUILD_GRACE_MILLIS + 1,
            )

        assertIs<PluginRenderRecovery.Outcome.Rebuilt>(outcome, "an unrelated later fault deserves its own retry")
        assertTrue(!PluginCrashRegistry.hasCrashed("plugin.a"))
    }

    @Test
    fun `only one plugin is suspected, not every mounted panel`() {
        // The first implementation quarantined all of them. Against the real crash
        // that disabled four plugins for one plugin's bug and closed the user's
        // terminal and browser tabs.
        PluginRenderRecovery.registerMounted("plugin.a")
        PluginRenderRecovery.registerMounted("plugin.b")
        PluginRenderRecovery.onUnattributedRenderException(error, now = 1_000)

        val outcome = PluginRenderRecovery.onUnattributedRenderException(error, now = 2_000)

        assertIs<PluginRenderRecovery.Outcome.Quarantined>(outcome)
        assertEquals(1, outcome.plugins.size, "exactly one suspect at a time")
        // Most recently mounted first: the panel just opened is the better guess.
        assertEquals(setOf("plugin.b"), outcome.plugins)
        assertTrue(!PluginCrashRegistry.hasCrashed("plugin.a"), "an unsuspected plugin must keep rendering")
    }

    @Test
    fun `a suspect that does not stop the fault is released and the next is tried`() {
        PluginRenderRecovery.registerMounted("plugin.a")
        PluginRenderRecovery.registerMounted("plugin.b")
        PluginRenderRecovery.onUnattributedRenderException(error, now = 1_000)
        PluginRenderRecovery.onUnattributedRenderException(error, now = 2_000) // suspects b

        // Still failing while b is held, so b was innocent.
        val outcome = PluginRenderRecovery.onUnattributedRenderException(error, now = 3_000)

        assertIs<PluginRenderRecovery.Outcome.Quarantined>(outcome)
        assertEquals(setOf("plugin.a"), outcome.plugins, "should move on to the next candidate")
        assertTrue(
            !PluginCrashRegistry.hasCrashed("plugin.b"),
            "a suspect proven innocent must be released, not left disabled",
        )
        assertTrue(PluginCrashRegistry.hasCrashed("plugin.a"))
    }

    @Test
    fun `when every mounted plugin has been ruled out it stops churning`() {
        PluginRenderRecovery.registerMounted("plugin.a")
        PluginRenderRecovery.onUnattributedRenderException(error, now = 1_000)
        PluginRenderRecovery.onUnattributedRenderException(error, now = 2_000) // suspects a

        val outcome = PluginRenderRecovery.onUnattributedRenderException(error, now = 3_000)

        assertIs<PluginRenderRecovery.Outcome.Unexplained>(
            outcome,
            "with nobody left to blame it must stop rebuilding rather than loop",
        )
        assertTrue(
            !PluginCrashRegistry.hasCrashed("plugin.a"),
            "the last innocent suspect is released too",
        )
    }

    @Test
    fun `an unmounted panel is no longer a candidate`() {
        val unregister = PluginRenderRecovery.registerMounted("plugin.a")
        PluginRenderRecovery.registerMounted("plugin.b")
        unregister()

        assertEquals(listOf("plugin.b"), PluginRenderRecovery.mountedPlugins())

        PluginRenderRecovery.onUnattributedRenderException(error, now = 1_000)
        val outcome = PluginRenderRecovery.onUnattributedRenderException(error, now = 2_000)

        assertIs<PluginRenderRecovery.Outcome.Quarantined>(outcome)
        assertEquals(setOf("plugin.b"), outcome.plugins, "a closed panel must not be quarantined")
    }

    @Test
    fun `a plugin with two live boundaries stays mounted when one closes`() {
        // PluginErrorBoundary is per surface — a tab and a side panel, or two
        // tabs — so one plugin routinely has several. With a plain set, closing
        // one dropped the plugin while it was still rendering, after which it
        // could never be suspected: the culprit was ruled out by omission and the
        // window stayed broken.
        val closeFirst = PluginRenderRecovery.registerMounted("plugin.a")
        PluginRenderRecovery.registerMounted("plugin.a")

        closeFirst()

        assertTrue(
            PluginRenderRecovery.mountedPlugins().contains("plugin.a"),
            "a plugin still rendering in another surface must remain a candidate",
        )
    }

    @Test
    fun `a plugin is unmounted once its last boundary closes`() {
        val closeFirst = PluginRenderRecovery.registerMounted("plugin.a")
        val closeSecond = PluginRenderRecovery.registerMounted("plugin.a")

        closeFirst()
        closeSecond()

        assertFalse(PluginRenderRecovery.mountedPlugins().contains("plugin.a"))
    }

    @Test
    fun `unregistering twice does not drop a plugin another surface still holds`() {
        val close = PluginRenderRecovery.registerMounted("plugin.a")
        PluginRenderRecovery.registerMounted("plugin.a")

        close()
        close() // a double dispose must not decrement someone else's count

        assertTrue(
            PluginRenderRecovery.mountedPlugins().contains("plugin.a"),
            "one unregister must release exactly one count",
        )
    }

    @Test
    fun `quarantine does not close the plugin's tab`() {
        // The whole reason recordRenderFault exists: quarantine is a positional
        // guess, and a guess must never destroy a live session. Mass quarantine
        // through recordCrash closed the user's terminal and browser tabs.
        var tabClosed = false
        PluginCrashRegistry.registerActiveTab("plugin.a", "tab-1") { tabClosed = true }
        try {
            PluginRenderRecovery.registerMounted("plugin.a")
            PluginRenderRecovery.onUnattributedRenderException(error, now = 1_000)
            val outcome = PluginRenderRecovery.onUnattributedRenderException(error, now = 2_000)

            assertIs<PluginRenderRecovery.Outcome.Quarantined>(outcome)
            // Drain the EDT first. recordCrash's destructive branch closes the tab
            // from inside invokeLater, so without this the assertion below wins a
            // race instead of checking a behaviour — it would pass against the old
            // recordCrash too. Verified: with recordCrash restored, this goes red.
            javax.swing.SwingUtilities.invokeAndWait { }
            assertTrue(PluginCrashRegistry.hasCrashed("plugin.a"), "the fallback must still be shown")
            assertTrue(!tabClosed, "quarantining a suspect must not close its tab")
        } finally {
            PluginCrashRegistry.unregisterActiveTab("plugin.a", "tab-1")
        }
    }

    @Test
    fun `quarantine does not fire the generic crash notification`() {
        // The registry's "Plugin X crashed" goes out through invokeLater, while the
        // caller's tailored "Paused X — restart it from the panel menu" is posted
        // straight from the EDT. StatusMessageManager holds one message, so the
        // generic one landed second and overwrote the useful one. Suppressing it
        // here is what lets the tailored wording survive.
        val notified = mutableListOf<String>()
        val previous = PluginCrashRegistry.onCrashNotify
        PluginCrashRegistry.onCrashNotify = { pluginId, _ -> notified += pluginId }
        try {
            PluginRenderRecovery.registerMounted("plugin.a")
            PluginRenderRecovery.onUnattributedRenderException(error, now = 1_000)
            val outcome = PluginRenderRecovery.onUnattributedRenderException(error, now = 2_000)
            assertIs<PluginRenderRecovery.Outcome.Quarantined>(outcome)
            javax.swing.SwingUtilities.invokeAndWait { }

            assertTrue(
                notified.isEmpty(),
                "quarantine must leave the messaging to its caller, saw $notified",
            )
        } finally {
            PluginCrashRegistry.onCrashNotify = previous
        }
    }

    @Test
    fun `a plugin already showing its own crash is never suspected`() {
        // Releasing a suspect clears its registry entry, so quarantining a plugin
        // that was already broken would wipe its genuine crash state and put a
        // known-broken plugin back on screen. It also cannot be the culprit: it is
        // rendering its fallback, not plugin content.
        PluginRenderRecovery.registerMounted("plugin.a")
        PluginRenderRecovery.registerMounted("plugin.b")
        PluginCrashRegistry.recordRenderFault("plugin.b", RuntimeException("its own bug"), notify = false)
        javax.swing.SwingUtilities.invokeAndWait { }

        PluginRenderRecovery.onUnattributedRenderException(error, now = 1_000)
        val outcome = PluginRenderRecovery.onUnattributedRenderException(error, now = 2_000)

        assertIs<PluginRenderRecovery.Outcome.Quarantined>(outcome)
        assertEquals(
            setOf("plugin.a"),
            outcome.plugins,
            "the already-crashed plugin must be skipped as a suspect",
        )
    }

    @Test
    fun `once an incident is resolved the next fault starts a fresh cycle`() {
        // Guards against a restarted plugin being instantly re-suspected: the
        // bookkeeping from a finished incident must not carry into the next one.
        PluginRenderRecovery.registerMounted("plugin.a")
        PluginRenderRecovery.onUnattributedRenderException(error, now = 1_000)
        PluginRenderRecovery.onUnattributedRenderException(error, now = 2_000) // suspects a
        PluginRenderRecovery.onUnattributedRenderException(error, now = 3_000) // rules a out
        PluginCrashRegistry.clearCrash("plugin.a")

        // Much later, something fails again. That is a new incident.
        val outcome = PluginRenderRecovery.onUnattributedRenderException(error, now = 60_000)

        assertIs<PluginRenderRecovery.Outcome.Rebuilt>(
            outcome,
            "a later fault must get its own rebuild, not inherit the last incident's verdict",
        )
        assertTrue(!PluginCrashRegistry.hasCrashed("plugin.a"))
    }
}
