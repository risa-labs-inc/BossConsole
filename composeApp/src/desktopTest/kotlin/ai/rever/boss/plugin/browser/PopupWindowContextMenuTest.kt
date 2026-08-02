package ai.rever.boss.plugin.browser

import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.browser.callback.ShowContextMenuCallback
import com.teamdev.jxbrowser.frame.Frame
import com.teamdev.jxbrowser.menu.ContextMenuContentType
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.lang.reflect.Proxy
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
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
 * blocking IPC on the JxBrowser callback thread, which would delay the menu on every right-click.
 */
class PopupWindowContextMenuTest {
    private fun entries(
        editable: Boolean,
        selection: String,
        clipboard: Boolean = true,
    ) = popupMenuEntriesFor(
        contentTypes = if (editable) listOf(ContextMenuContentType.EDITABLE) else listOf(ContextMenuContentType.PAGE),
        selectedText = selection,
        clipboardHasText = { clipboard },
    )

    /**
     * A do-nothing [Frame]. `buildMenu` only stores it for the action listeners, so a reflection
     * proxy is enough to render the menu headlessly — the build has no mocking library.
     *
     * `Object`'s methods are handled explicitly: returning null for `hashCode()` would NPE on
     * unboxing the moment the stub landed in a hash collection or an assertion message. Nothing
     * here does that today, but stubs get copied.
     */
    private fun stubFrame(executed: MutableList<Any?> = mutableListOf()): Frame =
        Proxy.newProxyInstance(Frame::class.java.classLoader, arrayOf(Frame::class.java)) { proxy, method, args ->
            when {
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === args?.getOrNull(0)
                method.name == "toString" -> "stubFrame"
                method.name == "execute" -> executed.add(args?.getOrNull(0)).let { null }
                method.returnType == Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        } as Frame

    /**
     * A [Browser] that records the callback types installed on it, optionally failing one of them.
     *
     * Same reflection-proxy technique as [stubFrame], for the same reason: no mocking library.
     */
    private fun recordingBrowser(
        installed: MutableList<String>,
        failOn: String? = null,
        captureMenuCallback: ((ShowContextMenuCallback) -> Unit)? = null,
    ): Browser =
        Proxy.newProxyInstance(
            Browser::class.java.classLoader,
            arrayOf(Browser::class.java),
        ) { proxy, method, args ->
            when {
                method.name == "hashCode" -> {
                    System.identityHashCode(proxy)
                }

                method.name == "equals" -> {
                    proxy === args?.getOrNull(0)
                }

                method.name == "toString" -> {
                    "stubBrowser"
                }

                method.name == "set" -> {
                    installed += (args?.getOrNull(0) as? Class<*>)?.simpleName.orEmpty()
                    (args?.getOrNull(1) as? ShowContextMenuCallback)?.let { captureMenuCallback?.invoke(it) }
                    if (installed.last() == failOn) error("simulated set() failure")
                    null
                }

                method.returnType == Boolean::class.javaPrimitiveType -> {
                    false
                }

                else -> {
                    null
                }
            }
        } as Browser

    // --- menu contents ---

    @Test
    fun `the menu is Cut Copy Paste, a separator, then Select All`() {
        // Order and the separator's position are the whole visual contract.
        val menu = entries(editable = true, selection = "x")
        assertEquals(listOf("Cut", "Copy", "Paste", "", "Select All"), menu.map { it.label })
        assertTrue(menu[3].isSeparator)
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
    fun `Paste needs something on the clipboard`() {
        // Without this the KDoc's "enablement mirrors what the command would actually do" was a
        // claim the code did not honour: Paste was offered on any editable target.
        assertFalse(entries(editable = true, selection = "", clipboard = false).single { it.label == "Paste" }.enabled)
        assertTrue(entries(editable = true, selection = "", clipboard = true).single { it.label == "Paste" }.enabled)
    }

    @Test
    fun `the clipboard is not probed on a non-editable target`() {
        // Paste is disabled on a read-only page whatever the clipboard says, so probing it there is
        // pure latency on the right-click path - and a Windows clipboard read is a retry-with-sleep
        // loop, not a free call. That is why the parameter is a lambda.
        var probes = 0
        popupMenuEntriesFor(
            contentTypes = listOf(ContextMenuContentType.PAGE),
            selectedText = "abc",
            clipboardHasText = {
                probes++
                true
            },
        )
        assertEquals(0, probes, "a read-only target should not read the clipboard")

        popupMenuEntriesFor(
            contentTypes = listOf(ContextMenuContentType.EDITABLE),
            selectedText = "abc",
            clipboardHasText = {
                probes++
                true
            },
        )
        assertEquals(1, probes, "an editable target must read the clipboard to decide Paste")
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
    fun `separators are entries with no command`() {
        assertTrue(PopupMenuEntry("", command = null, enabled = false).isSeparator)
        assertFalse(entries(editable = true, selection = "x").single { it.label == "Copy" }.isSeparator)
    }

    // --- the guard that actually prevents the reported crash ---

    @Test
    fun `the menu is not shown once the view has stopped showing`() {
        // This is the crash. JxBrowser's built-in menu asked a disposed component for its location
        // on screen; this path refuses to render at all instead.
        assertFalse(
            shouldShowPopupMenu(
                viewShowing = false,
                browserClosed = false,
                entries = entries(editable = true, selection = "x"),
            ),
        )
    }

    @Test
    fun `the menu is not shown once the popup browser has closed`() {
        // An OAuth popup closes itself when the flow completes, which is the race in the report.
        assertFalse(
            shouldShowPopupMenu(
                viewShowing = true,
                browserClosed = true,
                entries = entries(editable = true, selection = "x"),
            ),
        )
    }

    @Test
    fun `the menu is not shown when nothing in it would be enabled`() {
        // Four greyed-out items is worse than no menu. Hand-built because popupMenuEntriesFor
        // cannot currently produce such a list - Select All is unconditionally enabled.
        val allDisabled = entries(editable = false, selection = "").map { it.copy(enabled = false) }
        assertFalse(shouldShowPopupMenu(viewShowing = true, browserClosed = false, entries = allDisabled))
        assertFalse(shouldShowPopupMenu(viewShowing = true, browserClosed = false, entries = emptyList()))
    }

    @Test
    fun `the menu is shown when the view is live and something is enabled`() {
        assertTrue(
            shouldShowPopupMenu(
                viewShowing = true,
                browserClosed = false,
                entries = entries(editable = true, selection = "x"),
            ),
        )
    }

    // --- rendering ---

    @Test
    fun `entries render in order with the separator between Paste and Select All`() {
        val menu: JPopupMenu = buildPopupWindowMenu(stubFrame(), entries(editable = true, selection = "x"))
        val rendered = menu.components.map { (it as? JMenuItem)?.text ?: "<separator>" }
        assertEquals(listOf("Cut", "Copy", "Paste", "<separator>", "Select All"), rendered)
    }

    @Test
    fun `disabled entries render disabled`() {
        // Read-only page with no selection: only Select All should be clickable.
        val menu = buildPopupWindowMenu(stubFrame(), entries(editable = false, selection = ""))
        val enabledLabels =
            menu.components
                .filterIsInstance<JMenuItem>()
                .filter { it.isEnabled }
                .map { it.text }
        assertEquals(listOf("Select All"), enabledLabels)
    }

    @Test
    fun `clicking an item runs its command on the frame the click resolved to`() {
        // The menu renders correctly per the tests above, but rendering a Paste item that is wired
        // to nothing would pass all of them. This is the one assertion that the menu does anything.
        val executed = mutableListOf<Any?>()
        val entries = entries(editable = true, selection = "x")
        val menu = buildPopupWindowMenu(stubFrame(executed), entries)
        menu.components
            .filterIsInstance<JMenuItem>()
            .single { it.text == "Paste" }
            .doClick(0)
        assertEquals<Any?>(entries.single { it.label == "Paste" }.command, executed.singleOrNull())
    }

    @Test
    fun `nothing is shown against a view that is not showing`() {
        // The guard was previously only tested as a pure function, so deleting its call site left
        // every test passing. This drives the real show path: a JPanel that was never realised is
        // the disposed popup's situation, and rendering into it is what threw in the report.
        val shown =
            showPopupMenuIfPossible(
                view = JPanel(),
                browserClosed = { false },
                target = stubFrame(),
                entries = entries(editable = true, selection = "x"),
                at = Point(10, 10),
            )
        assertFalse(shown, "a view that is not showing must not host a menu")
    }

    @Test
    fun `a browser-closed check that throws does not escape onto the EDT`() {
        // popupBrowser.isClosed is a call into JxBrowser during teardown. An uncaught throw from
        // the guard itself would be the same failure class this file removes.
        val shown =
            showPopupMenuIfPossible(
                view = JPanel(),
                browserClosed = { error("simulated teardown") },
                target = stubFrame(),
                entries = entries(editable = true, selection = "x"),
                at = Point(0, 0),
            )
        assertFalse(shown)
    }

    // --- install contract ---

    @Test
    fun `the menu and the dismiss handler are both installed, menu first`() {
        // They are a pair: a Swing menu over a heavyweight browser surface does not close when the
        // user clicks back into the page. The failure-path test below covers a throwing install;
        // this covers the ordinary one, so dropping the dismissal on success would be caught too.
        val installed = mutableListOf<String>()
        installPopupWindowChrome(recordingBrowser(installed), JPanel())
        assertEquals(listOf("ShowContextMenuCallback", "PressMouseCallback"), installed)
    }

    @Test
    fun `the callback answers Chromium even when reading the click target throws`() {
        // tell.close() in the finally is the only thing between a params read that throws and a
        // request nobody answers - not a menu that fails to appear, a renderer left waiting on one.
        // It is also what suppresses the built-in (crashing) menu, so it has to run on every path.
        var callback: ShowContextMenuCallback? = null
        installPopupWindowContextMenu(
            recordingBrowser(mutableListOf(), captureMenuCallback = { callback = it }),
            JPanel(),
        )
        val installed = requireNotNull(callback) { "no ShowContextMenuCallback was installed" }

        val params =
            Proxy.newProxyInstance(
                ShowContextMenuCallback.Params::class.java.classLoader,
                arrayOf(ShowContextMenuCallback.Params::class.java),
            ) { _, method, _ ->
                if (method.name == "location") error("simulated params failure") else null
            } as ShowContextMenuCallback.Params

        val tell = ShowContextMenuCallback.Action { }
        installed.on(params, tell)

        assertTrue(tell.isClosed, "Chromium must be answered on the params-failure path too")
    }

    @Test
    fun `a menu-install failure neither propagates nor skips the dismiss handler`() {
        // The most consequential bug this change produced: installPopupWindowChrome runs before
        // the popup window is made visible, inside a caller try/catch that closes the browser, so
        // a throwing set() meant the OAuth window never opened at all - worse than the crash.
        // Both halves are asserted: the call is swallowed, AND PressMouseCallback still lands.
        val installed = mutableListOf<String>()

        // Must not throw.
        installPopupWindowChrome(recordingBrowser(installed, failOn = "ShowContextMenuCallback"), JPanel())

        assertTrue("ShowContextMenuCallback" in installed, "the menu install should have been attempted")
        assertTrue(
            "PressMouseCallback" in installed,
            "the dismiss handler must still be installed after a menu-install failure",
        )
    }

    // --- clipboard ---

    @Test
    fun `clipboard probe defaults to enabled when the clipboard cannot be read`() {
        // On a headless CI agent this exercises the HeadlessException branch - an
        // UnsupportedOperationException, not an IllegalStateException, which is the case round 5
        // added. On a desktop it only asserts the call is safe: it must not throw.
        clipboardHasText()
        if (GraphicsEnvironment.isHeadless()) {
            assertTrue(clipboardHasText(), "headless must default to enabled, not throw or disable")
        }
    }

    @Test
    fun `the rendered menu is heavyweight so it can paint over the browser surface`() {
        // A lightweight popup draws into the Swing layer, which sits behind the heavyweight browser
        // surface - the menu would silently not appear.
        val menu = buildPopupWindowMenu(stubFrame(), entries(editable = true, selection = "x"))
        assertFalse(menu.isLightWeightPopupEnabled)
    }
}
