package ai.rever.boss.keymap

import ai.rever.boss.keymap.model.canonicalKeyName
import ai.rever.boss.keymap.model.composeKeyName
import ai.rever.boss.window.AWTKeyboardInterceptor
import androidx.compose.ui.input.key.Key
import org.junit.jupiter.api.Assumptions.assumeFalse
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import kotlin.test.Test
import kotlin.test.assertTrue
import java.awt.event.KeyEvent as AwtKeyEvent

/**
 * The two key-name vocabularies BOSS maintains, held against each other key by key.
 *
 * `AWTKeyboardInterceptor.getKeyName` names a physical key one way; Compose's `Key.toString()`,
 * which is where the Shortcuts screen and `KeymapMatcher` both get their name, names it another.
 * Neither list is derived from the other, and where they disagree the result is always the same
 * shape: a chord that fires on one path and silently does nothing on the other, with no conflict
 * badge and no error to explain it.
 *
 * Its input is whatever this environment renders, which is deliberate - CI runs it on three
 * platforms and `Key.toString()` answers differently on each, and differently again depending on
 * whether the AWT toolkit has been initialised yet (a word while cold, a macOS glyph once up).
 * So it is a canary over the real vocabulary rather than a fixed list, and `CanonicalKeyNameTest`
 * carries the enumerated spellings that a single run cannot be trusted to reach.
 *
 * They had fourteen spelling divergences over twelve keys when this was written - the nine macOS glyphs a running app
 * renders (Enter, Escape, Tab, Backspace, Delete, Home, End, PageUp, PageDown) and five cold
 * spellings (`Back Slash`, `Quote`, `Back Quote`, `Page Up`, `Page Down`) - on top of the
 * "Left"/"DirectionLeft" and bracket-pair gaps found before it. The glyphs were the reachable
 * half: Ctrl+Tab and Ctrl+Shift+Tab are shipped in all four presets and asked this matcher
 * whether "⇥" was "Tab".
 *
 * `canonicalKeyName` is the fold that reconciles them, so what this asserts is that the fold
 * covers the keyboard rather than the handful of keys someone happened to notice.
 */
class KeyVocabularyAgreementTest {
    /**
     * One physical key, named from both sides.
     *
     * The pairing is the only thing that has to be hand-written - it is the identity of the key
     * itself, not either vocabulary - and everything else is read out of the two tables under
     * test. Modifier-only keys are left out: the interceptor gates on them rather than binding
     * them, so they never reach a keymap entry.
     */
    private val keyboard: List<Pair<Key, Int>> =
        listOf(
            Key.A to AwtKeyEvent.VK_A,
            Key.B to AwtKeyEvent.VK_B,
            Key.C to AwtKeyEvent.VK_C,
            Key.D to AwtKeyEvent.VK_D,
            Key.E to AwtKeyEvent.VK_E,
            Key.F to AwtKeyEvent.VK_F,
            Key.G to AwtKeyEvent.VK_G,
            Key.H to AwtKeyEvent.VK_H,
            Key.I to AwtKeyEvent.VK_I,
            Key.J to AwtKeyEvent.VK_J,
            Key.K to AwtKeyEvent.VK_K,
            Key.L to AwtKeyEvent.VK_L,
            Key.M to AwtKeyEvent.VK_M,
            Key.N to AwtKeyEvent.VK_N,
            Key.O to AwtKeyEvent.VK_O,
            Key.P to AwtKeyEvent.VK_P,
            Key.Q to AwtKeyEvent.VK_Q,
            Key.R to AwtKeyEvent.VK_R,
            Key.S to AwtKeyEvent.VK_S,
            Key.T to AwtKeyEvent.VK_T,
            Key.U to AwtKeyEvent.VK_U,
            Key.V to AwtKeyEvent.VK_V,
            Key.W to AwtKeyEvent.VK_W,
            Key.X to AwtKeyEvent.VK_X,
            Key.Y to AwtKeyEvent.VK_Y,
            Key.Z to AwtKeyEvent.VK_Z,
            Key.Zero to AwtKeyEvent.VK_0,
            Key.One to AwtKeyEvent.VK_1,
            Key.Two to AwtKeyEvent.VK_2,
            Key.Three to AwtKeyEvent.VK_3,
            Key.Four to AwtKeyEvent.VK_4,
            Key.Five to AwtKeyEvent.VK_5,
            Key.Six to AwtKeyEvent.VK_6,
            Key.Seven to AwtKeyEvent.VK_7,
            Key.Eight to AwtKeyEvent.VK_8,
            Key.Nine to AwtKeyEvent.VK_9,
            Key.F1 to AwtKeyEvent.VK_F1,
            Key.F2 to AwtKeyEvent.VK_F2,
            Key.F3 to AwtKeyEvent.VK_F3,
            Key.F4 to AwtKeyEvent.VK_F4,
            Key.F5 to AwtKeyEvent.VK_F5,
            Key.F6 to AwtKeyEvent.VK_F6,
            Key.F7 to AwtKeyEvent.VK_F7,
            Key.F8 to AwtKeyEvent.VK_F8,
            Key.F9 to AwtKeyEvent.VK_F9,
            Key.F10 to AwtKeyEvent.VK_F10,
            Key.F11 to AwtKeyEvent.VK_F11,
            Key.F12 to AwtKeyEvent.VK_F12,
            Key.Enter to AwtKeyEvent.VK_ENTER,
            Key.Escape to AwtKeyEvent.VK_ESCAPE,
            Key.Spacebar to AwtKeyEvent.VK_SPACE,
            Key.Tab to AwtKeyEvent.VK_TAB,
            Key.Backspace to AwtKeyEvent.VK_BACK_SPACE,
            Key.Delete to AwtKeyEvent.VK_DELETE,
            Key.DirectionLeft to AwtKeyEvent.VK_LEFT,
            Key.DirectionRight to AwtKeyEvent.VK_RIGHT,
            Key.DirectionUp to AwtKeyEvent.VK_UP,
            Key.DirectionDown to AwtKeyEvent.VK_DOWN,
            Key.MoveHome to AwtKeyEvent.VK_HOME,
            Key.MoveEnd to AwtKeyEvent.VK_END,
            Key.PageUp to AwtKeyEvent.VK_PAGE_UP,
            Key.PageDown to AwtKeyEvent.VK_PAGE_DOWN,
            Key.Minus to AwtKeyEvent.VK_MINUS,
            Key.Equals to AwtKeyEvent.VK_EQUALS,
            Key.LeftBracket to AwtKeyEvent.VK_OPEN_BRACKET,
            Key.RightBracket to AwtKeyEvent.VK_CLOSE_BRACKET,
            Key.Slash to AwtKeyEvent.VK_SLASH,
            Key.Backslash to AwtKeyEvent.VK_BACK_SLASH,
            Key.Semicolon to AwtKeyEvent.VK_SEMICOLON,
            Key.Apostrophe to AwtKeyEvent.VK_QUOTE,
            Key.Comma to AwtKeyEvent.VK_COMMA,
            Key.Period to AwtKeyEvent.VK_PERIOD,
            Key.Grave to AwtKeyEvent.VK_BACK_QUOTE,
        )

    @Test
    fun `both paths name the same physical key the same key`() {
        val disagreements =
            keyboard.mapNotNull { (key, awtKeyCode) ->
                val fromCompose = composeKeyName(key)
                val fromAwt = AWTKeyboardInterceptor.getKeyName(awtKeyCode)
                "  $key: Compose says '$fromCompose', AWT says '$fromAwt'"
                    .takeIf { canonicalKeyName(fromCompose) != canonicalKeyName(fromAwt) }
            }

        assertTrue(
            disagreements.isEmpty(),
            "these keys resolve on one path and not the other, which is a chord that silently " +
                "does nothing wherever the other path is the one listening:\n" +
                disagreements.joinToString("\n"),
        )
    }

    @Test
    fun `both paths agree after the platform toolkit installs its key names`() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        Toolkit.getDefaultToolkit()
        `both paths name the same physical key the same key`()
    }

    @Test
    fun `distinct keys stay distinct through the fold`() {
        // The other half of the same contract, and the cheaper mistake to make: an alias table
        // reconciles spellings by collapsing them, and one entry in the wrong row would silently
        // make two keys the same chord. Asserting only agreement above would pass on a fold that
        // answered the same thing for everything.
        val collisions =
            keyboard
                .groupBy { (key, _) -> canonicalKeyName(composeKeyName(key)) }
                .filterValues { it.size > 1 }

        assertTrue(
            collisions.isEmpty(),
            "the fold collapsed keys that are not the same key: " +
                collisions.map { (canonical, keys) -> "$canonical <- ${keys.map { it.first }}" },
        )
    }
}
