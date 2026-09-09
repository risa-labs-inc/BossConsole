package ai.rever.boss.plugin.browser

import com.teamdev.jxbrowser.browser.Browser
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class BrowserChromeTest {
    private fun browser(close: () -> Unit): Browser =
        Proxy.newProxyInstance(Browser::class.java.classLoader, arrayOf(Browser::class.java)) { _, method, _ ->
            check(method.name == "close")
            close()
            null
        } as Browser

    @Test
    fun `successful chrome setup leaves the browser open`() {
        val browser = browser { error("successful browser must remain open") }
        var installs = 0
        installBrowserChromeOrClose(browser) {
            assertSame(browser, it)
            installs++
        }
        assertEquals(1, installs)
    }

    @Test
    fun `setup failure closes the browser and preserves the failure`() {
        var closes = 0
        val failure = IllegalStateException("setup failed")
        assertSame(
            failure,
            assertFailsWith<IllegalStateException> {
                installBrowserChromeOrClose(browser { closes++ }) { throw failure }
            },
        )
        assertEquals(1, closes)
    }

    @Test
    fun `cleanup failure does not replace the original fatal setup failure`() {
        var closes = 0
        val failure = LinkageError("setup linkage failure")
        assertSame(
            failure,
            assertFailsWith<LinkageError> {
                installBrowserChromeOrClose(
                    browser {
                        closes++
                        error("close failed")
                    },
                ) { throw failure }
            },
        )
        assertEquals(1, closes)
    }
}
