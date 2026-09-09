package ai.rever.boss.plugin.browser

import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Scenarios retained from Antriksh's #408, adapted to the shared session's ownership guard. */
class ClipboardRestoreAfterPasteTest {
    @Test
    fun `restores when nothing else was copied in the meantime`() {
        val original = StringSelection("hello world")
        var contents: Transferable? = original
        val session = PasteWithoutFormattingSession({ contents }, { contents = it })
        val ticket = checkNotNull(session.beginPaste())

        assertTrue(session.tryRestore(ticket))
        assertSame(original, contents)
    }

    @Test
    fun `does not restore when the user copied something else during the window`() {
        var contents: Transferable? = StringSelection("hello world")
        val session = PasteWithoutFormattingSession({ contents }, { contents = it })
        val ticket = checkNotNull(session.beginPaste())
        val copy = StringSelection("something the user just copied")
        contents = copy

        assertFalse(session.tryRestore(ticket))
        assertSame(copy, contents)
    }

    @Test
    fun `does not restore when the clipboard was cleared in the meantime`() {
        var contents: Transferable? = StringSelection("hello world")
        val session = PasteWithoutFormattingSession({ contents }, { contents = it })
        val ticket = checkNotNull(session.beginPaste())
        contents = null

        assertFalse(session.tryRestore(ticket))
        assertSame(null, contents)
    }
}
