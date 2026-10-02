package ai.rever.boss.window

import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.keymap.model.ShortcutContext
import ai.rever.boss.keymap.presets.KeymapPresets
import ai.rever.boss.plugin.browser.BrowserKeyboardOwner
import ai.rever.boss.plugin.browser.resolveBrowserKeyboardOwner
import ai.rever.boss.utils.SystemUtils
import java.awt.Canvas
import java.awt.Container
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * BossConsole#1566: BROWSER-context bindings could never match in a main-window browser tab,
 * because the context came only from an AWT focus walk that cannot see the Compose
 * `BrowserView`. Cmd+L (Focus Address Bar) did nothing.
 *
 * Drives [resolveKeyboardContext] and [AWTKeyboardInterceptor.matchBinding]
 * against the shipped BOSS Default preset rather than the on-disk keymap, for the reason
 * [ShortcutKeyUpInvocationTest] gives.
 */
class BrowserContextShortcutTest {
    private val bindings = KeymapPresets.getBOSSDefault().shortcuts.values
    private val source = Canvas()
    private val primaryMask = if (SystemUtils.isMacOS) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK

    private fun press(
        keyCode: Int,
        extraMask: Int = 0,
    ) = KeyEvent(source, KeyEvent.KEY_PRESSED, 0L, primaryMask or extraMask, keyCode, KeyEvent.CHAR_UNDEFINED)

    /** The whole path from the two focus signals to the matched action. */
    private fun actionFor(
        keyCode: Int,
        awtContext: ShortcutContext = ShortcutContext.GLOBAL,
        owner: BrowserKeyboardOwner,
    ): String? {
        val context = resolveKeyboardContext(awtContext, owner)
        return AWTKeyboardInterceptor.matchBinding(press(keyCode), bindings, context)?.binding?.actionId
    }

    private val focusAddressBar = KeymapPresets.FLUCK_FOCUS_ADDRESS_BAR_ACTION

    @Test
    fun `Cmd+L focuses the address bar with the keyboard in the browser's chrome or page`() {
        val chrome = resolveBrowserKeyboardOwner("tab", emptyList(), mainPanelHasComposeFocus = true)
        assertEquals(focusAddressBar, actionFor(KeyEvent.VK_L, owner = chrome))

        val page = resolveBrowserKeyboardOwner("tab", listOf("tab"), mainPanelHasComposeFocus = false)
        assertEquals(focusAddressBar, actionFor(KeyEvent.VK_L, owner = page))
    }

    @Test
    fun `Cmd+L is not the address bar with the keyboard in an editor`() {
        // A sidebar editor beside the browser, and an editor tab with no browser active.
        val sidebar = resolveBrowserKeyboardOwner("tab", emptyList(), mainPanelHasComposeFocus = false)
        assertNotEquals(focusAddressBar, actionFor(KeyEvent.VK_L, owner = sidebar))

        val editorTab = resolveBrowserKeyboardOwner(null, emptyList(), mainPanelHasComposeFocus = true)
        assertNotEquals(focusAddressBar, actionFor(KeyEvent.VK_L, owner = editorTab))
    }

    @Test
    fun `a terminal keeps TERMINAL unless the page really has the keyboard`() {
        listOf(BrowserKeyboardOwner.CHROME, BrowserKeyboardOwner.NONE).forEach { owner ->
            assertEquals(
                ShortcutContext.TERMINAL,
                resolveKeyboardContext(ShortcutContext.TERMINAL, owner).context,
                owner.name,
            )
        }
        // Clicking the native page leaves the terminal as the AWT focus owner; the page wins.
        assertEquals(
            KeyboardContext(ShortcutContext.BROWSER, pageFocused = true),
            resolveKeyboardContext(ShortcutContext.TERMINAL, BrowserKeyboardOwner.PAGE),
        )
    }

    @Test
    fun `the Swing fullscreen browser keeps its answer and is not treated as the native page`() {
        assertEquals(
            KeyboardContext(ShortcutContext.BROWSER),
            resolveKeyboardContext(ShortcutContext.BROWSER, BrowserKeyboardOwner.NONE),
        )
        assertEquals(
            KeymapActions.BROWSER_FIND,
            actionFor(KeyEvent.VK_F, ShortcutContext.BROWSER, BrowserKeyboardOwner.PAGE),
        )
    }

    @Test
    fun `nothing reports BROWSER without a browser holding the keyboard`() {
        assertEquals(
            ShortcutContext.GLOBAL,
            resolveKeyboardContext(ShortcutContext.GLOBAL, BrowserKeyboardOwner.NONE).context,
        )
    }

    @Test
    fun `find, reload and print stay with the page's native key callback while the page has focus`() {
        // FluckEngine's PressKeyCallback serves these for a focused page; matching them here too
        // would act twice, and for find would take the chord before the page can pre-empt it.
        //
        // "Stay with" means the chord matches exactly what it matched before BROWSER context was
        // reachable here, when the context was GLOBAL: nothing, or a GLOBAL binding on that chord.
        listOf(KeyEvent.VK_F, KeyEvent.VK_R, KeyEvent.VK_P).forEach { keyCode ->
            val action = actionFor(keyCode, owner = BrowserKeyboardOwner.PAGE)
            assertFalse(action in PAGE_SERVED_ACTIONS, "key $keyCode -> $action")
            assertEquals(actionFor(keyCode, owner = BrowserKeyboardOwner.NONE), action, "key $keyCode")
        }
        // BOSS Default has no GLOBAL binding on these, so there the page gets the chord alone.
        assertNull(actionFor(KeyEvent.VK_F, owner = BrowserKeyboardOwner.PAGE))
        assertNull(actionFor(KeyEvent.VK_R, owner = BrowserKeyboardOwner.PAGE))
    }

    @Test
    fun `a GLOBAL binding on a page-served chord keeps it, as it did before`() {
        // VS Code: Cmd+P is quick open (GLOBAL), and there is no print binding at all.
        val vsCode = KeymapPresets.getVSCodePreset().shortcuts.values
        val page = resolveKeyboardContext(ShortcutContext.GLOBAL, BrowserKeyboardOwner.PAGE)
        val global = resolveKeyboardContext(ShortcutContext.GLOBAL, BrowserKeyboardOwner.NONE)
        assertEquals(
            AWTKeyboardInterceptor.matchBinding(press(KeyEvent.VK_P), vsCode, global)?.binding?.actionId,
            AWTKeyboardInterceptor.matchBinding(press(KeyEvent.VK_P), vsCode, page)?.binding?.actionId,
        )
    }

    @Test
    fun `the AWT focus walk recognises the Swing browser and BossTerm by ancestor class`() {
        assertEquals(ShortcutContext.GLOBAL, detectContextFromAwtComponent(null))
        assertEquals(ShortcutContext.GLOBAL, detectContextFromAwtComponent(Container()))
        assertEquals(ShortcutContext.BROWSER, detectContextFromAwtComponent(child(FakeJxBrowserView())))
        assertEquals(ShortcutContext.TERMINAL, detectContextFromAwtComponent(child(FakeTerminalPanel())))
        // The nearest match wins walking up: a terminal inside a browser view is a terminal.
        assertEquals(
            ShortcutContext.TERMINAL,
            detectContextFromAwtComponent(child(FakeTerminalPanel().also { FakeJxBrowserView().add(it) })),
        )
    }

    private class FakeJxBrowserView : Container()

    private class FakeTerminalPanel : Container()

    private fun child(parent: Container): Container = Container().also { parent.add(it) }

    @Test
    fun `find, reload and print are served from the chrome, which has no native callback`() {
        assertEquals(KeymapActions.BROWSER_FIND, actionFor(KeyEvent.VK_F, owner = BrowserKeyboardOwner.CHROME))
        assertEquals(KeymapActions.BROWSER_RELOAD, actionFor(KeyEvent.VK_R, owner = BrowserKeyboardOwner.CHROME))
        assertEquals(KeymapActions.BROWSER_PRINT, actionFor(KeyEvent.VK_P, owner = BrowserKeyboardOwner.CHROME))
    }

    @Test
    fun `history and DevTools match for a focused page, which has no native handler for them`() {
        assertEquals(
            KeymapActions.BROWSER_BACK,
            actionFor(KeyEvent.VK_OPEN_BRACKET, owner = BrowserKeyboardOwner.PAGE),
        )
        assertEquals(
            KeymapActions.BROWSER_FORWARD,
            actionFor(KeyEvent.VK_CLOSE_BRACKET, owner = BrowserKeyboardOwner.PAGE),
        )
        val devTools =
            AWTKeyboardInterceptor.matchBinding(
                press(KeyEvent.VK_I, InputEvent.ALT_DOWN_MASK),
                bindings,
                resolveKeyboardContext(ShortcutContext.GLOBAL, BrowserKeyboardOwner.PAGE),
            )
        assertEquals(KeymapActions.BROWSER_DEVTOOLS, devTools?.binding?.actionId)
    }
}
