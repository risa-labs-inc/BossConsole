package ai.rever.boss.components.window_panel

import ai.rever.boss.components.window_panel.components.main_window_panels.stripShownFor
import ai.rever.boss.window.WindowAppearanceSettings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins when a pane draws its favicon strip.
 *
 * The strip was split-only, on the reasoning that a single pane's tabs are already listed by name
 * in the sidebar. That holds only while the sidebar is EXPANDED, and it collapses to the rail on
 * its own when a panel is narrow, so a one-pane window could reach a state with no tab titles
 * anywhere. The pane count is a preference now rather than a rule, and the default changed with
 * it - which is exactly the kind of thing worth pinning, because both the old and the new
 * behaviour look reasonable in a screenshot.
 */
class PaneTabStripVisibilityTest {
    private val defaults = WindowAppearanceSettings()

    @Test
    fun `by default an unsplit window shows the strip`() {
        assertTrue(defaults.stripShownFor(1), "this is the default that changed")
        assertTrue(defaults.stripShownFor(2))
    }

    @Test
    fun `the setting brings the old split-only behaviour back`() {
        val onlyWhenSplit = defaults.copy(paneTabStripOnlyWhenSplit = true)

        assertFalse(onlyWhenSplit.stripShownFor(1))
        assertTrue(onlyWhenSplit.stripShownFor(2))
        assertTrue(onlyWhenSplit.stripShownFor(4))
    }

    @Test
    fun `switching the strip off beats the pane count either way`() {
        // The second row only qualifies the first. If it could override it, a user who turned the
        // strip off would get it back by splitting.
        val off = defaults.copy(showPaneTabStrip = false)

        assertFalse(off.stripShownFor(1))
        assertFalse(off.stripShownFor(4))
        assertFalse(off.copy(paneTabStripOnlyWhenSplit = true).stripShownFor(4))
    }

    @Test
    fun `an unmeasured window counts as one pane`() {
        // splitViewState is nullable at the call site, and null there means there is no split
        // view at all - which is one pane, not zero and not "unknown".
        assertTrue(defaults.stripShownFor(null))
        assertFalse(defaults.copy(paneTabStripOnlyWhenSplit = true).stripShownFor(null))
    }
}
