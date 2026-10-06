package ai.rever.boss.plugin.browser

import ai.rever.boss.window.MainPanelFocusTracker
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BossConsole#1566: [resolveBrowserKeyboardOwner] is the one rule for "does a browser hold the
 * keyboard", which decides whether the AWT keymap's BROWSER-context bindings (Cmd+L above all)
 * can match. Each test is one row of the table in its KDoc.
 */
class BrowserKeyboardOwnerTest {
    @Test
    fun `no active browser in the window means no browser owns the keyboard`() {
        // A browser in a sidebar slot, or the background half of a split, is not the window's
        // active browser, so even its own focused page must not bring BROWSER context with it.
        assertEquals(BrowserKeyboardOwner.NONE, resolveBrowserKeyboardOwner(null, listOf("sidebar"), true))
        assertEquals(BrowserKeyboardOwner.NONE, resolveBrowserKeyboardOwner(null, emptyList(), true))
        assertEquals(BrowserKeyboardOwner.NONE, resolveBrowserKeyboardOwner(null, emptyList(), false))
    }

    @Test
    fun `the active browser's focused page owns the keyboard whatever Compose focus says`() {
        // HARDWARE_ACCELERATED: clicking the native page moves no Compose focus, so a sidebar
        // editor can still hold it. The page's own focus event is the truthful one.
        assertEquals(BrowserKeyboardOwner.PAGE, resolveBrowserKeyboardOwner("a", listOf("a"), false))
        assertEquals(BrowserKeyboardOwner.PAGE, resolveBrowserKeyboardOwner("a", listOf("a"), true))
    }

    @Test
    fun `another browser's focused page takes the keyboard away from the active one`() {
        // Keys are going to a browser this window's shortcuts would not act on, and Compose focus
        // left in the main panel is stale, so answering CHROME would act on the wrong browser.
        assertEquals(BrowserKeyboardOwner.NONE, resolveBrowserKeyboardOwner("a", listOf("b"), true))
    }

    @Test
    fun `Compose focus in the main panel is the chrome`() {
        assertEquals(BrowserKeyboardOwner.CHROME, resolveBrowserKeyboardOwner("a", emptyList(), true))
    }

    @Test
    fun `focus outside the main panel is not the browser`() {
        // The sidebar editor case: Cmd+L must stay Go To Line there.
        assertEquals(BrowserKeyboardOwner.NONE, resolveBrowserKeyboardOwner("a", emptyList(), false))
    }

    @Test
    fun `main panel focus survives a handoff between split halves in either order`() {
        val left = Any()
        val right = Any()

        // Gain on the right reported before the loss on the left.
        MainPanelFocusTracker.update(WINDOW, left, true)
        MainPanelFocusTracker.update(WINDOW, right, true)
        MainPanelFocusTracker.update(WINDOW, left, false)
        assertTrue(MainPanelFocusTracker.hasFocus(WINDOW))
        MainPanelFocusTracker.clearWindow(WINDOW)

        // Loss on the left reported before the gain on the right: a gap, then focus again.
        MainPanelFocusTracker.update(WINDOW, left, true)
        MainPanelFocusTracker.update(WINDOW, left, false)
        assertFalse(MainPanelFocusTracker.hasFocus(WINDOW))
        MainPanelFocusTracker.update(WINDOW, right, true)
        assertTrue(MainPanelFocusTracker.hasFocus(WINDOW))

        MainPanelFocusTracker.update(WINDOW, right, false)
        assertFalse(MainPanelFocusTracker.hasFocus(WINDOW))
    }

    @Test
    fun `main panel focus is per window and a closed window is forgotten`() {
        val panel = Any()
        MainPanelFocusTracker.update(WINDOW, panel, true)
        assertTrue(MainPanelFocusTracker.hasFocus(WINDOW))
        assertFalse(MainPanelFocusTracker.hasFocus(OTHER_WINDOW))
        MainPanelFocusTracker.clearWindow(WINDOW)
        assertFalse(MainPanelFocusTracker.hasFocus(WINDOW))
    }

    // The registry half: real registrations through a proxy handle, as BrowserPrintingTest does.

    @Test
    fun `the registry answers from its own registrations and page focus`() {
        register("kb-active", WINDOW, panelActive = true)
        register("kb-background", WINDOW, panelActive = false)
        register("kb-elsewhere", OTHER_WINDOW, panelActive = true)
        MainPanelFocusTracker.update(WINDOW, this, true)

        assertEquals(BrowserKeyboardOwner.CHROME, ActiveBrowserRegistry.keyboardOwnerIn(WINDOW, inWindowItself = true))
        // A dialog window owned by this one: its stale Compose focus must not count.
        assertEquals(BrowserKeyboardOwner.NONE, ActiveBrowserRegistry.keyboardOwnerIn(WINDOW, inWindowItself = false))

        // A page focused in ANOTHER window does not touch this one.
        ActiveBrowserRegistry.setPageFocused("kb-elsewhere", true)
        assertEquals(BrowserKeyboardOwner.CHROME, ActiveBrowserRegistry.keyboardOwnerIn(WINDOW, inWindowItself = true))

        // The background half's page has the keyboard: not this window's shortcut target.
        ActiveBrowserRegistry.setPageFocused("kb-background", true)
        assertEquals(BrowserKeyboardOwner.NONE, ActiveBrowserRegistry.keyboardOwnerIn(WINDOW, inWindowItself = true))

        // Unregistering clears page focus, so the veto goes with it.
        ActiveBrowserRegistry.unregister("kb-background")
        assertEquals(BrowserKeyboardOwner.CHROME, ActiveBrowserRegistry.keyboardOwnerIn(WINDOW, inWindowItself = true))

        ActiveBrowserRegistry.setPageFocused("kb-active", true)
        assertEquals(BrowserKeyboardOwner.PAGE, ActiveBrowserRegistry.keyboardOwnerIn(WINDOW, inWindowItself = true))
        // Keyboard in an owned dialog window: not even a focused page is the target.
        assertEquals(BrowserKeyboardOwner.NONE, ActiveBrowserRegistry.keyboardOwnerIn(WINDOW, inWindowItself = false))
    }

    @Test
    fun `a dead handle's focused page does not veto the window's browser`() {
        register("kb-active", WINDOW, panelActive = true)
        register("kb-dead", WINDOW, panelActive = false) { false }
        MainPanelFocusTracker.update(WINDOW, this, true)
        ActiveBrowserRegistry.setPageFocused("kb-dead", true)
        assertEquals(BrowserKeyboardOwner.CHROME, ActiveBrowserRegistry.keyboardOwnerIn(WINDOW, inWindowItself = true))
    }

    private val registered = mutableListOf<String>()

    private fun register(
        id: String,
        windowId: String,
        panelActive: Boolean,
        isValid: () -> Boolean = { true },
    ) {
        val handle =
            Proxy.newProxyInstance(
                BrowserHandle::class.java.classLoader,
                arrayOf(BrowserHandle::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getId" -> id
                    "isValid" -> isValid()
                    else -> error("Unexpected call ${method.name}")
                }
            } as BrowserHandle
        ActiveBrowserRegistry.register(handle, windowId, inMainPanel = true, panelActive = panelActive)
        registered += id
    }

    @AfterTest
    fun tearDown() {
        registered.forEach(ActiveBrowserRegistry::unregister)
        MainPanelFocusTracker.clearWindow(WINDOW)
        MainPanelFocusTracker.clearWindow(OTHER_WINDOW)
    }

    private companion object {
        const val WINDOW = "owner-test-w1"
        const val OTHER_WINDOW = "owner-test-w2"
    }
}
