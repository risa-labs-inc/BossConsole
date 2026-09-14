package ai.rever.boss.keymap

import ai.rever.boss.keymap.handler.KeymapValidator
import ai.rever.boss.keymap.model.EventChord
import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeyStroke
import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.model.ShortcutContext
import ai.rever.boss.keymap.model.canonicalModifiers
import ai.rever.boss.keymap.model.chordSignature
import ai.rever.boss.keymap.model.keystrokeMatches
import ai.rever.boss.keymap.model.primaryModifierPressed
import ai.rever.boss.keymap.model.recordedModifiers
import ai.rever.boss.keymap.presets.KeymapPresets
import ai.rever.boss.utils.SystemUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Cmd/Ctrl rule, on both platforms.
 *
 * `isMacOS` is a parameter rather than a read precisely so this file can run the non-mac branch on
 * a Mac. It could not before: every matcher read `SystemUtils.isMacOS` inline, so on the machines
 * this project is developed on only the macOS branch ever executed, and the non-mac branch was
 * wrong for as long as it existed. Each test below therefore states its platform.
 *
 * BossConsole#553.
 */
class PrimaryModifierRuleTest {
    private fun pressed(
        hasCmd: Boolean = false,
        hasCtrl: Boolean = false,
        metaDown: Boolean = false,
        controlDown: Boolean = false,
        isMacOS: Boolean,
    ) = primaryModifierPressed(hasCmd, hasCtrl, metaDown, controlDown, isMacOS)

    // ---------------------------------------------------------------------
    // macOS: Cmd and Ctrl are different keys and must stay so.
    // ---------------------------------------------------------------------

    @Test
    fun `on macOS a Cmd chord needs the Cmd key`() {
        assertTrue(pressed(hasCmd = true, metaDown = true, isMacOS = true))
        assertFalse(pressed(hasCmd = true, controlDown = true, isMacOS = true))
    }

    @Test
    fun `on macOS a Ctrl chord needs the Control key`() {
        assertTrue(pressed(hasCtrl = true, controlDown = true, isMacOS = true))
        assertFalse(pressed(hasCtrl = true, metaDown = true, isMacOS = true))
    }

    // ---------------------------------------------------------------------
    // Windows and Linux: both spellings are the Control key.
    // ---------------------------------------------------------------------

    @Test
    fun `off macOS an explicit Ctrl chord fires on the Control key`() {
        // The whole of #553. This was false before: an explicit "Ctrl" was matched against the
        // Meta key, so the default preset's Ctrl+Tab needed Super+Tab while Settings said Ctrl.
        assertTrue(pressed(hasCtrl = true, controlDown = true, isMacOS = false))
    }

    @Test
    fun `off macOS a Cmd chord also fires on the Control key`() {
        // Unchanged, and the reason the collapse is consistent rather than a special case:
        // displayString already renders both spellings as "Ctrl" on this platform.
        assertTrue(pressed(hasCmd = true, controlDown = true, isMacOS = false))
    }

    @Test
    fun `off macOS the Super key no longer fires either spelling`() {
        // Legacy Super bindings were stored as Ctrl and now fire on Control instead.
        assertFalse(pressed(hasCtrl = true, metaDown = true, isMacOS = false))
        assertFalse(pressed(hasCmd = true, metaDown = true, isMacOS = false))
    }

    // ---------------------------------------------------------------------
    // Chords with no primary modifier, on either platform.
    // ---------------------------------------------------------------------

    @Test
    fun `a chord asking for no primary modifier refuses one that is held`() {
        for (mac in listOf(true, false)) {
            assertTrue(pressed(isMacOS = mac), "plain chord with nothing held (mac=$mac)")
            assertFalse(pressed(controlDown = true, isMacOS = mac), "Control held (mac=$mac)")
            assertFalse(pressed(metaDown = true, isMacOS = mac), "Meta held (mac=$mac)")
        }
    }

    // ---------------------------------------------------------------------
    // Record then match. This prevents future drift between capture and dispatch.
    // ---------------------------------------------------------------------

    @Test
    fun `every chord the recorder can produce is one the matcher accepts`() {
        // Exhaustive over both platforms and all four primary-key states. Nothing pinned this
        // before, and the reason it matters is that recordedModifiers and primaryModifierPressed
        // are the same decision written twice, in opposite directions.
        //
        // Flattened into one list rather than three nested loops: detekt caps the nesting depth
        // here, and widening that rule to keep a prettier shape would be the wrong trade.
        val cases =
            listOf(true, false).flatMap { mac ->
                listOf(true, false).flatMap { meta ->
                    listOf(true, false).map { control -> Triple(mac, meta, control) }
                }
            }

        // Super captures are rejected off macOS, including Super+Control.
        for ((mac, meta, control) in cases.filter { (mac, meta, control) -> mac || !meta }) {
            val recorded =
                recordedModifiers(
                    metaDown = meta,
                    controlDown = control,
                    shiftDown = false,
                    altDown = false,
                    isMacOS = mac,
                )
            val modifiers = canonicalModifiers(requireNotNull(recorded))
            assertTrue(
                primaryModifierPressed(
                    hasCmd = "cmd" in modifiers,
                    hasCtrl = "ctrl" in modifiers,
                    metaDown = meta,
                    controlDown = control,
                    isMacOS = mac,
                ),
                "recorded $recorded does not match the keys that produced it " +
                    "(mac=$mac, meta=$meta, control=$control)",
            )
        }
    }

    @Test
    fun `off macOS a Super press is rejected instead of saving a plain chord`() {
        // A held unsupported modifier must not disappear from the saved shortcut.
        val recorded =
            recordedModifiers(
                metaDown = true,
                controlDown = false,
                shiftDown = true,
                altDown = false,
                isMacOS = false,
            )

        assertNull(recorded)
        assertNull(
            recordedModifiers(metaDown = true, controlDown = true, shiftDown = false, altDown = false, isMacOS = false),
        )
    }

    @Test
    fun `on macOS both primary modifiers are still recorded separately`() {
        assertEquals(
            listOf("Cmd"),
            recordedModifiers(metaDown = true, controlDown = false, shiftDown = false, altDown = false, isMacOS = true),
        )
        assertEquals(
            listOf("Ctrl"),
            recordedModifiers(metaDown = false, controlDown = true, shiftDown = false, altDown = false, isMacOS = true),
        )
    }

    @Test
    fun `a keymap written by the previous capture dialog still works`() {
        // That dialog recorded a Control press as "Cmd" off macOS. Those bindings are on disk and
        // must keep firing, which is why "cmd" is still accepted on read rather than migrated.
        assertTrue(
            primaryModifierPressed(
                hasCmd = true,
                hasCtrl = false,
                metaDown = false,
                controlDown = true,
                isMacOS = false,
            ),
        )
    }

    @Test
    fun `the default tab-cycle binding displays the chord that actually fires`() {
        // Settings promised Ctrl+Tab while only Super+Tab worked. Both halves are asserted here so
        // the display string and the matcher cannot drift apart again.
        val preset = KeyStroke(key = "Tab", modifiers = listOf("Ctrl"))

        assertEquals("Ctrl+Tab", preset.displayString("Windows 11"))

        val modifiers = canonicalModifiers(preset.modifiers)
        assertTrue(
            primaryModifierPressed(
                hasCmd = "cmd" in modifiers,
                hasCtrl = "ctrl" in modifiers,
                metaDown = false,
                controlDown = true,
                isMacOS = false,
            ),
            "the chord Settings displays as Ctrl+Tab must fire on the Control key",
        )
    }

    // ---------------------------------------------------------------------
    // Chord identity. The matcher folding the two spellings is only half the
    // rule: everything that asks "is this chord already taken" has to fold the
    // same way, or one keypress runs two actions and Settings shows no badge.
    // ---------------------------------------------------------------------

    private fun sig(
        key: String,
        vararg modifiers: String,
        isMacOS: Boolean,
    ) = chordSignature(key, modifiers.toList(), isMacOS)

    @Test
    fun `off macOS a Ctrl chord and a Cmd chord are one chord`() {
        // Both fire on Control off macOS, so conflict detection has to see one chord. It saw two:
        // a captured Ctrl+W and the preset's Cmd+W both dispatched and neither was reported.
        assertEquals(sig("W", "Ctrl", isMacOS = false), sig("W", "Cmd", isMacOS = false))
    }

    @Test
    fun `on macOS a Ctrl chord and a Cmd chord stay different chords`() {
        assertNotEquals(sig("W", "Ctrl", isMacOS = true), sig("W", "Cmd", isMacOS = true))
    }

    @Test
    fun `the fold reaches alias spellings too`() {
        // "Meta" and "Control" are the file spellings; a keymap edited by hand uses them.
        assertEquals(sig("W", "Meta", isMacOS = false), sig("W", "Control", isMacOS = false))
        assertNotEquals(sig("W", "Meta", isMacOS = true), sig("W", "Control", isMacOS = true))
    }

    @Test
    fun `the fold merges the primary modifier and nothing else`() {
        // A fold that swallowed Shift or Alt would report conflicts between chords that really are
        // different, which is worse than the bug it fixes.
        assertNotEquals(sig("W", "Ctrl", isMacOS = false), sig("W", "Ctrl", "Shift", isMacOS = false))
        assertNotEquals(sig("W", "Ctrl", isMacOS = false), sig("W", "Ctrl", "Alt", isMacOS = false))
        assertNotEquals(sig("W", "Ctrl", isMacOS = false), sig("Q", "Ctrl", isMacOS = false))
        assertNotEquals(sig("W", "Shift", isMacOS = false), sig("W", "Alt", isMacOS = false))
        // An unmodified chord is untouched on both platforms.
        assertEquals(sig("W", isMacOS = false), sig("W", isMacOS = true))
    }

    @Test
    fun `the fold keeps key aliases folded as before`() {
        assertEquals(sig("Right", "Cmd", isMacOS = true), sig("DirectionRight", "Meta", isMacOS = true))
        assertEquals(sig("Right", "Cmd", isMacOS = false), sig("DirectionRight", "Ctrl", isMacOS = false))
    }

    // ---------------------------------------------------------------------
    // The validator is the caller that matters, and it reads the platform
    // inline through allSignatures(), so it can only be asserted for the
    // machine running the test. The pure pair above covers both branches.
    // ---------------------------------------------------------------------

    private fun binding(
        actionId: String,
        vararg modifiers: String,
    ) = KeyBinding(actionId = actionId, key = "W", modifiers = modifiers.toList(), context = ShortcutContext.GLOBAL)

    @Test
    fun `the validator reports the Ctrl and Cmd collision exactly where it is real`() {
        val existing = binding("tab.close", "Cmd")
        val candidate = binding("editor.close", "Ctrl")
        val settings = KeymapSettings(shortcuts = mapOf(existing.actionId to existing))

        val conflicts = KeymapValidator.checkBinding(candidate, settings)

        if (SystemUtils.isMacOS) {
            assertTrue(conflicts.isEmpty(), "on macOS these are different keys: $conflicts")
        } else {
            // Before the fold this list was empty while both bindings fired on Control+W.
            assertEquals(listOf(existing.actionId), conflicts.map { it.actionId })
        }
    }

    @Test
    fun `the validator still separates chords that differ off macOS`() {
        val existing = binding("tab.close", "Cmd")
        val candidate = binding("editor.close", "Ctrl", "Shift")
        val settings = KeymapSettings(shortcuts = mapOf(existing.actionId to existing))

        assertTrue(KeymapValidator.checkBinding(candidate, settings).isEmpty())
    }

    @Test
    fun `an alternate keystroke collides through the fold as well`() {
        // allSignatures(), not signature(): migration adds alternates, and an alternate that
        // collides only after the fold is exactly the case claimsChord has to refuse.
        val existing =
            KeyBinding(
                actionId = "tab.close",
                key = "Q",
                modifiers = listOf("Cmd"),
                alternateKeystrokes = listOf(KeyStroke("W", listOf("Cmd"))),
                context = ShortcutContext.GLOBAL,
            )
        val candidate = binding("editor.close", "Ctrl")
        val settings = KeymapSettings(shortcuts = mapOf(existing.actionId to existing))

        val conflicts = KeymapValidator.checkBinding(candidate, settings)

        if (SystemUtils.isMacOS) {
            assertTrue(conflicts.isEmpty())
        } else {
            assertEquals(listOf(existing.actionId), conflicts.map { it.actionId })
        }
    }

    // ---------------------------------------------------------------------
    // The public KeyStroke/KeyBinding matcher. It has no production caller
    // today - KeymapMatcher and AWTKeyboardInterceptor are the live paths -
    // but it is public API, and its tests certified the rule the live
    // matchers stopped using in #553.
    // ---------------------------------------------------------------------

    private fun matched(
        vararg modifiers: String,
        metaDown: Boolean = false,
        controlDown: Boolean = false,
        shiftDown: Boolean = false,
        altDown: Boolean = false,
        isMacOS: Boolean,
    ) = keystrokeMatches(
        keystrokeKey = "W",
        modifiers = modifiers.toList(),
        event =
            EventChord(
                key = "W",
                metaDown = metaDown,
                controlDown = controlDown,
                shiftDown = shiftDown,
                altDown = altDown,
            ),
        isMacOS = isMacOS,
    )

    @Test
    fun `off macOS the public matcher fires an explicit Ctrl chord on Control`() {
        // Was false: it compared hasCtrl to isCtrlPressed but also required hasCmd == isMetaPressed,
        // so the same chord the recorder writes was one this matcher refused.
        assertTrue(matched("Ctrl", controlDown = true, isMacOS = false))
    }

    @Test
    fun `off macOS the public matcher fires a Cmd chord on Control`() {
        assertTrue(matched("Cmd", controlDown = true, isMacOS = false))
        assertFalse(matched("Cmd", metaDown = true, isMacOS = false))
    }

    @Test
    fun `on macOS the public matcher keeps the two keys apart`() {
        assertTrue(matched("Cmd", metaDown = true, isMacOS = true))
        assertFalse(matched("Cmd", controlDown = true, isMacOS = true))
        assertTrue(matched("Ctrl", controlDown = true, isMacOS = true))
        assertFalse(matched("Ctrl", metaDown = true, isMacOS = true))
    }

    @Test
    fun `the public matcher still requires shift and alt exactly`() {
        assertFalse(matched("Ctrl", controlDown = true, shiftDown = true, isMacOS = false))
        assertFalse(matched("Ctrl", "Shift", controlDown = true, isMacOS = false))
        assertTrue(matched("Ctrl", "Shift", controlDown = true, shiftDown = true, isMacOS = false))
        assertTrue(matched("Ctrl", "Alt", controlDown = true, altDown = true, isMacOS = false))
    }

    @Test
    fun `an unmodified chord still refuses a held primary modifier`() {
        assertTrue(matched(isMacOS = false))
        assertFalse(matched(controlDown = true, isMacOS = false))
        assertFalse(matched(metaDown = true, isMacOS = true))
    }

    @Test
    fun `the public matcher still refuses a different key`() {
        assertFalse(
            keystrokeMatches(
                keystrokeKey = "W",
                modifiers = listOf("Ctrl"),
                event = EventChord(key = "Q", controlDown = true),
                isMacOS = false,
            ),
        )
    }

    // ---------------------------------------------------------------------
    // What the fold found. Every shipped preset has to survive its own
    // conflict check on BOTH platforms, not just the one it was written on.
    // ---------------------------------------------------------------------

    @Test
    fun `every shipped preset is free of conflicts on this platform`() {
        listOf(
            "BOSS Default" to KeymapPresets.getBOSSDefault(),
            "VS Code" to KeymapPresets.getVSCodePreset(),
            "IntelliJ IDEA" to KeymapPresets.getIntelliJPreset(),
            "Emacs" to KeymapPresets.getEmacsPreset(),
        ).forEach { (name, preset) ->
            val conflicts = KeymapValidator.validate(preset)
            assertTrue(conflicts.isEmpty(), "$name: ${conflicts.joinToString { it.description() }}")
        }
    }

    @Test
    fun `VS Code keeps find-next and go-to-line on different keys`() {
        // Off macOS this preset bound Cmd+G to Find Next and Ctrl+G to Go to Line, which are the
        // same physical key there, so one Control+G press matched two EDITOR actions. Nothing
        // reported it: the matcher folded the two spellings and the signature did not. Real VS
        // Code avoids this by using F3 off macOS and reserving Ctrl+G for Go to Line.
        val preset = KeymapPresets.getVSCodePreset()
        val findNext = requireNotNull(preset.getBinding(KeymapActions.EDITOR_FIND_NEXT))
        val goToLine = requireNotNull(preset.getBinding(KeymapActions.EDITOR_GO_TO_LINE))

        assertEquals(findNext.context, goToLine.context, "the case only matters inside one context")
        assertNotEquals(findNext.signature(), goToLine.signature())

        if (SystemUtils.isMacOS) {
            assertEquals("G", findNext.key)
            assertEquals(listOf("Cmd"), findNext.modifiers)
        } else {
            assertEquals("F3", findNext.key)
            assertTrue(findNext.modifiers.isEmpty())
        }
        assertEquals("G", goToLine.key)
        assertEquals(listOf("Ctrl"), goToLine.modifiers)
    }
}
