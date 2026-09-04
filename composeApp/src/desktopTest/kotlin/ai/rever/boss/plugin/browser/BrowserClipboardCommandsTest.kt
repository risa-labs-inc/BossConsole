package ai.rever.boss.plugin.browser

import com.teamdev.jxbrowser.engine.RenderingMode
import com.teamdev.jxbrowser.frame.EditorCommand
import com.teamdev.jxbrowser.frame.Frame
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Guards the two decisions behind browser copy/paste, both of which failed silently before.
 *
 * The reported symptom was "copy in a browser tab, switch tabs, paste - works sometimes". Two
 * unrelated causes produced it, and neither could be caught by any existing test:
 *
 *  - The clipboard operations ran `document.execCommand(...)` as injected JavaScript against
 *    `mainFrame()`. `execCommand('copy')` needs transient user activation, which a right-click
 *    grants for about five seconds - so the same menu item copied or did nothing depending on
 *    how long the menu had been open, and the boolean saying which was discarded.
 *  - Nothing gave the web content keyboard focus when a tab came back into view.
 *
 * What is NOT covered here, deliberately, so nobody reads more into a green run: the four
 * one-line mappings from `copySelection`/`cut`/`paste`/`selectAll` to their `EditorCommand`
 * factories are review-verified. Reaching them needs a `BrowserHandleImpl`, which needs a live
 * engine generation for `isValid`, and a test that stood up an engine to assert four constants
 * would be testing JxBrowser rather than this code.
 */
class BrowserClipboardCommandsTest {
    /** A [Frame] that records the commands it is asked to run and answers [accepts]. */
    private class RecordingFrame(
        private val accepts: Boolean = true,
    ) {
        val executed = mutableListOf<EditorCommand>()

        val frame: Frame =
            Proxy.newProxyInstance(
                Frame::class.java.classLoader,
                arrayOf(Frame::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "execute" -> {
                        executed += args[0] as EditorCommand
                        accepts
                    }

                    "hashCode" -> {
                        System.identityHashCode(this)
                    }

                    "equals" -> {
                        false
                    }

                    "toString" -> {
                        "RecordingFrame"
                    }

                    else -> {
                        error("unexpected Frame call: ${method.name}")
                    }
                }
            } as Frame
    }

    // --- which frame an editor command reaches ---

    /**
     * The regression this exists for. `mainFrame()` was the only target, so a caret inside an
     * iframe - an embedded editor, an OAuth form, a comment box - copied and pasted nothing at
     * all. Asserted as "the main frame was not touched" rather than only "the focused frame was",
     * because a change that ran the command on both would satisfy the weaker claim while pasting
     * into the wrong document.
     */
    @Test
    fun `the focused frame receives the command, and the main frame does not`() {
        val focused = RecordingFrame()
        val main = RecordingFrame()

        executeEditorCommand(focused.frame, main.frame, EditorCommand.copy())

        assertEquals(1, focused.executed.size, "the focused frame should have run the command")
        assertEquals(EditorCommand.Name.COPY, focused.executed.single().name())
        assertTrue(main.executed.isEmpty(), "the main frame must not also run it")
    }

    /**
     * Chromium reports no focused frame for a page nothing has been clicked in yet, which is
     * exactly the state a freshly restored tab is in. Falling back keeps Select All and Copy
     * working there instead of turning the fix into a different silent no-op.
     */
    @Test
    fun `the main frame is the fallback when nothing is focused`() {
        val main = RecordingFrame()

        executeEditorCommand(focusedFrame = null, mainFrame = main.frame, command = EditorCommand.paste())

        assertEquals(EditorCommand.Name.PASTE, main.executed.single().name())
    }

    /** A closed or still-loading browser has neither frame. Refusing beats throwing at a menu. */
    @Test
    fun `no frame at all is a refusal, not a throw`() {
        assertFalse(executeEditorCommand(focusedFrame = null, mainFrame = null, command = EditorCommand.cut()))
    }

    /**
     * Chromium's own answer has to survive the call, because it is the only signal that a
     * clipboard command did nothing - an empty selection, a non-editable caret, an empty
     * clipboard. Swallowing it is what the old `executeJavaScript<Unit>` did, and it is why the
     * failure was invisible for as long as it was.
     */
    @Test
    fun `a refusal by Chromium is reported, not swallowed`() {
        val refusing = RecordingFrame(accepts = false)

        assertFalse(executeEditorCommand(refusing.frame, null, EditorCommand.copy()))
        assertTrue(executeEditorCommand(RecordingFrame(accepts = true).frame, null, EditorCommand.copy()))
    }

    // --- who has to restore keyboard focus ---

    /**
     * OFF_SCREEN must stay false. JxBrowser's own `OffScreenWidgetState` wires `onFocusChanged`
     * to `BrowserWidget.focus()`/`unfocus()` and answers `TakeFocusCallback`, so a second
     * host-side `focus()` there would fight the widget rather than help it. `SharedSurfaceWidget`,
     * the HARDWARE_ACCELERATED path, has no such wiring - which is the gap being filled.
     */
    @Test
    fun `only the hardware path needs the host to restore focus`() {
        assertTrue(needsExplicitFocusOnReshow(RenderingMode.HARDWARE_ACCELERATED))
        assertFalse(needsExplicitFocusOnReshow(RenderingMode.OFF_SCREEN))
    }

    /**
     * The rule the fix turns on, and the one no test could reach while it lived inside the
     * composable. Each clause is asserted by removing it and nothing else, because each is a
     * distinct bug: focusing on first show steals the URL bar from every new tab, and focusing in
     * an unfocused window is a background tab taking the keyboard from the one being used.
     */
    @Test
    fun `focus is taken only on a re-show, in the focused window, under hardware`() {
        assertTrue(
            shouldFocusOnShow(RenderingMode.HARDWARE_ACCELERATED, alreadyShown = true, hostWindowFocused = true),
            "the case the fix exists for: switching back to a tab in the window you are using",
        )

        assertFalse(
            shouldFocusOnShow(RenderingMode.HARDWARE_ACCELERATED, alreadyShown = false, hostWindowFocused = true),
            "a tab appearing for the first time must leave the caret in the URL bar",
        )
        assertFalse(
            shouldFocusOnShow(RenderingMode.HARDWARE_ACCELERATED, alreadyShown = true, hostWindowFocused = false),
            "a re-show in a background window must not take the keyboard",
        )
        assertFalse(
            shouldFocusOnShow(RenderingMode.OFF_SCREEN, alreadyShown = true, hostWindowFocused = true),
            "under OFF_SCREEN the widget owns focus and a host-side call fights it",
        )
    }

    // --- the JxBrowser API this rests on ---

    /**
     * Not a test of our code, and here on purpose: the fix replaced injected JavaScript with
     * `Frame.execute(EditorCommand)`, and this pins that the four commands it needs exist and are
     * distinct in whatever JxBrowser version the build resolves. A version bump that renamed or
     * merged one of them would otherwise surface as clipboard operations quietly doing the wrong
     * thing rather than as a failing build.
     */
    @Test
    fun `the four editor commands the clipboard needs are distinct`() {
        val names =
            listOf(
                EditorCommand.copy(),
                EditorCommand.cut(),
                EditorCommand.paste(),
                EditorCommand.selectAll(),
            ).map { it.name() }

        assertEquals(
            listOf(
                EditorCommand.Name.COPY,
                EditorCommand.Name.CUT,
                EditorCommand.Name.PASTE,
                EditorCommand.Name.SELECT_ALL,
            ),
            names,
        )
        assertEquals(names.size, names.toSet().size, "the four must not collapse onto each other")
    }

    // --- paste-without-formatting's clipboard restore (issue #205) ---

    /** A [Transferable] with no data: identity is the only thing these tests ask of it. */
    private class FakeTransferable : Transferable {
        override fun getTransferData(flavor: DataFlavor): Any = Any()
        override fun getTransferDataFlavors(): Array<DataFlavor> = emptyArray()
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = false
    }

    /** A stand-in clipboard: [PasteWithoutFormattingSession] reads and installs through it. */
    private class FakeClipboard {
        var contents: Transferable? = null
        val installed = mutableListOf<Transferable>()

        fun session() =
            PasteWithoutFormattingSession(
                currentContents = { contents },
                install = {
                    contents = it
                    installed += it
                },
            )
    }

    /**
     * The happy path, and the reason the round trip exists at all: the rich original comes
     * back after Chromium has consumed the plain text.
     */
    @Test
    fun `a single press restores the original it captured`() {
        val clip = FakeClipboard()
        val rich = FakeTransferable()
        clip.contents = rich
        val session = clip.session()

        val written = FakeTransferable()
        session.registerWrite(written)
        clip.contents = written

        assertTrue(session.tryRestore(), "the deferred restore must fire while our write is still current")
        assertSame(rich, clip.contents, "the restore puts back the content captured before the write")
    }

    /**
     * The clobber the unconditional 200ms restore caused: whatever the user copies inside the
     * window is a foreign Transferable, so it wins and the session retires - a second attempt
     * must not resurrect the restore.
     */
    @Test
    fun `a copy made inside the restore window wins and retires the session`() {
        val clip = FakeClipboard()
        clip.contents = FakeTransferable()
        val session = clip.session()

        val written = FakeTransferable()
        session.registerWrite(written)
        clip.contents = written

        val userCopy = FakeTransferable()
        clip.contents = userCopy

        assertFalse(session.tryRestore(), "a foreign Transferable must not be replaced")
        assertSame(userCopy, clip.contents, "the user's copy survives")
        assertFalse(session.tryRestore(), "a retired session must not fire again")
        assertEquals(0, clip.installed.size)
    }

    /**
     * The regression the PR review caught in the first cut: a text-equality guard cannot tell
     * "our write is still current" from "the rich original - whose string projection IS that
     * text - was already restored", so two presses inside the window ended with the second
     * restore putting the first press's plain write back over the rich original. Identity
     * tracking must converge a burst on the rich original, exactly once.
     */
    @Test
    fun `two presses inside the window restore the rich original, not the second press's plain text`() {
        val clip = FakeClipboard()
        val rich = FakeTransferable()
        clip.contents = rich
        // One session for both presses - FluckEngine holds it process-wide, which is what
        // makes a two-tab burst a single round trip.
        val session = clip.session()

        val firstWrite = FakeTransferable()
        session.registerWrite(firstWrite)
        clip.contents = firstWrite

        val secondWrite = FakeTransferable()
        session.registerWrite(secondWrite)
        clip.contents = secondWrite

        assertTrue(session.tryRestore(), "the first restore to fire still sees one of our writes")
        assertSame(rich, clip.contents, "the restore targets the pre-window original, not the previous write")
        assertFalse(session.tryRestore(), "and the second press's deferred restore must not run after it")
        assertEquals(1, clip.installed.size, "exactly one install for the whole burst")
    }

    /** A session with no outstanding write is inert: no read of the clipboard, no install. */
    @Test
    fun `a restore does not fire when nothing is pending`() {
        val clip = FakeClipboard()
        clip.contents = FakeTransferable()

        assertFalse(clip.session().tryRestore())
        assertEquals(0, clip.installed.size)
    }

    /**
     * The property that makes the session safe to leave installed process-wide: an empty
     * window captures whatever is current, so a press that follows a completed restore
     * restores the content it actually displaced - the already-restored rich original -
     * rather than something stale.
     */
    @Test
    fun `a press after a completed restore captures the restored content as its own original`() {
        val clip = FakeClipboard()
        val rich = FakeTransferable()
        clip.contents = rich
        val session = clip.session()

        val first = FakeTransferable()
        session.registerWrite(first)
        clip.contents = first
        session.tryRestore()
        assertSame(rich, clip.contents)

        val second = FakeTransferable()
        session.registerWrite(second)
        clip.contents = second
        session.tryRestore()
        assertSame(rich, clip.contents, "each press restores what it displaced")
        assertEquals(2, clip.installed.size)
    }
}
