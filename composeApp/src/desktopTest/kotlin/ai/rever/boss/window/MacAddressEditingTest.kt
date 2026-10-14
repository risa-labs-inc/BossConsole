package ai.rever.boss.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacAddressEditingTest {
    @Test
    fun `return without input or after disposal is not consumed`() {
        val editing = MacAddressEditing()
        var submissions = 0
        assertFalse(editing.command("insertNewline:", false) { submissions++ })
        editing.input = input()
        editing.closed = true
        assertFalse(editing.command("insertNewline:", false) { submissions++ })
        assertEquals(0, submissions)
    }

    @Test
    fun `return with live input submits exactly once`() {
        val editing = MacAddressEditing()
        editing.input = input()
        var submissions = 0
        assertTrue(editing.command("insertNewline:", false) { submissions++ })
        assertEquals(1, submissions)
    }

    @Test
    fun `delete and unknown commands never submit`() {
        val editing = MacAddressEditing()
        editing.input = input()
        var submissions = 0
        for (selector in listOf("deleteBackward:", "deleteForward:", "unknown:")) {
            assertFalse(editing.command(selector, false) { submissions++ })
        }
        assertEquals(0, submissions)
    }

    private fun input() = NativeTitleBarTextInput("test", "https://example.com", {}, {})
}
