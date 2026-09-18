package ai.rever.boss.app.browser

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.NavigateBrowserRequest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression coverage for issue #911: `navigate` accepted and logged `javascript:`/`data:` URLs
 * verbatim, and `reload` left `getPageInfo().isLoading` stuck true forever (nothing ever flipped
 * it back, since this stub has no real engine to report completion).
 */
class BrowserServiceImplTest {
    private fun navigateRequest(
        url: String,
        windowId: String = "window-1",
    ) = NavigateBrowserRequest
        .newBuilder()
        .setUrl(url)
        .setWindowId(windowId)
        .build()

    @Test
    fun `a javascript URL is refused and never reaches window state`() =
        runBlocking {
            val service = BrowserServiceImpl()
            val response = service.navigate(navigateRequest("javascript:alert(document.domain)"))

            assertFalse(response.success)
            assertTrue(response.errorMessage.contains("javascript"))

            val info = service.getPageInfo(Empty.getDefaultInstance())
            assertEquals("", info.url, "a refused navigation must not create window state")
        }

    @Test
    fun `a data URL is refused the same way`() =
        runBlocking {
            val service = BrowserServiceImpl()
            val response = service.navigate(navigateRequest("data:text/html,<script>alert(1)</script>"))

            assertFalse(response.success)
            assertTrue(response.errorMessage.contains("data"))
        }

    @Test
    fun `scheme matching is case-insensitive`() =
        runBlocking {
            val service = BrowserServiceImpl()
            val response = service.navigate(navigateRequest("JavaScript:alert(1)"))

            assertFalse(response.success)
        }

    @Test
    fun `an ordinary https URL still navigates normally`() =
        runBlocking {
            val service = BrowserServiceImpl()
            val response = service.navigate(navigateRequest("https://example.com/page"))

            assertTrue(response.success)
            assertEquals("https://example.com/page", response.finalUrl)

            val info = service.getPageInfo(Empty.getDefaultInstance())
            assertEquals("https://example.com/page", info.url)
        }

    @Test
    fun `reload does not leave isLoading stuck true`() =
        runBlocking {
            val service = BrowserServiceImpl()
            service.navigate(navigateRequest("https://example.com"))

            service.reload(Empty.getDefaultInstance())

            val info = service.getPageInfo(Empty.getDefaultInstance())
            assertFalse(info.isLoading, "getPageInfo must not report loading forever after reload")
        }

    @Test
    fun `dangerousUrlScheme flags javascript and data, and nothing else`() {
        assertEquals("javascript", dangerousUrlScheme("javascript:alert(1)"))
        assertEquals("javascript", dangerousUrlScheme("JAVASCRIPT:alert(1)"))
        assertEquals("data", dangerousUrlScheme("data:text/html,<b>hi</b>"))
        assertNull(dangerousUrlScheme("https://example.com"))
        assertNull(dangerousUrlScheme("about:blank"))
        assertNull(dangerousUrlScheme(""))
        assertNull(dangerousUrlScheme("no-scheme-at-all"))
    }

    @Test
    fun `redactUrlUserInfo masks credentials without touching the rest of the URL`() {
        assertEquals(
            "https://***@internal-host/path?x=1",
            redactUrlUserInfo("https://admin:Secret123@internal-host/path?x=1"),
        )
        assertEquals(
            "https://example.com/no-userinfo",
            redactUrlUserInfo("https://example.com/no-userinfo"),
        )
    }
}
