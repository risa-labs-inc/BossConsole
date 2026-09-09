package ai.rever.boss.keymap

import ai.rever.boss.keymap.model.canonicalKeyName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Every spelling `canonicalKeyName` has to accept, enumerated.
 *
 * This fold replaced three separate tables - the AWT interceptor's, the Compose matcher's, and
 * the implicit one in `KeyStroke.signature` - and unifying them dropped two entries, which CI
 * caught only because the bracket chords happen to be covered end to end. The names Compose
 * renders are PLATFORM-DEPENDENT (the bracket keys are "Left Bracket"/"Right Bracket" on some and
 * "Open Bracket"/"Close Bracket" on others), so a macOS-only run cannot see a missing alias.
 * Listing them here means a dropped spelling fails everywhere, at the source.
 */
class CanonicalKeyNameTest {
    private fun assertAllSame(vararg spellings: String) {
        val canonical = spellings.map { canonicalKeyName(it) }.toSet()
        assertEquals(1, canonical.size, "these should be one key: ${spellings.toList()} -> $canonical")
    }

    @Test
    fun `bracket spellings, including the ones Compose uses per platform`() {
        assertAllSame("OpenBracket", "openbracket", "Open Bracket", "Left Bracket", "LeftBracket", "[")
        assertAllSame("CloseBracket", "closebracket", "Close Bracket", "Right Bracket", "RightBracket", "]")
        assertNotEquals(canonicalKeyName("OpenBracket"), canonicalKeyName("CloseBracket"))
    }

    @Test
    fun `arrow spellings across Compose, AWT and older keymap files`() {
        assertAllSame("DirectionLeft", "Left", "ArrowLeft", "←")
        assertAllSame("DirectionRight", "Right", "ArrowRight", "→")
        assertAllSame("DirectionUp", "Up", "ArrowUp", "↑")
        assertAllSame("DirectionDown", "Down", "ArrowDown", "↓")
        assertNotEquals(canonicalKeyName("DirectionLeft"), canonicalKeyName("DirectionRight"))
    }

    @Test
    fun `symbol characters and their word forms`() {
        assertAllSame("Minus", "minus", "-")
        // A dedicated + key folds onto Equals: zoom in is stored as Equals with a Shift alternate.
        assertAllSame("Equals", "equals", "=", "Plus", "plus", "+")
        assertAllSame("Slash", "slash", "/", "?")
        assertAllSame("Backslash", "backslash", "\\")
        assertAllSame("Semicolon", ";")
        assertAllSame("Apostrophe", "'")
        assertAllSame("Comma", ",")
        assertAllSame("Period", ".")
        assertAllSame("Grave", "`")
    }

    @Test
    fun `digits match their word forms`() {
        listOf("Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine")
            .forEachIndexed { digit, word -> assertAllSame(word, digit.toString()) }
        assertNotEquals(canonicalKeyName("One"), canonicalKeyName("Two"))
    }

    @Test
    fun `named keys with more than one spelling`() {
        assertAllSame("Space", "Spacebar", "␣", " ")
        assertAllSame("Escape", "Esc")
        assertAllSame("Enter", "Return")
    }

    @Test
    fun `an unknown name canonicalises to itself, case-folded`() {
        // What makes the presets' own vocabulary the default answer rather than a special case.
        assertEquals(canonicalKeyName("F7"), canonicalKeyName("f7"))
        assertNotEquals(canonicalKeyName("F7"), canonicalKeyName("F8"))
        assertEquals(canonicalKeyName("Tab"), canonicalKeyName("tab"))
    }
}
