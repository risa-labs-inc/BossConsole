package ai.rever.boss.window

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [AWTKeyboardInterceptor.keyNameMatches].
 *
 * The interceptor compares an AWT keycode's name against the `key` string stored in the keymap.
 * Those two vocabularies drifted: the presets use Compose's `Key.DirectionLeft` naming while the
 * interceptor emitted `"Left"`, so no arrow binding ever matched on this path - Cmd+Arrow panel
 * navigation survived only on the native menu accelerator and went dead whenever a terminal or
 * browser held focus. Cmd+Opt+Arrow tab stepping has no menu fallback at all, so it depends on
 * this agreeing.
 */
class AWTKeyNameMatchingTest {
    @Test
    fun `arrow spellings are interchangeable`() {
        // What getKeyName now emits, against what the presets store.
        assertTrue(AWTKeyboardInterceptor.keyNameMatches("DirectionLeft", "DirectionLeft"))
        assertTrue(AWTKeyboardInterceptor.keyNameMatches("DirectionRight", "DirectionRight"))

        // A keymap file written by an older build, or hand-edited, still matches.
        assertTrue(AWTKeyboardInterceptor.keyNameMatches("Left", "DirectionLeft"))
        assertTrue(AWTKeyboardInterceptor.keyNameMatches("ArrowUp", "DirectionUp"))
        assertTrue(AWTKeyboardInterceptor.keyNameMatches("Down", "DirectionDown"))
    }

    @Test
    fun `common aliases are interchangeable`() {
        assertTrue(AWTKeyboardInterceptor.keyNameMatches("Space", "Spacebar"))
        assertTrue(AWTKeyboardInterceptor.keyNameMatches("Esc", "Escape"))
        assertTrue(AWTKeyboardInterceptor.keyNameMatches("Return", "Enter"))
    }

    @Test
    fun `comparison is case insensitive`() {
        assertTrue(AWTKeyboardInterceptor.keyNameMatches("t", "T"))
        assertTrue(AWTKeyboardInterceptor.keyNameMatches("closebracket", "CloseBracket"))
    }

    @Test
    fun `distinct keys stay distinct`() {
        assertFalse(AWTKeyboardInterceptor.keyNameMatches("DirectionLeft", "DirectionRight"))
        assertFalse(AWTKeyboardInterceptor.keyNameMatches("OpenBracket", "CloseBracket"))
        assertFalse(AWTKeyboardInterceptor.keyNameMatches("Equals", "Minus"))
        assertFalse(AWTKeyboardInterceptor.keyNameMatches("Nine", "Eight"))
    }
}
