package ai.rever.boss.app

import ai.rever.boss.focusmode.FocusModeSettings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [focusQuickActionsVisible], which was the highest-consequence line in this feature with no
 * test behind it.
 *
 * The `hides(TOP)` half looks redundant next to `!showTopBar` and is not. `showTopBar` is written
 * from a `LaunchedEffect`, so it reads false on the first composition of every window regardless of
 * whether focus mode is enabled - and dropping the conjunct is a native always-on-top window
 * created and disposed on every window open, a corner flash for users who never enable focus mode,
 * and a content-pane read before the pane is showing. None of that is visible to any other test.
 */
class FocusModeVisibilityTest {
    private val focusOnHidingTop = FocusModeSettings(enabled = true, hideTopBar = true)

    @Test
    fun `hidden once focus mode has cleared the top bar`() {
        assertTrue(focusQuickActionsVisible(focusOnHidingTop, showTopBar = false))
    }

    @Test
    fun `stands down while the top bar is revealed`() {
        assertFalse(focusQuickActionsVisible(focusOnHidingTop, showTopBar = true))
    }

    @Test
    fun `never composed when focus mode is off, whatever the reveal state says`() {
        // The launch path: showTopBar is false on the first composition of every window because the
        // effect that turns it back on has not run yet. This is the case the settings half exists
        // for, and the only one where the two conjuncts disagree.
        val off = focusOnHidingTop.copy(enabled = false)

        assertFalse(focusQuickActionsVisible(off, showTopBar = false))
        assertFalse(focusQuickActionsVisible(off, showTopBar = true))
    }

    @Test
    fun `never composed when focus mode is on but keeps the top bar`() {
        // Per-edge settings: focus mode enabled with hideTopBar off leaves the bar in place, so the
        // three actions were never taken away and the cluster has nothing to restore.
        val keepsTop = focusOnHidingTop.copy(hideTopBar = false)

        assertFalse(focusQuickActionsVisible(keepsTop, showTopBar = false))
        assertFalse(focusQuickActionsVisible(keepsTop, showTopBar = true))
    }
}
