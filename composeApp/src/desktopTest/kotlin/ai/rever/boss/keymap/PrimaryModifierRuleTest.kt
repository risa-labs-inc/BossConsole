package ai.rever.boss.keymap

import ai.rever.boss.keymap.model.KeyStroke
import ai.rever.boss.keymap.model.canonicalModifiers
import ai.rever.boss.keymap.model.primaryModifierPressed
import ai.rever.boss.keymap.model.recordedModifiers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        // The deliberate loss. Super was never nameable in the vocabulary or producible by the
        // recorder, so what goes is an accidental behaviour no surface could display.
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
    // Record then match. This is the assertion that would have caught #553.
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

        // Off macOS a Super press records NO primary modifier, so what comes back is a plain chord,
        // and a plain chord must refuse a held modifier. That case is not round-trippable by design
        // and is asserted on its own below; excluding it here rather than weakening the assertion
        // keeps this one exact.
        for ((mac, meta, control) in cases.filter { (mac, meta, control) -> mac || !meta || control }) {
            val recorded =
                recordedModifiers(
                    metaDown = meta,
                    controlDown = control,
                    shiftDown = false,
                    altDown = false,
                    isMacOS = mac,
                )
            val modifiers = canonicalModifiers(recorded)
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
    fun `off macOS a Super press records no primary modifier`() {
        // It used to record "Ctrl", which the old matcher then fired on Super. After the collapse
        // no spelling means Super, so recording one would hand back a binding that fires on a key
        // the user did not press. Shift and Alt still come through, so the chord is not lost.
        val recorded =
            recordedModifiers(
                metaDown = true,
                controlDown = false,
                shiftDown = true,
                altDown = false,
                isMacOS = false,
            )

        assertEquals(listOf("Shift"), recorded)
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
}
