package ai.rever.boss.plugin.browser

import com.teamdev.jxbrowser.engine.RenderingMode
import com.teamdev.jxbrowser.frame.EditorCommand
import com.teamdev.jxbrowser.frame.Frame
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    private val authority = BrowserMenuContextAuthority()

    private fun executeEditorCommand(
        menuContext: BrowserMenuContext?,
        focusedFrame: Frame?,
        mainFrame: Frame?,
        command: EditorCommand,
    ): Boolean =
        ai.rever.boss.plugin.browser
            .executeEditorCommand(menuContext, focusedFrame, mainFrame, command, authority)

    /** A [Frame] that records the commands it is asked to run and answers [accepts]. */
    private class RecordingFrame(
        private val accepts: Boolean = true,
        private val closed: Boolean = false,
    ) {
        val executed = mutableListOf<EditorCommand>()

        val frame: Frame =
            Proxy.newProxyInstance(
                Frame::class.java.classLoader,
                arrayOf(Frame::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "execute" -> {
                        check(!closed) { "Frame is closed" }
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
    fun `the context menu frame beats the focused frame, which beats the main frame`() {
        val menu = RecordingFrame()
        val focused = RecordingFrame()
        val main = RecordingFrame()

        val context = authority.capture(menu.frame)
        executeEditorCommand(context, focused.frame, main.frame, EditorCommand.copy())

        assertEquals(1, menu.executed.size, "the exact frame the menu was opened on must be used")
        assertEquals(EditorCommand.Name.COPY, menu.executed.single().name())
        assertTrue(focused.executed.isEmpty(), "a focused frame must be ignored if a menu frame exists")
        assertTrue(main.executed.isEmpty(), "the main frame must not be used")
    }

    @Test
    fun `the focused frame is the fallback when no menu frame is held`() {
        val focused = RecordingFrame()
        val main = RecordingFrame()

        executeEditorCommand(
            menuContext = null,
            focusedFrame = focused.frame,
            mainFrame = main.frame,
            command = EditorCommand.copy(),
        )

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
    fun `the main frame is the final fallback when nothing is focused`() {
        val main = RecordingFrame()

        executeEditorCommand(
            menuContext = null,
            focusedFrame = null,
            mainFrame = main.frame,
            command = EditorCommand.paste(),
        )

        assertEquals(EditorCommand.Name.PASTE, main.executed.single().name())
    }

    /** A closed or still-loading browser has neither frame. Refusing beats throwing at a menu. */
    @Test
    fun `no frame at all is a refusal, not a throw`() {
        assertFalse(
            executeEditorCommand(
                menuContext = null,
                focusedFrame = null,
                mainFrame = null,
                command = EditorCommand.cut(),
            ),
        )
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

        assertFalse(
            executeEditorCommand(
                menuContext = null,
                focusedFrame = refusing.frame,
                mainFrame = null,
                command = EditorCommand.copy(),
            ),
        )
        assertTrue(
            executeEditorCommand(
                menuContext = null,
                focusedFrame = RecordingFrame(accepts = true).frame,
                mainFrame = null,
                command = EditorCommand.copy(),
            ),
        )
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

    @Test
    fun `Test B - dismissed menu does not poison ordinary commands`() {
        val iframeA = RecordingFrame()
        val focusedB = RecordingFrame()
        val main = RecordingFrame()

        // Simulating a menu created for A
        // But the user dismisses the menu and triggers an ordinary paste (menuContext = null)
        executeEditorCommand(
            menuContext = null,
            focusedFrame = focusedB.frame,
            mainFrame = main.frame,
            command = EditorCommand.paste(),
        )
        assertTrue(iframeA.executed.isEmpty(), "a stale menu frame must not be used by ordinary commands")
        assertEquals(1, focusedB.executed.size)
    }

    @Test
    fun `Test C - two distinct menu contexts do not overwrite each other`() {
        val iframeA = RecordingFrame()
        val iframeB = RecordingFrame()

        val contextA = authority.capture(iframeA.frame)
        val contextB = authority.capture(iframeB.frame)

        executeEditorCommand(contextA, null, null, EditorCommand.copy())
        executeEditorCommand(contextB, null, null, EditorCommand.paste())

        assertEquals(1, iframeA.executed.size)
        assertEquals(EditorCommand.Name.COPY, iframeA.executed.single().name())

        assertEquals(1, iframeB.executed.size)
        assertEquals(EditorCommand.Name.PASTE, iframeB.executed.single().name())
    }

    @Test
    fun `Test D - menu context ignores changed focus`() {
        val iframeA = RecordingFrame()
        val iframeB = RecordingFrame()

        val contextA = authority.capture(iframeA.frame)

        // Execute with context A, but focus has changed to iframeB
        executeEditorCommand(contextA, iframeB.frame, null, EditorCommand.copy())

        assertEquals(1, iframeA.executed.size, "menu context must win over changed focus")
        assertTrue(iframeB.executed.isEmpty(), "changed focus must be ignored")
    }

    @Test
    fun `Test F - invalid or closed menu frame fails safely without falling back`() {
        val closedFrame = RecordingFrame(accepts = false)
        val focused = RecordingFrame()
        val main = RecordingFrame()

        val contextForClosed = authority.capture(closedFrame.frame)

        // This should return the result of the frame execution (which is false for our mock),
        // but importantly, it should NOT fall back to focused or main frame
        assertFalse(
            executeEditorCommand(
                menuContext = contextForClosed,
                focusedFrame = focused.frame,
                mainFrame = main.frame,
                command = EditorCommand.cut(),
            ),
        )

        assertEquals(1, closedFrame.executed.size)
        assertTrue(focused.executed.isEmpty(), "must not fall back to focused frame")
        assertTrue(main.executed.isEmpty(), "must not fall back to main frame")

        // What if the weak reference itself is cleared?
        val nullContext = authority.capture(null)
        assertFalse(
            executeEditorCommand(
                menuContext = nullContext,
                focusedFrame = focused.frame,
                mainFrame = main.frame,
                command = EditorCommand.cut(),
            ),
        )
        // Ensure no fallback executed
        assertTrue(focused.executed.isEmpty())
        assertTrue(main.executed.isEmpty())
    }

    @Test
    fun `Test 6 - menuContext from BrowserContextMenuInfo propagates to editor command`() {
        val menu = RecordingFrame()
        val context = authority.capture(menu.frame)

        // Simulate what a plugin does: receive BrowserContextMenuInfo, extract menuContext,
        // and pass it to an editor command.
        val info = BrowserContextMenuInfo(menuContext = context)
        val extractedContext = info.menuContext

        // The extracted token must be the same instance - identity, not equality.
        assertSame(context, extractedContext)

        // And it must successfully route to the correct frame.
        executeEditorCommand(extractedContext, null, null, EditorCommand.copy())
        assertEquals(1, menu.executed.size)
    }

    @Test
    fun `Test 7 - executeEditorCommand has no hidden global state dependency`() {
        // This test proves the function is pure with respect to frame selection:
        // calling it twice with different contexts routes to different frames,
        // and calling it without context never consults any prior menu state.
        val frameA = RecordingFrame()
        val frameB = RecordingFrame()
        val main = RecordingFrame()

        val contextA = authority.capture(frameA.frame)

        // 1. Use context A
        executeEditorCommand(contextA, frameB.frame, main.frame, EditorCommand.copy())
        assertEquals(1, frameA.executed.size)
        assertTrue(frameB.executed.isEmpty())
        assertTrue(main.executed.isEmpty())

        // 2. Now call WITHOUT context — must not use A
        executeEditorCommand(null, frameB.frame, main.frame, EditorCommand.paste())
        assertEquals(1, frameA.executed.size, "frameA must not receive a second command")
        assertEquals(1, frameB.executed.size, "focused frame must be used when no context")
        assertTrue(main.executed.isEmpty())

        // 3. Call WITHOUT context and without focused frame — main frame fallback
        executeEditorCommand(null, null, main.frame, EditorCommand.selectAll())
        assertEquals(1, main.executed.size)
    }

    @Test
    fun `foreign handle tokens fail closed without executing any frame`() {
        val menu = RecordingFrame()
        val focused = RecordingFrame()
        val foreign = BrowserMenuContextAuthority().capture(menu.frame)
        assertFalse(executeEditorCommand(foreign, focused.frame, focused.frame, EditorCommand.paste()))
        assertTrue(menu.executed.isEmpty())
        assertTrue(focused.executed.isEmpty())
    }

    @Test
    fun `revoked tokens fail closed and new tokens still work`() {
        val menu = RecordingFrame()
        val focused = RecordingFrame()
        val stale = authority.capture(menu.frame)
        // Navigation, callback replacement/removal, and disposal all revoke the authority.
        authority.invalidate()
        assertFalse(executeEditorCommand(stale, focused.frame, focused.frame, EditorCommand.paste()))
        assertTrue(menu.executed.isEmpty())
        assertTrue(focused.executed.isEmpty())
        assertTrue(executeEditorCommand(authority.capture(menu.frame), null, null, EditorCommand.copy()))
    }

    @Test
    fun `unknown token and missing clicked frame never use ordinary fallback`() {
        val focused = RecordingFrame()
        for (token in listOf(object : BrowserMenuContext {}, authority.capture(null))) {
            assertFalse(executeEditorCommand(token, focused.frame, focused.frame, EditorCommand.cut()))
        }
        assertTrue(focused.executed.isEmpty())
    }

    @Test
    fun `legacy menu copy preserves content but drops transient frame authority`() {
        val info = BrowserContextMenuInfo(pageUrl = "https://example.test", menuContext = authority.capture(null))
        assertEquals(info, info.copy())
        assertEquals(null, info.copy().menuContext)
    }

    @Test
    fun `revocation while reading native menu params cannot refresh an old frame token`() {
        val menu = RecordingFrame()
        val beforeRead = authority.snapshot()
        authority.invalidate()
        val stale = authority.capture(menu.frame, beforeRead)
        assertFalse(executeEditorCommand(stale, menu.frame, menu.frame, EditorCommand.paste()))
        assertTrue(menu.executed.isEmpty())
    }

    @Test
    fun `closed frame exception never falls back to another frame`() {
        val closed = RecordingFrame(closed = true)
        val focused = RecordingFrame()
        // BrowserHandleImpl catches this Exception and reports refusal; the selector must
        // propagate it without trying a different document.
        assertFailsWith<IllegalStateException> {
            executeEditorCommand(authority.capture(closed.frame), focused.frame, focused.frame, EditorCommand.paste())
        }
        assertTrue(closed.executed.isEmpty())
        assertTrue(focused.executed.isEmpty())
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

    /** A rich-content stand-in distinct from the plain substitution even when its text matches. */
    private class FakeTransferable : Transferable {
        override fun getTransferData(flavor: DataFlavor): Any = "same text"

        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.stringFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.stringFlavor
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

        val written = checkNotNull(session.beginPaste())

        assertTrue(session.tryRestore(written), "the deferred restore must fire while our write is still current")
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

        val written = checkNotNull(session.beginPaste())

        val userCopy = FakeTransferable()
        clip.contents = userCopy

        assertFalse(session.tryRestore(written), "a foreign Transferable must not be replaced")
        assertSame(userCopy, clip.contents, "the user's copy survives")
        assertFalse(session.tryRestore(written), "a retired session must not fire again")
        assertEquals(1, clip.installed.size)
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

        val firstWrite = checkNotNull(session.beginPaste())

        val secondWrite = checkNotNull(session.beginPaste())

        assertTrue(session.tryRestore(secondWrite), "the latest write restores the original")
        assertSame(rich, clip.contents, "the restore targets the pre-window original, not the previous write")
        assertFalse(session.tryRestore(firstWrite), "and an earlier timer must not restore again")
        assertEquals(3, clip.installed.size, "two plain writes and exactly one restore for the burst")
    }

    /** A session with no outstanding write is inert: no read of the clipboard, no install. */
    @Test
    fun `a restore does not fire when nothing is pending`() {
        val clip = FakeClipboard()
        clip.contents = FakeTransferable()

        assertFalse(clip.session().tryRestore(Any()))
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

        val first = checkNotNull(session.beginPaste())
        session.tryRestore(first)
        assertSame(rich, clip.contents)

        val second = checkNotNull(session.beginPaste())
        session.tryRestore(second)
        assertSame(rich, clip.contents, "each press restores what it displaced")
        assertEquals(4, clip.installed.size)
    }
}
