package ai.rever.boss.keymap

import ai.rever.boss.keymap.model.KeyStroke
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.model.canonicalKeyName
import ai.rever.boss.keymap.model.storedKeyName
import ai.rever.boss.keymap.presets.KeymapPresets
import ai.rever.boss.window.AWTKeyboardInterceptor
import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the Shortcuts screen writes when a user rebinds an action, against what the two matchers
 * will accept when that user presses the key.
 *
 * `KeyCaptureDialog` stored `Key.keyCode.toString()` - Compose's PACKED code, "4294967333"
 * for the left arrow - while both matchers compare key NAMES. So a rebind saved, listed and then
 * fired on neither path, with no conflict badge and no error to explain it (#329). These are the
 * assertions that would have caught it: the name the dialog produces has to be the same key as
 * the one the presets spell, on both paths, for every key a preset uses.
 */
class KeyCaptureNameTest {
    /**
     * The Compose [Key] a user presses, against the spelling the presets store for it.
     *
     * Hand-written rather than derived, because deriving it from `storedKeyName` is what is
     * under test. [`every key the presets use is covered here`] keeps it honest: a preset that
     * starts using a key absent from this table fails the build rather than shipping a chord
     * nobody can rebind.
     */
    private val presetSpellingByKey: Map<Key, String> =
        mapOf(
            Key.B to "B",
            Key.D to "D",
            Key.E to "E",
            Key.F to "F",
            Key.G to "G",
            Key.H to "H",
            Key.I to "I",
            Key.K to "K",
            Key.L to "L",
            Key.N to "N",
            Key.O to "O",
            Key.P to "P",
            Key.R to "R",
            Key.S to "S",
            Key.T to "T",
            Key.W to "W",
            Key.X to "X",
            Key.Zero to "Zero",
            Key.One to "One",
            Key.Two to "Two",
            Key.Three to "Three",
            Key.Four to "Four",
            Key.Five to "Five",
            Key.Six to "Six",
            Key.Seven to "Seven",
            Key.Eight to "Eight",
            Key.Nine to "Nine",
            Key.Minus to "Minus",
            Key.Equals to "Equals",
            Key.Slash to "Slash",
            Key.Backslash to "Backslash",
            Key.Comma to "Comma",
            Key.Tab to "Tab",
            Key.Spacebar to "Spacebar",
            Key.DirectionLeft to "DirectionLeft",
            Key.DirectionRight to "DirectionRight",
            Key.DirectionUp to "DirectionUp",
            Key.DirectionDown to "DirectionDown",
            Key.LeftBracket to "OpenBracket",
            Key.RightBracket to "CloseBracket",
        )

    /**
     * Run [check] over every key in the table and fail once with all of its complaints.
     *
     * A gap in the name fold is rarely one key - the bracket pair was two, `Back Slash` and
     * `Close Bracket` are the same shape - and failing on the first turns one build into
     * several to learn the shape of the problem.
     */
    private fun assertNoMismatches(
        what: String,
        check: (Key, String) -> String?,
    ) {
        val complaints =
            presetSpellingByKey.mapNotNull { (key, spelling) ->
                check(key, spelling)?.let { "  $key: $it" }
            }
        assertTrue(complaints.isEmpty(), "$what:\n" + complaints.joinToString("\n"))
    }

    private fun shippedPresets(): List<KeymapSettings> =
        listOf(
            KeymapPresets.getBOSSDefault(),
            KeymapPresets.getVSCodePreset(),
            KeymapPresets.getIntelliJPreset(),
            KeymapPresets.getEmacsPreset(),
        )

    @Test
    fun `every key the presets use is covered here`() {
        val spellings =
            shippedPresets()
                .flatMap { it.shortcuts.values }
                .flatMap { it.allKeystrokes }
                .map { it.key }
                .toSet()

        val uncovered = spellings - presetSpellingByKey.values.toSet()
        assertTrue(
            uncovered.isEmpty(),
            "a preset uses a key this test cannot capture, so nothing here proves it is rebindable: $uncovered",
        )
    }

    @Test
    fun `a captured key is stored as a name, never as a packed keyCode`() {
        presetSpellingByKey.keys.forEach { key ->
            val stored = storedKeyName(key)
            assertTrue(
                stored.any { !it.isDigit() },
                "the capture dialog would store a packed keyCode for $key: '$stored'",
            )
        }
    }

    @Test
    fun `a captured key is the same key the presets spell`() {
        assertNoMismatches("a rebind claims a different key than the preset it replaces") { key, presetSpelling ->
            val stored = canonicalKeyName(storedKeyName(key))
            val preset = canonicalKeyName(presetSpelling)
            "stored '${storedKeyName(key)}' folds to '$stored', preset '$presetSpelling' folds to '$preset'"
                .takeIf { stored != preset }
        }
    }

    @Test
    fun `a captured key resolves on the AWT path too`() {
        // The two paths are separate implementations over separate vocabularies - AWT key codes
        // on one side, Compose Key names on the other - so a name that satisfies the Compose
        // matcher proves nothing about the interceptor. Both, or the rebind is still half dead.
        assertNoMismatches("the AWT interceptor does not accept what the dialog stores") { key, presetSpelling ->
            "stored '${storedKeyName(key)}' is not '$presetSpelling' to the interceptor"
                .takeIf { !AWTKeyboardInterceptor.keyNameMatches(storedKeyName(key), presetSpelling) }
        }
    }

    @Test
    fun `a rebind renders exactly like the preset binding it replaces`() {
        // Why the capture folds the name before storing it rather than keeping what Compose
        // renders. `Key.DirectionLeft` renders "Left", `Key.RightBracket` renders "Close
        // Bracket", `Key.One` renders "1" - all three MATCH through the alias table, and all
        // three would have listed as "⌘LEFT", "⌘CLOSE BRACKET" and "⌘1" beside a preset's "⌘←",
        // "⌘]" and "⌘1", because the display formatter knows only the presets' spellings.
        assertNoMismatches("a rebind does not render like the preset binding it replaces") { key, presetSpelling ->
            val rebound = KeyStroke(storedKeyName(key), listOf("Cmd")).displayString()
            val preset = KeyStroke(presetSpelling, listOf("Cmd")).displayString()
            "lists as '$rebound', preset lists as '$preset'".takeIf { rebound != preset }
        }
    }

    @Test
    fun `a keymap still holding a packed keyCode keeps matching`() {
        // The migration rewrites the file, but it rewrites ONE file. A keymap restored from a
        // backup, copied off another machine, or exported and re-imported reaches the matchers
        // first, and the failure being fixed is a rebind that silently does nothing.
        assertNoMismatches("a pre-#329 keymap entry no longer fires") { key, presetSpelling ->
            val stored = canonicalKeyName(key.keyCode.toString())
            "stored keyCode ${key.keyCode} folds to '$stored', not '$presetSpelling'"
                .takeIf { stored != canonicalKeyName(presetSpelling) }
        }
    }

    @Test
    fun `a packed keyCode and its name are one chord, not two`() {
        // Signatures are what KeymapValidator groups conflicts by, what `claimsChord` asks, and
        // what the migration's drop-on-conflict guard reads. While a UI rebind signed as a
        // fifteen-digit key it collided with nothing, so the guard added for "someone who
        // rebound panel.navigate_right" could not see the very rebinds it was written for.
        assertNoMismatches("a stored keyCode signs as a different chord than its name") { key, presetSpelling ->
            val stored = KeyStroke(key.keyCode.toString(), listOf("Cmd", "Alt")).signature()
            val named = KeyStroke(presetSpelling, listOf("Cmd", "Alt")).signature()
            "signs as '$stored', '$presetSpelling' signs as '$named'".takeIf { stored != named }
        }
    }

    @Test
    fun `single digits are still their word forms, not keyCodes`() {
        // The keyCode fold is guarded to two-or-more all-digit strings precisely so the digit
        // spellings the alias table already claims keep reaching their word forms. Losing this
        // would take Cmd+1 out, which no test above would notice.
        listOf("Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine")
            .forEachIndexed { digit, word ->
                assertEquals(
                    canonicalKeyName(word),
                    canonicalKeyName(digit.toString()),
                    "'$digit' should still be '$word'",
                )
            }
    }
}
