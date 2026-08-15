package ai.rever.boss.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [SettingsWindowState], whose whole reason for existing is a bug that is invisible in code
 * review: every Settings affordance assigned `showSettingsDialog = true`, and assigning `true` to
 * a flag that is already `true` is a no-op. With the settings window open behind the main one,
 * clicking Settings did nothing whatsoever.
 *
 * The reason it needs a test rather than just a method is that the broken version and the fixed
 * version are indistinguishable in the only case anyone tries by hand - settings closed, click
 * once, window appears. Every assertion here is about the second click.
 */
class SettingsWindowStateTest {
    @Test
    fun `the first request shows the window and asks for no raise`() {
        val state = SettingsWindowState()

        state.open()

        assertTrue(state.visible)
        assertEquals(0, state.focusRequest, "a window about to be created does not need raising")
    }

    @Test
    fun `a second request while open asks the window to raise itself`() {
        // The bug, in one assertion. Before this, the second open() was a no-op in every observable
        // way: visible was already true and nothing else moved, so the window stayed buried.
        val state = SettingsWindowState()
        state.open()

        state.open()

        assertTrue(state.visible, "the window must not be torn down and rebuilt")
        assertEquals(1, state.focusRequest)
    }

    @Test
    fun `every request raises, not just the first repeat`() {
        // Monotonic on purpose. If this saturated - a boolean, or a counter that reset - the third
        // click would present the window with a value it had already handled and be swallowed,
        // which is the original bug wearing a different hat.
        val state = SettingsWindowState()
        state.open()

        repeat(3) { state.open() }

        assertEquals(3, state.focusRequest)
    }

    @Test
    fun `a plain request does not clear a section another caller navigated to`() {
        // Shortcut help deep-links to KEYMAP; the quick actions pass nothing. If null overwrote the
        // section, clicking the quick action while settings was open would yank the user off the
        // page they were on, on the very interaction that is only supposed to raise the window.
        val state = SettingsWindowState()
        state.open("KEYMAP")

        state.open()

        assertEquals("KEYMAP", state.section)
        assertEquals(1, state.focusRequest, "it is still a raise request")
    }

    @Test
    fun `a deep link applied while open both navigates and raises`() {
        val state = SettingsWindowState()
        state.open()

        state.open("KEYMAP")

        assertEquals("KEYMAP", state.section)
        assertEquals(1, state.focusRequest)
    }

    @Test
    fun `closing clears the section but never the raise counter`() {
        // The section goes so the next plain open starts at the default rather than re-landing on
        // an old deep link. The counter stays: the window keys an effect on it, and rewinding to a
        // value it has already acted on would make the next request do nothing.
        val state = SettingsWindowState()
        state.open("KEYMAP")
        state.open()

        state.close()

        assertFalse(state.visible)
        assertNull(state.section)
        assertEquals(1, state.focusRequest, "the counter is a signal, not window state")
    }

    @Test
    fun `reopening after a close shows the window rather than raising a dead one`() {
        // The window is gone, so a raise request would be handled by nothing at all and Settings
        // would never come back.
        val state = SettingsWindowState()
        state.open()
        state.close()

        state.open()

        assertTrue(state.visible)
        assertEquals(0, state.focusRequest, "no raise: a new window is being created")
    }
}
