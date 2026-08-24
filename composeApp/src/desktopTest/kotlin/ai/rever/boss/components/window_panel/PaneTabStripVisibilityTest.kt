package ai.rever.boss.components.window_panel

import ai.rever.boss.components.window_panel.components.main_window_panels.stripShownFor
import ai.rever.boss.window.WindowAppearanceSettings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins when a pane draws its favicon strip.
 *
 * The strip is split-only, on the reasoning that a single pane's tabs are already listed by name
 * in the sidebar. That holds only while the sidebar is EXPANDED, and it collapses to the rail on
 * its own when a panel is narrow, so a one-pane window can reach a state with no tab titles
 * anywhere - which is why the pane count is a preference rather than a rule.
 *
 * The DEFAULT is the load-bearing part. Leaving it split-only is what makes the setting purely
 * additive: no install changes behaviour, and nothing needs migrating. Flipping it would be a
 * silent behaviour change for everyone, and both settings look perfectly reasonable in a
 * screenshot, so it is pinned here rather than left to be noticed.
 */
class PaneTabStripVisibilityTest {
    private val defaults = WindowAppearanceSettings()

    @Test
    fun `by default the strip needs a split`() {
        // Purely additive: this is what every build before the setting existed did, so an
        // upgrade changes nothing on its own.
        assertTrue(defaults.paneTabStripOnlyWhenSplit, "the default must stay split-only")
        assertFalse(defaults.stripShownFor(1))
        assertTrue(defaults.stripShownFor(2))
        assertTrue(defaults.stripShownFor(4))
    }

    @Test
    fun `switching it off puts the strip in an unsplit window too`() {
        val always = defaults.copy(paneTabStripOnlyWhenSplit = false)

        assertTrue(always.stripShownFor(1))
        assertTrue(always.stripShownFor(2))
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
        assertFalse(defaults.stripShownFor(null))
        assertTrue(defaults.copy(paneTabStripOnlyWhenSplit = false).stripShownFor(null))
    }
}
