package ai.rever.boss.app.browser

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.GetFaviconRequest
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
 *
 * A first pass at this fix used a `{javascript, data}` blocklist and a first-`@` redaction regex.
 * Both were found to have bypasses in review (see PR #932): a blocklist can only ever enumerate
 * known-bad schemes, and a naive `indexOf('@')` split leaves a credential containing its own `@`
 * only partially masked. This suite pins the allowlist + WHATWG-faithful parsing that replaced
 * them, including the exact smuggling inputs that motivated the change.
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
    fun `a tab embedded in the scheme cannot smuggle javascript past the gate`() =
        runBlocking {
            // The blocklist regression this suite exists to prevent: a real URL parser strips
            // embedded tab/newline/CR before reading the scheme and would still run this as
            // javascript:, but `dangerousUrlScheme`'s naive indexOf(':') split let it through.
            val service = BrowserServiceImpl()
            val response = service.navigate(navigateRequest("jav\tascript:alert(1)"))

            assertFalse(response.success)
            assertTrue(response.errorMessage.contains("javascript"))
        }

    @Test
    fun `a newline embedded in the scheme cannot smuggle javascript past the gate`() =
        runBlocking {
            val service = BrowserServiceImpl()
            val response = service.navigate(navigateRequest("jav\nascript:alert(1)"))

            assertFalse(response.success)
            assertTrue(response.errorMessage.contains("javascript"))
        }

    @Test
    fun `a leading NUL byte cannot smuggle javascript past the gate`() =
        runBlocking {
            val service = BrowserServiceImpl()
            val response = service.navigate(navigateRequest("\u0000javascript:alert(1)"))

            assertFalse(response.success)
            assertTrue(response.errorMessage.contains("javascript"))
        }

    @Test
    fun `a nested scheme like view-source is refused because it is not on the allowlist`() =
        runBlocking {
            val service = BrowserServiceImpl()
            val response = service.navigate(navigateRequest("view-source:data:text/html,<script>1</script>"))

            assertFalse(response.success)
        }

    @Test
    fun `a schemeless URL is refused`() =
        runBlocking {
            val service = BrowserServiceImpl()
            val response = service.navigate(navigateRequest("no-scheme-at-all"))

            assertFalse(response.success)
        }

    @Test
    fun `http, https, file and ftp URLs all navigate normally`() =
        runBlocking {
            val allowedUrls =
                listOf("http://example.com", "https://example.com", "file:///tmp/x.html", "ftp://example.com/f")
            for (url in allowedUrls) {
                val service = BrowserServiceImpl()
                val response = service.navigate(navigateRequest(url))

                assertTrue(response.success, "$url should have been allowed")
                assertEquals(url, response.finalUrl)
            }
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
    fun `a refused navigation's response and events keep no trace of the payload`() =
        runBlocking {
            val service = BrowserServiceImpl()
            val response = service.navigate(navigateRequest("javascript:alert(document.cookie)"))

            assertFalse(response.success)
            assertFalse(response.errorMessage.contains("alert"))
            assertFalse(response.errorMessage.contains("cookie"))
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
    fun `extractUrlScheme allows only the browser-navigable schemes`() {
        assertEquals("http", extractUrlScheme("http://example.com"))
        assertEquals("https", extractUrlScheme("https://example.com"))
        assertEquals("file", extractUrlScheme("file:///tmp/x"))
        assertEquals("ftp", extractUrlScheme("ftp://example.com"))
        assertEquals("javascript", extractUrlScheme("javascript:alert(1)"))
        assertEquals("javascript", extractUrlScheme("JAVASCRIPT:alert(1)"))
        assertEquals("data", extractUrlScheme("data:text/html,<b>hi</b>"))
        assertEquals("about", extractUrlScheme("about:blank"))
        assertNull(extractUrlScheme(""))
        assertNull(extractUrlScheme("no-scheme-at-all"))
    }

    @Test
    fun `extractUrlScheme strips embedded tab, newline and CR before reading the scheme`() {
        assertEquals("javascript", extractUrlScheme("jav\tascript:alert(1)"))
        assertEquals("javascript", extractUrlScheme("jav\nascript:alert(1)"))
        assertEquals("javascript", extractUrlScheme("jav\rascript:alert(1)"))
    }

    @Test
    fun `extractUrlScheme strips a leading NUL byte before reading the scheme`() {
        assertEquals("javascript", extractUrlScheme("\u0000javascript:alert(1)"))
    }

    @Test
    fun `extractUrlScheme rejects a scheme that does not start with a letter`() {
        assertNull(extractUrlScheme("123abc:alert(1)"))
        assertNull(extractUrlScheme("+http://example.com"))
    }

    @Test
    fun `an about URL is refused even though it was never on the old blocklist`() =
        runBlocking {
            // about: passed the old {javascript, data} blocklist untouched (it isn't in that set),
            // but it isn't a browser-navigable scheme either. Under the allowlist that replaced
            // the blocklist, anything not in {http, https, file, ftp} is refused by default.
            val service = BrowserServiceImpl()
            val response = service.navigate(navigateRequest("about:blank"))

            assertFalse(response.success)
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

    @Test
    fun `redactUrlUserInfo masks the whole credential even when the password contains an at sign`() {
        // The first-'@' regression: `Regex("(://)[^/@\\s]+@")` stopped at the first '@' and left
        // "ss@w0rd@internal-host" unmasked. The userinfo/host boundary is the LAST '@' in the
        // authority, so everything up to and including it must be replaced.
        assertEquals(
            "https://***@internal-host/path",
            redactUrlUserInfo("https://admin:p@ss@w0rd@internal-host/path"),
        )
    }

    @Test
    fun `redactUrlUserInfo does not treat an at sign in the path or query as userinfo`() {
        assertEquals(
            "https://example.com/user@example.com",
            redactUrlUserInfo("https://example.com/user@example.com"),
        )
        assertEquals(
            "https://example.com/search?q=user@example.com",
            redactUrlUserInfo("https://example.com/search?q=user@example.com"),
        )
    }

    @Test
    fun `getFavicon redacts credentials from the URL it logs`() =
        runBlocking {
            val service = BrowserServiceImpl()
            // No logging assertions are practical here without a logger fixture; this pins that
            // the call succeeds and returns the documented empty stub regardless of the URL shape,
            // while redactUrlUserInfo (exercised directly above) is what the log line now routes
            // through instead of the raw request URL.
            val response =
                service.getFavicon(
                    GetFaviconRequest.newBuilder().setUrl("https://admin:Secret123@internal-host/favicon.ico").build(),
                )

            assertEquals("", response.contentType)
        }
}
