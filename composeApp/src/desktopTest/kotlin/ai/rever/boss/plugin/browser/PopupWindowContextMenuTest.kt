package ai.rever.boss.plugin.browser

import com.teamdev.jxbrowser.menu.ContextMenuContentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the popup-window context menu's pure parts — no JxBrowser engine required.
 *
 * The menu exists because JxBrowser's built-in one crashes the EDT in popup windows
 * (BossConsole-Releases#17). Its contents are decided entirely from the click target so they can be
 * pinned here, and — the reason that matters beyond testability — so that deciding them costs no
 * blocking IPC on the EDT, where a busy renderer would freeze the whole app.
 */
class PopupWindowContextMenuTest {
    private fun entries(
        editable: Boolean,
        selection: String,
    ) = popupMenuEntriesFor(
        contentTypes = if (editable) listOf(ContextMenuContentType.EDITABLE) else listOf(ContextMenuContentType.PAGE),
        selectedText = selection,
    )

    @Test
    fun `the menu is Cut Copy Paste, a separator, then Select All`() {
        // Order and the separator's position are the whole visual contract.
        val labels = entries(editable = true, selection = "x").map { it.label }
        assertEquals(listOf("Cut", "Copy", "Paste", "", "Select All"), labels)
        assertTrue(entries(editable = true, selection = "x")[3].isSeparator)
    }

    @Test
    fun `Cut and Paste need an editable target`() {
        // Offering Cut on a read-only page would be a menu item that silently does nothing, which
        // is the thing this menu is supposed to avoid.
        val readOnly = entries(editable = false, selection = "selected")
        assertFalse(readOnly.single { it.label == "Cut" }.enabled)
        assertFalse(readOnly.single { it.label == "Paste" }.enabled)

        val editable = entries(editable = true, selection = "selected")
        assertTrue(editable.single { it.label == "Cut" }.enabled)
        assertTrue(editable.single { it.label == "Paste" }.enabled)
    }

    @Test
    fun `Cut and Copy need a selection, Paste does not`() {
        val noSelection = entries(editable = true, selection = "")
        assertFalse(noSelection.single { it.label == "Cut" }.enabled)
        assertFalse(noSelection.single { it.label == "Copy" }.enabled)
        // Paste depends on the clipboard, not the selection - an empty field is its main use.
        assertTrue(noSelection.single { it.label == "Paste" }.enabled)
    }

    @Test
    fun `Copy works on a read-only page when text is selected`() {
        assertTrue(entries(editable = false, selection = "abc").single { it.label == "Copy" }.enabled)
    }

    @Test
    fun `Select All is always offered`() {
        for (editable in listOf(true, false)) {
            for (selection in listOf("", "abc")) {
                assertTrue(
                    entries(editable, selection).single { it.label == "Select All" }.enabled,
                    "Select All should be enabled (editable=$editable, selection='$selection')",
                )
            }
        }
    }

    @Test
    fun `a menu with nothing enabled is never shown`() {
        // Four greyed-out items is worse than no menu, which is what the caller checks before
        // showing. Select All keeps every real target above that bar, so this asserts the
        // predicate itself rather than trying to construct an all-disabled target.
        assertTrue(hasAnyEnabledEntry(entries(editable = false, selection = "")))
        assertFalse(hasAnyEnabledEntry(emptyList()))
        assertFalse(
            hasAnyEnabledEntry(
                listOf(PopupMenuEntry("Cut", null, enabled = false), PopupMenuEntry("", null, enabled = false)),
            ),
        )
    }

    @Test
    fun `separators are entries with no command`() {
        val separator = PopupMenuEntry("", command = null, enabled = false)
        assertTrue(separator.isSeparator)
        assertFalse(entries(editable = true, selection = "x").single { it.label == "Copy" }.isSeparator)
    }
}
