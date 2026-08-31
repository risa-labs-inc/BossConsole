package ai.rever.boss.fullscreen

import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.model.ShortcutContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins what the reminder says, and which keys Windows takes.
 *
 * Both are "the user cannot get out" bugs rather than cosmetic ones, and neither is visible to any
 * other test: the wording is only wrong once someone rebinds, and the key table is only wrong on a
 * platform this suite does not run on.
 */
class CapturedFullScreenEscapesTest {
    private fun keymapOf(vararg bindings: KeyBinding) = KeymapSettings(shortcuts = bindings.associateBy { it.actionId })

    private fun binding(
        actionId: String,
        key: String,
        vararg modifiers: String,
    ) = KeyBinding(
        actionId = actionId,
        key = key,
        modifiers = modifiers.toList(),
        context = ShortcutContext.GLOBAL,
        category = KeymapActions.Categories.VIEW,
        description = KeymapActions.getDescription(actionId),
    )

    // --- the reminder ------------------------------------------------------------------

    @Test
    fun `the reminder reads the live bindings rather than a hardcoded chord`() {
        val lines =
            capturedHudLines(
                keymapOf(
                    binding(KeymapActions.CAPTURED_FULLSCREEN_TOGGLE, "J", "Cmd", "Shift"),
                    binding(KeymapActions.POINTER_RELEASE, "K", "Cmd", "Shift"),
                ),
                emptySet(),
            )
        assertTrue(
            lines.any { it.contains("J") },
            "A rebound exit must be what the reminder shows. Hardcoding the default would tell a " +
                "user to press something that no longer works, in the one mode they cannot leave " +
                "without it. Got: $lines",
        )
        assertTrue(lines.any { it.contains("K") })
    }

    @Test
    fun `an unbound exit falls back to the hold rather than printing an empty chord`() {
        val lines = capturedHudLines(KeymapSettings(), emptySet())
        assertTrue(
            lines.first().contains("Esc"),
            "With the action unbound there is no chord to show, and this is exactly when someone " +
                "needs the hardwired way out. Got: $lines",
        )
    }

    @Test
    fun `the hold is always offered, bound or not`() {
        val bound =
            capturedHudLines(
                keymapOf(binding(KeymapActions.CAPTURED_FULLSCREEN_TOGGLE, "F", "Cmd", "Alt")),
                emptySet(),
            )
        assertTrue(
            bound.any { it.contains("Hold Esc for 2 seconds") },
            "The hold cannot be rebound, so it is the one line that is true whatever the keymap " +
                "says. Got: $bound",
        )
    }

    @Test
    fun `every limitation is stated, because none of them is visible by looking`() {
        val lines =
            capturedHudLines(
                KeymapSettings(),
                setOf(CaptureLimitation.KEYBOARD_NOT_GRABBED, CaptureLimitation.WAYLAND_NO_GRAB),
            )
        assertTrue(lines.any { it.contains("system shortcuts are still active") })
        assertTrue(lines.any { it.contains("Wayland") })
    }

    @Test
    fun `a clean grab says nothing about limitations`() {
        val lines =
            capturedHudLines(
                keymapOf(binding(KeymapActions.CAPTURED_FULLSCREEN_TOGGLE, "F", "Cmd", "Alt")),
                emptySet(),
            )
        assertFalse(lines.any { it.startsWith("Note:") })
    }

    // --- the Windows key table ---------------------------------------------------------

    @Test
    fun `both Windows keys are taken outright`() {
        assertTrue(swallowsVirtualKey(VK_LWIN, altDown = false, ctrlDown = false))
        assertTrue(swallowsVirtualKey(VK_RWIN, altDown = false, ctrlDown = false))
    }

    @Test
    fun `bare Tab and bare Escape are never taken`() {
        // The bug this exists to prevent: a first draft swallowed both unconditionally, which
        // breaks Tab in the editor and Escape in every terminal, dialog and vim session in the
        // app - inside a mode whose panic escape is holding Escape.
        assertFalse(
            swallowsVirtualKey(VK_TAB, altDown = false, ctrlDown = false),
            "Bare Tab belongs to the focused editor, not to the shell switcher",
        )
        assertFalse(
            swallowsVirtualKey(VK_ESCAPE, altDown = false, ctrlDown = false),
            "Bare Escape must reach the app, or the hardwired hold can never be observed",
        )
    }

    @Test
    fun `the shell switcher chords are taken`() {
        assertTrue(swallowsVirtualKey(VK_TAB, altDown = true, ctrlDown = false), "Alt+Tab")
        assertTrue(swallowsVirtualKey(VK_ESCAPE, altDown = false, ctrlDown = true), "Ctrl+Esc")
        assertTrue(swallowsVirtualKey(VK_ESCAPE, altDown = true, ctrlDown = false), "Alt+Esc")
    }

    @Test
    fun `an ordinary letter is never taken`() {
        val vkA = 0x41
        assertFalse(swallowsVirtualKey(vkA, altDown = true, ctrlDown = true))
    }

    // --- the two actions are real, registered actions -----------------------------------

    @Test
    fun `both escapes are registered so they appear in Settings and can be rebound`() {
        val ids = KeymapActions.getAllActionIds()
        listOf(KeymapActions.CAPTURED_FULLSCREEN_TOGGLE, KeymapActions.POINTER_RELEASE).forEach { id ->
            assertTrue(id in ids, "$id missing from getAllActionIds, so Settings would not list it")
            assertEquals(KeymapActions.Categories.VIEW, KeymapActions.getCategory(id))
            assertEquals(ShortcutContext.GLOBAL, KeymapActions.getContext(id))
            assertTrue(KeymapActions.getDescription(id) != "Unknown action", "$id has no description")
        }
    }
}
