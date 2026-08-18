package ai.rever.boss.plugin.browser

import com.teamdev.jxbrowser.engine.RenderingMode
import com.teamdev.jxbrowser.frame.EditorCommand
import com.teamdev.jxbrowser.frame.Frame
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
