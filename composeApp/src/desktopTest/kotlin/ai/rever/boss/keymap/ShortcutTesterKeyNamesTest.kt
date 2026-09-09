package ai.rever.boss.keymap

import ai.rever.boss.components.settings.keymap.ShortcutTestRunner
import ai.rever.boss.keymap.model.KNOWN_KEY_NAMES
import ai.rever.boss.keymap.model.canonicalKeyName
import ai.rever.boss.keymap.presets.KeymapPresets
import ai.rever.boss.window.AWTKeyboardInterceptor
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.awt.event.KeyEvent as AwtKeyEvent

/**
 * What the Shortcuts screen's tester says about a key, against the keys BOSS can actually
 * dispatch.
 *
 * The tester kept its own hand-written list of "valid" key names - the fourth copy of the key
 * vocabulary in the codebase, and the only one nothing tested. It had drifted: no function key
 * was on it, nor Home, End, PageUp or PageDown, nor the character spellings `canonicalKeyName`
 * has always folded. So "Test all shortcuts" reported fifteen working shortcuts as broken, with
 * "Unknown key name 'F5' - won't match user input" on a binding that fires.
 */
class ShortcutTesterKeyNamesTest {
    /**
     * The key codes whose names are checked against the tester's vocabulary.
     *
     * The NAMES are read from `AWTKeyboardInterceptor.getKeyName`, so no spelling is restated
     * here. The CODES are a hand-written list, and that is this test's one blind spot: a key
     * added to the interceptor's `when` and not to this list is checked by nothing. Reading the
     * `when` itself would need reflection over a private table, so the honest thing is to say so
     * rather than to claim a guarantee that is not there.
     */
    private val dispatchableKeyCodes: List<Int> =
        buildList {
            addAll((AwtKeyEvent.VK_A..AwtKeyEvent.VK_Z).toList())
            addAll((AwtKeyEvent.VK_0..AwtKeyEvent.VK_9).toList())
            addAll((AwtKeyEvent.VK_F1..AwtKeyEvent.VK_F12).toList())
            addAll(
                listOf(
                    AwtKeyEvent.VK_ENTER,
                    AwtKeyEvent.VK_ESCAPE,
                    AwtKeyEvent.VK_SPACE,
                    AwtKeyEvent.VK_TAB,
                    AwtKeyEvent.VK_BACK_SPACE,
                    AwtKeyEvent.VK_DELETE,
                    AwtKeyEvent.VK_LEFT,
                    AwtKeyEvent.VK_RIGHT,
                    AwtKeyEvent.VK_UP,
                    AwtKeyEvent.VK_DOWN,
                    AwtKeyEvent.VK_HOME,
                    AwtKeyEvent.VK_END,
                    AwtKeyEvent.VK_PAGE_UP,
                    AwtKeyEvent.VK_PAGE_DOWN,
                    AwtKeyEvent.VK_MINUS,
                    AwtKeyEvent.VK_EQUALS,
                    AwtKeyEvent.VK_PLUS,
                    AwtKeyEvent.VK_OPEN_BRACKET,
                    AwtKeyEvent.VK_CLOSE_BRACKET,
                    AwtKeyEvent.VK_SLASH,
                    AwtKeyEvent.VK_BACK_SLASH,
                    AwtKeyEvent.VK_SEMICOLON,
                    AwtKeyEvent.VK_QUOTE,
                    AwtKeyEvent.VK_COMMA,
                    AwtKeyEvent.VK_PERIOD,
                    AwtKeyEvent.VK_BACK_QUOTE,
                ),
            )
        }

    @Test
    fun `every key the host can dispatch is recognised by the tester`() {
        val rejected =
            dispatchableKeyCodes
                .map { AWTKeyboardInterceptor.getKeyName(it) }
                .distinct()
                .mapNotNull { name -> ShortcutTestRunner.unrecognisedKeyNote(name)?.let { "  $name: $it" } }

        assertTrue(
            rejected.isEmpty(),
            "the Shortcuts tester calls these unrecognised, but the interceptor dispatches them:\n" +
                rejected.joinToString("\n"),
        )
    }

    @Test
    fun `the known set claims nothing the host cannot dispatch`() {
        // The other direction. Without it the set could be padded into passing the test above
        // while telling a user a key exists that nothing can produce.
        val dispatchable =
            dispatchableKeyCodes
                .map { canonicalKeyName(AWTKeyboardInterceptor.getKeyName(it)) }
                .toSet()

        val phantom = KNOWN_KEY_NAMES - dispatchable
        assertTrue(phantom.isEmpty(), "these are claimed as known keys but nothing dispatches them: $phantom")
    }

    @Test
    fun `the spellings a keymap can legitimately hold are recognised`() {
        // A keymap file is hand-editable and is written by three different producers, so one key
        // arrives under several names. Each of these was a false failure.
        listOf(
            // Character forms, folded onto their word forms since canonicalKeyName existed.
            "-",
            "=",
            "/",
            "\\",
            ";",
            "'",
            ",",
            ".",
            "`",
            "[",
            "]",
            // Compose's cold spellings.
            "Left Bracket",
            "Right Bracket",
            "Back Slash",
            "Quote",
            "Back Quote",
            "Page Up",
            "Page Down",
            // Compose's glyphs, which is what a running app renders.
            "⇥",
            "⏎",
            "⎋",
            "⌫",
            "⌦",
            "↖",
            "↘",
            "⇞",
            "⇟",
            "←",
            "→",
            "↑",
            "↓",
            "␣",
            // Older and alternate word forms.
            "Left",
            "Right",
            "Up",
            "Down",
            "Esc",
            "Return",
            "Spacebar",
            "ArrowLeft",
        ).forEach { spelling ->
            assertNull(
                ShortcutTestRunner.unrecognisedKeyNote(spelling),
                "'$spelling' names a real key and should not be called unrecognised",
            )
        }
    }

    @Test
    fun `every shipped preset binding passes the tester`() {
        val presets =
            listOf(
                KeymapPresets.getBOSSDefault(),
                KeymapPresets.getVSCodePreset(),
                KeymapPresets.getIntelliJPreset(),
                KeymapPresets.getEmacsPreset(),
            )

        val complaints =
            presets
                .flatMap { it.shortcuts.values }
                .flatMap { binding -> binding.allKeystrokes.map { binding.actionId to it.key } }
                .distinct()
                .mapNotNull { (actionId, key) ->
                    ShortcutTestRunner.unrecognisedKeyNote(key)?.let { "  $actionId: $it" }
                }

        assertTrue(
            complaints.isEmpty(),
            "the tester does not recognise keys BOSS ships bindings for:\n" + complaints.joinToString("\n"),
        )
    }

    @Test
    fun `only unresolved numeric keys fail validation`() {
        assertTrue(ShortcutTestRunner.looksLikePackedKeyCode("999999999999999999999999"))
        listOf("4294967333", "281474976710721").forEach { stored ->
            assertTrue(!ShortcutTestRunner.looksLikePackedKeyCode(stored))
        }
    }

    @Test
    fun `a real key name is never mistaken for a raw key code`() {
        // The guard is "two or more characters, all digits", which has to leave the single-digit
        // spellings alone: "1" is the One key, and failing it would be the false failure this PR
        // exists to remove, reintroduced by the fix for it.
        (listOf("1", "9", "0", "F5", "F12", "Tab", "Home", "-", "=") + KNOWN_KEY_NAMES).forEach { name ->
            assertTrue(
                !ShortcutTestRunner.looksLikePackedKeyCode(name),
                "'$name' names a real key and must not be reported as a raw key code",
            )
        }
    }

    @Test
    fun `a name nothing can produce is still called out`() {
        // The note has to keep some teeth, or removing the false failures would have removed the
        // check with them.
        assertTrue(ShortcutTestRunner.unrecognisedKeyNote("Ctrl-ish") != null)
        assertTrue(ShortcutTestRunner.unrecognisedKeyNote("") != null)
    }

    @Test
    fun `presets and the known set agree on what a key is called`() {
        // Guards the direction neither test above covers: a preset spelling that folds to
        // something outside the vocabulary would pass `every shipped preset binding` only if the
        // set had been widened to admit it.
        val presetKeys =
            KeymapPresets
                .getBOSSDefault()
                .shortcuts.values
                .flatMap { it.allKeystrokes }
                .map { canonicalKeyName(it.key) }
                .toSet()

        assertTrue(
            presetKeys.all { it in KNOWN_KEY_NAMES },
            "a preset binds a key the vocabulary does not list: ${presetKeys - KNOWN_KEY_NAMES}",
        )
    }
}
