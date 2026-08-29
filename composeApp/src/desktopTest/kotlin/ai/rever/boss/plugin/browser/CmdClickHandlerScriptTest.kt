package ai.rever.boss.plugin.browser

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The cmd+click handler runs in the page and hijacks the click before the page sees it, so every
 * link it claims wrongly is a link the page can no longer handle itself. There is no JS runtime on
 * this source set, so what is pinned here is that each guard is present at all - the failure mode
 * being guarded against is a guard quietly dropped in a rewrite, not a subtly wrong one.
 */
class CmdClickHandlerScriptTest {
    private val script = BrowserJavaScripts.injectCmdClickHandler

    @Test
    fun `it acts only on a modifier-held primary click that nothing has already cancelled`() {
        assertTrue(script.contains("event.metaKey || event.ctrlKey"), "no modifier gate:\n$script")
        assertTrue(script.contains("event.button !== 0"), "a middle or right click would be claimed:\n$script")
        assertTrue(
            script.contains("event.defaultPrevented"),
            "a click the page already cancelled would still open a tab:\n$script",
        )
    }

    @Test
    fun `it leaves downloads and non-web schemes to the page`() {
        assertTrue(
            script.contains("link.hasAttribute('download')"),
            "a download anchor would be opened as a tab instead of saved:\n$script",
        )
        assertTrue(
            script.contains("protocol !== 'http:'") && script.contains("protocol !== 'https:'"),
            "javascript:, mailto:, blob: and data: would be handed to window.open:\n$script",
        )
    }

    @Test
    fun `it still opens the link in a new tab, once, on the capture phase`() {
        assertTrue(script.contains("window.open(link.href, '_blank')"), "the handler no longer opens anything")
        // preventDefault stops Chromium opening its own tab alongside ours.
        assertTrue(script.contains("event.preventDefault()"), "the page would navigate as well as opening a tab")
        assertTrue(script.contains("}, true);"), "not registered on the capture phase")
        assertTrue(script.contains("window._cmdClickHandlerAdded"), "re-injection would stack duplicate handlers")
    }
}
