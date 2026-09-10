package ai.rever.boss.window

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessWideDialogOwnershipTest {
    @Test
    fun `only the first window owns process-wide dialogs`() {
        val windows = listOf("primary", "secondary", "third")

        assertTrue(ownsProcessWideDialogs("primary", windows))
        assertFalse(ownsProcessWideDialogs("secondary", windows))
        assertFalse(ownsProcessWideDialogs("third", windows))
    }

    @Test
    fun `ownership passes to the next window when the first closes`() {
        assertTrue(ownsProcessWideDialogs("secondary", listOf("secondary", "third")))
        assertFalse(ownsProcessWideDialogs("missing", emptyList()))
    }
}
