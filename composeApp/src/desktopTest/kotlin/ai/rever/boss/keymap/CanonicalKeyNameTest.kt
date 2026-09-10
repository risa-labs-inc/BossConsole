package ai.rever.boss.keymap

import ai.rever.boss.keymap.model.canonicalKeyName
import ai.rever.boss.keymap.model.composeKeyName
import androidx.compose.ui.input.key.Key
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
    fun `the glyphs Compose renders once the AWT toolkit is up`() {
        // `Key.toString()` falls through to AWT's `getKeyText`, which answers with a word while
        // the toolkit is cold and with the macOS glyph once it is up - so one machine produces
        // both spellings and which one a keymap holds depends on nothing the user did. Enumerated
        // here rather than left to `KeyVocabularyAgreementTest`, whose input is whatever the
        // environment happened to render: a cold run there cannot see a missing glyph.
        assertAllSame("Escape", "Esc", "\u238B")
        assertAllSame("Enter", "Return", "\u23CE")
        assertAllSame("Tab", "\u21E5")
        assertAllSame("Backspace", "\u232B")
        assertAllSame("Delete", "\u2326")
        assertAllSame("Home", "MoveHome", "\u2196")
        assertAllSame("End", "MoveEnd", "\u2198")
        assertAllSame("PageUp", "Page Up", "\u21DE")
        assertAllSame("PageDown", "Page Down", "\u21DF")
        assertAllSame("Space", "Spacebar", "\u2423")
        assertNotEquals(canonicalKeyName("\u21DE"), canonicalKeyName("\u21DF"))
        assertNotEquals(canonicalKeyName("\u232B"), canonicalKeyName("\u2326"))
        assertNotEquals(canonicalKeyName("\u2196"), canonicalKeyName("\u2198"))
    }

    @Test
    fun `the spaced spellings Compose renders`() {
        // `Key.toString()` is where the capture dialog and the Compose matcher both get a name,
        // and it spaces words the AWT interceptor and the presets run together. Every one of
        // these was a chord that resolved on one path and silently did nothing on the other;
        // `KeyVocabularyAgreementTest` is what walks the whole keyboard for the next one.
        assertAllSame("Backslash", "Back Slash", "\\")
        assertAllSame("Apostrophe", "Quote", "'")
        assertAllSame("Grave", "Back Quote", "`")
        assertNotEquals(canonicalKeyName("PageUp"), canonicalKeyName("PageDown"))
        assertNotEquals(canonicalKeyName("Grave"), canonicalKeyName("Apostrophe"))
    }

    @Test
    fun `a packed Key keyCode folds onto the key it stands for`() {
        // What every rebind made in the Shortcuts screen wrote before #329. An unmigrated keymap
        // has to keep matching, so the fold answers for these rather than only the migration.
        listOf(Key.DirectionLeft, Key.Spacebar, Key.Enter, Key.Escape, Key.A, Key.One, Key.Backslash)
            .forEach { key ->
                assertEquals(
                    canonicalKeyName(composeKeyName(key)),
                    canonicalKeyName(key.keyCode.toString()),
                    "a stored keyCode should be the same key as its name: $key",
                )
            }
    }

    @Test
    fun `an unknown native code is not rewritten to an AWT diagnostic`() {
        listOf("4294967295", "4311744511").forEach { unknown ->
            assertEquals(unknown, canonicalKeyName(unknown))
            assertEquals(unknown, canonicalKeyName(canonicalKeyName(unknown)))
        }
    }

    @Test
    fun `an unknown name canonicalises to itself, case-folded`() {
        // What makes the presets' own vocabulary the default answer rather than a special case.
        assertEquals(canonicalKeyName("F7"), canonicalKeyName("f7"))
        assertNotEquals(canonicalKeyName("F7"), canonicalKeyName("F8"))
        assertEquals(canonicalKeyName("Tab"), canonicalKeyName("tab"))
    }
}
