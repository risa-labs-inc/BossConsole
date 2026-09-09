package ai.rever.boss.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [SettingsWindowState.reveal] and the highlight's lifetime.
 *
 * All of it is plain logic that a composable then reads, and every failure here looks like a UI
 * glitch rather than a state bug: a row lighting up on a page nobody asked about, or a pick that
 * visibly does nothing because the value it wrote equalled the one already there.
 */
class SettingsWindowRevealTest {
    @Test
    fun `revealing a row navigates and arms the highlight`() {
        val state = SettingsWindowState()

        state.reveal(section = "APPEARANCE", group = "Tab Bar", label = "Show Title Bar", highlightable = true)

        assertEquals("APPEARANCE", state.section)
        assertTrue(state.visible)
        assertEquals("Show Title Bar", state.highlight?.label)
        assertEquals("Tab Bar", state.highlight?.group)
    }

    @Test
    fun `the same row twice bumps the nonce`() {
        // The whole reason the nonce exists: the value is otherwise unchanged, so the window's
        // keyed effect would not re-run and picking a row a second time would do nothing at all.
        val state = SettingsWindowState()

        state.reveal(section = "APPEARANCE", group = null, label = "Show Title Bar", highlightable = true)
        val first = state.highlight
        state.reveal(section = "APPEARANCE", group = null, label = "Show Title Bar", highlightable = true)

        assertNotEquals(first, state.highlight, "a repeat must be a new value or the effect will not re-run")
    }

    @Test
    fun `an entry that cannot be highlighted clears the last one rather than leaving it armed`() {
        // A plugin page or a control with no search target can only reach its section. Pointing at
        // nothing is the honest outcome; keeping the previous pick would light a row on a page it
        // does not belong to.
        val state = SettingsWindowState()
        state.reveal(section = "APPEARANCE", group = null, label = "Show Title Bar", highlightable = true)

        state.reveal(section = "ai-gateway", group = null, label = "AI Gateway", highlightable = false)

        assertEquals("ai-gateway", state.section)
        assertNull(state.highlight)
    }

    @Test
    fun `closing the window disarms the highlight`() {
        // SettingsContent composes fresh each time `visible` flips, so a highlight left here fires
        // on that first composition - long after the pick it belonged to. The nonce cannot help:
        // it only distinguishes repeats within one composition's lifetime.
        val state = SettingsWindowState()
        state.reveal(section = "APPEARANCE", group = null, label = "Show Title Bar", highlightable = true)

        state.close()

        assertNull(state.highlight, "reopening Settings must not re-light the last row picked")
        assertNull(state.section)
    }

    @Test
    fun `opening settings again disarms a highlight the last pick consumed`() {
        // The other door onto the wrong-page highlight. Reveal a row, then open Settings from
        // somewhere else naming a different section: the window navigates, and the highlight from
        // the earlier pick must not still be pointing at a row on the page it left. The window's
        // effect is keyed on the nonce, so nothing downstream would ever correct it.
        val state = SettingsWindowState()
        state.reveal(section = "THEME", group = "App Theme", label = "Accent", highlightable = true)

        state.open(section = "KEYMAP")

        assertEquals("KEYMAP", state.section)
        assertNull(state.highlight, "a consumed highlight must not survive a later navigation")
    }

    @Test
    fun `reveal still arms the highlight even though open clears it`() {
        // reveal() calls open() and then sets the highlight; reversing that order would silently
        // lose every highlight, and every other test here would still pass.
        val state = SettingsWindowState()

        state.reveal(section = "THEME", group = "App Theme", label = "Accent", highlightable = true)

        assertEquals("Accent", state.highlight?.label)
    }

    @Test
    fun `every request bumps the counter, including one that points at nothing`() {
        // The window keys its adopt-the-highlight effect on this, because null carries no nonce:
        // without a counter, reveal-to-null after a local pick was `null -> null` and looked like
        // nothing happening, leaving the window's own highlight armed on a page it had left.
        val state = SettingsWindowState()
        val start = state.highlightRequest

        state.reveal(section = "THEME", group = null, label = "Accent", highlightable = true)
        val afterHighlightable = state.highlightRequest
        state.reveal(section = "KEYMAP", group = null, label = "Catch All", highlightable = false)
        val afterNotHighlightable = state.highlightRequest

        assertTrue(afterHighlightable > start, "a reveal that arms a highlight is a request")
        assertTrue(afterNotHighlightable > afterHighlightable, "a reveal that clears one is too")
        assertNull(state.highlight)
    }

    @Test
    fun `a plain open is a request as well, so the window can drop a consumed highlight`() {
        // sectionRequest would miss this: it only moves when a section is named, and the top-bar
        // Settings button names none.
        val state = SettingsWindowState()
        state.reveal(section = "THEME", group = null, label = "Accent", highlightable = true)
        val armed = state.highlightRequest

        state.open()

        assertTrue(state.highlightRequest > armed)
        assertNull(state.highlight)
    }

    @Test
    fun `a plain open leaves an unrelated section alone and only raises the window`() {
        // The bug the whole holder exists for: assigning `true` to an already-true flag changed
        // nothing, and Settings read as a dead button. open() with no section must bump the
        // counter, not clear where a deep link went.
        val state = SettingsWindowState()
        state.open(section = "APPEARANCE")
        val raisedOnce = state.focusRequest

        state.open()

        assertEquals("APPEARANCE", state.section)
        assertEquals(raisedOnce + 1, state.focusRequest)
    }
}
