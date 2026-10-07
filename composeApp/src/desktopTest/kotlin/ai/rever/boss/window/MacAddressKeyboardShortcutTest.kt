package ai.rever.boss.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MacAddressKeyboardShortcutTest {
    @Test
    fun `native command shortcuts use field editor actions`() {
        val commands =
            mapOf(
                "a" to "selectAll:",
                "c" to "copy:",
                "l" to "focusAddress",
                "x" to "cut:",
                "v" to "paste:",
                "z" to "undo:",
            )
        commands.forEach { (key, action) ->
            assertEquals(action, macAddressLocalShortcut(key, COMMAND))
            assertEquals(action, macAddressLocalShortcut(key.uppercase(), COMMAND or CAPS_LOCK))
        }
        assertEquals("redo:", macAddressLocalShortcut("Z", COMMAND or SHIFT))
    }

    @Test
    fun `plain typing and other modifier combinations are untouched`() {
        for (flags in listOf(0L, SHIFT, CONTROL, OPTION, COMMAND or OPTION, COMMAND or CONTROL, COMMAND or SHIFT)) {
            for (key in listOf("a", "c", "l")) assertNull(macAddressLocalShortcut(key, flags))
        }
        assertNull(macAddressLocalShortcut("r", COMMAND))
        assertNull(macAddressLocalShortcut("", COMMAND))
    }

    private companion object {
        const val COMMAND = 1L shl 20
        const val SHIFT = 1L shl 17
        const val CONTROL = 1L shl 18
        const val OPTION = 1L shl 19
        const val CAPS_LOCK = 1L shl 16
    }
}
