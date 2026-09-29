package ai.rever.boss.components.sidebar

import ai.rever.boss.window.addressCompletion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AddressCompletionTest {
    @Test
    fun `completion only extends current text with a caret at its end`() {
        assertEquals("github.com", addressCompletion("git", "git", "github.com", 3, 3))
        assertNull(addressCompletion("git", "gi", "github.com", 3, 3))
        assertNull(addressCompletion("git", "git", "github.com", 0, 3))
        assertNull(addressCompletion("git", "git", "github.com", 1, 1))
        assertNull(addressCompletion("git", "git", "google.com", 3, 3))
        assertNull(addressCompletion("", "", "github.com", 0, 0))
    }
}
