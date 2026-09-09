package ai.rever.boss.plugin.browser

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PasteWithoutFormattingSessionTest {
    private class Fixture {
        var contents: Transferable? = StringSelection("same text")
        var lastInstalled: Transferable? = null
        var readFailure = false
        var writeFailure = false
        val session =
            PasteWithoutFormattingSession(
                currentContents = {
                    check(!readFailure) { "clipboard locked" }
                    contents
                },
                install = {
                    check(!writeFailure) { "clipboard locked" }
                    // SunClipboard stores a TransferableProxy instead of the supplied instance.
                    lastInstalled = it
                    contents = object : Transferable by it {}
                },
            )
    }

    @Test
    fun `restores rich original through a wrapping clipboard`() {
        val fixture = Fixture()
        val html = DataFlavor("text/html;class=java.lang.String")
        val rich =
            object : Transferable by StringSelection("same text") {
                override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.stringFlavor, html)

                override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in transferDataFlavors

                override fun getTransferData(flavor: DataFlavor): Any = if (flavor == html) "<b>x</b>" else "x"
            }
        fixture.contents = rich
        val original = fixture.contents
        val ticket = checkNotNull(fixture.session.beginPaste())
        assertNotSame(original, fixture.contents)
        assertFalse(fixture.contents!!.isDataFlavorSupported(html), "plain substitution must not advertise HTML")
        assertTrue(fixture.session.tryRestore(ticket))
        assertSame(original, fixture.lastInstalled, "restore must install the original, not the plain replacement")
        assertFalse(fixture.session.tryRestore(ticket))
    }

    @Test
    fun `same text from another copy is not owned`() {
        val fixture = Fixture()
        val ticket = checkNotNull(fixture.session.beginPaste())
        val userCopy = StringSelection("same text")
        fixture.contents = userCopy
        assertFalse(fixture.session.tryRestore(ticket))
        assertSame(userCopy, fixture.contents)
    }

    @Test
    fun `foreign copy between presses becomes the new original`() {
        var contents: Transferable? = StringSelection("old")
        val session = PasteWithoutFormattingSession({ contents }, { contents = it })
        val oldTicket = checkNotNull(session.beginPaste())
        val userCopy = StringSelection("new")
        contents = userCopy
        val newTicket = checkNotNull(session.beginPaste())
        assertFalse(session.tryRestore(oldTicket))
        assertTrue(session.tryRestore(newTicket))
        assertSame(userCopy, contents)
    }

    @Test
    fun `earlier timer cannot restore while latest paste is still consuming plain text`() {
        var contents: Transferable? = StringSelection("original")
        val original = contents
        val session = PasteWithoutFormattingSession({ contents }, { contents = it })
        val first = checkNotNull(session.beginPaste())
        val second = checkNotNull(session.beginPaste())
        val latestPlain = contents
        assertFalse(session.tryRestore(first))
        assertSame(latestPlain, contents)
        assertTrue(session.tryRestore(second))
        assertSame(original, contents)
    }

    @Test
    fun `failed restore read retires stale original before the next paste`() {
        val fixture = Fixture()
        val first = checkNotNull(fixture.session.beginPaste())
        fixture.readFailure = true
        assertFailsWith<IllegalStateException> { fixture.session.tryRestore(first) }
        fixture.readFailure = false
        fixture.contents = StringSelection("new")
        val second = checkNotNull(fixture.session.beginPaste())
        assertFalse(fixture.session.tryRestore(first))
        assertTrue(fixture.session.tryRestore(second))
        assertTrue(fixture.contents!!.getTransferData(DataFlavor.stringFlavor) == "new")
    }

    @Test
    fun `failed write does not retain a previous burst original`() {
        val fixture = Fixture()
        val first = checkNotNull(fixture.session.beginPaste())
        fixture.writeFailure = true
        assertFailsWith<IllegalStateException> { fixture.session.beginPaste() }
        fixture.writeFailure = false
        assertFalse(fixture.session.tryRestore(first))
        fixture.contents = StringSelection("new")
        val next = checkNotNull(fixture.session.beginPaste())
        assertTrue(fixture.session.tryRestore(next))
        assertTrue(fixture.contents!!.getTransferData(DataFlavor.stringFlavor) == "new")
    }

    @Test
    fun `failed restore install cannot be retried by an old timer`() {
        val fixture = Fixture()
        val ticket = checkNotNull(fixture.session.beginPaste())
        fixture.writeFailure = true
        assertFailsWith<IllegalStateException> { fixture.session.tryRestore(ticket) }
        fixture.writeFailure = false
        assertFalse(fixture.session.tryRestore(ticket))
    }
}
