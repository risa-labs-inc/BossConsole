package ai.rever.boss.plugin.browser

import com.teamdev.jxbrowser.browser.Browser
import kotlinx.coroutines.sync.Mutex
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
        installBrowserChromeOrClose(browser, releaseOwnership = { error("successful browser retains ownership") }) {
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

    @Test
    fun `fatal failure from either production installer closes the new browser`() {
        for (failedRegistration in 1..2) {
            var registrations = 0
            var closes = 0
            val failure = LinkageError("callback registration failed")
            val browser =
                Proxy.newProxyInstance(Browser::class.java.classLoader, arrayOf(Browser::class.java)) { _, method, _ ->
                    when (method.name) {
                        "set" -> {
                            registrations++
                            if (registrations == failedRegistration) throw failure
                        }

                        "close" -> {
                            closes++
                        }

                        else -> {
                            error("unexpected browser access: ${method.name}")
                        }
                    }
                    null
                } as Browser

            assertSame(failure, assertFailsWith<LinkageError> { installBrowserChromeOrClose(browser) })
            assertEquals(failedRegistration, registrations)
            assertEquals(1, closes)
        }
    }

    @Test
    fun `fatal setup failure releases the profile fence even when browser close fails`() {
        val fence = Mutex(locked = true)
        var releases = 0
        val failure = LinkageError("setup failed")
        assertSame(
            failure,
            assertFailsWith<LinkageError> {
                installBrowserChromeOrClose(
                    browser { error("native close failed") },
                    releaseOwnership = {
                        releases++
                        fence.unlock()
                    },
                ) { throw failure }
            },
        )
        assertEquals(1, releases)
        assertFalse(fence.isLocked)
    }

    @Test
    fun `ownership cleanup failure preserves the original setup failure`() {
        val failure = LinkageError("setup failed")
        var closes = 0
        assertSame(
            failure,
            assertFailsWith<LinkageError> {
                installBrowserChromeOrClose(
                    browser { closes++ },
                    releaseOwnership = { error("ownership cleanup failed") },
                ) { throw failure }
            },
        )
        assertEquals(1, closes)
    }
}
