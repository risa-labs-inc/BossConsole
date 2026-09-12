package ai.rever.boss.components.workspaces

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins what the keep-or-close question says, in both of its versions.
 *
 * Copy is usually not worth a test. This copy is, because it makes a claim about what happens to
 * the user's work and the claim was false: the dialog offered to close a Space while promising it
 * would be "rebuilt from its layout next time", which is only true of the layout last SAVED. On an
 * edited Space, Close destroys the arrangement on screen - the window's preserved state is dropped,
 * every panel is cleared with no reopen record, and the Last Session record is overwritten within
 * about two seconds by the Space being entered. A test is the only thing that stops a future edit
 * quietly restoring the reassuring version.
 */
class WorkspaceSwitchCopyTest {
    private companion object {
        const val LEAVING = "Code Review"
    }

    private fun body(unsaved: Boolean) = switchPromptBody(LEAVING, leavingUnsaved = unsaved)

    @Test
    fun `both versions ask the same question, about the Space by name`() {
        listOf(true, false).forEach { unsaved ->
            assertTrue(
                body(unsaved).startsWith("Keep $LEAVING running in the background, or close it?"),
                "the question itself does not change with the state",
            )
        }
    }

    @Test
    fun `an unsaved Space is said to be unsaved, and Close is said to discard`() {
        val text = body(unsaved = true)
        assertTrue(text.contains("$LEAVING has unsaved changes"), "the state must be stated, not implied")
        assertTrue(text.contains("Closing discards them"), "and the cost of Close must be stated plainly")
        assertTrue(text.contains("Keeping it running keeps them"), "as must the fact that Keep does not")
    }

    @Test
    fun `the unsaved version never promises the layout on screen comes back`() {
        // The exact false claim. It survived three readings because it is true of a SAVED Space,
        // which is what makes it worth pinning rather than trusting to review.
        val text = body(unsaved = true)
        assertFalse(
            text.contains("rebuilt from its layout"),
            "an unsaved Space is not rebuilt from the layout that is on screen",
        )
        assertTrue(
            text.contains("comes back from its last saved layout"),
            "it comes back from the last save, and the dialog should say which",
        )
    }

    @Test
    fun `the way out is named, because the dialog has no cancel button`() {
        // Escape reaches `onDismissRequest`, which cancels the switch rather than guessing - see
        // `BossDialog`. Neither button is safe when there is unsaved work, so the third option has
        // to be discoverable from the text.
        assertTrue(body(unsaved = true).contains("Escape cancels the switch"))
    }

    @Test
    fun `a saved Space keeps its old reassurance, corrected to name the save`() {
        val text = body(unsaved = false)
        assertTrue(text.contains("Running keeps its tabs open so switching back is instant"))
        assertTrue(
            text.contains("$LEAVING is rebuilt from its saved layout next time"),
            "true here, and the word 'saved' is what makes it true",
        )
        assertFalse(text.contains("unsaved"), "nothing to warn about, so no warning")
    }

    @Test
    fun `the close button names the consequence only when there is one`() {
        assertEquals("Discard and Close", closeButtonLabel(LEAVING, leavingUnsaved = true))
        assertEquals("Close $LEAVING", closeButtonLabel(LEAVING, leavingUnsaved = false))
    }
}
