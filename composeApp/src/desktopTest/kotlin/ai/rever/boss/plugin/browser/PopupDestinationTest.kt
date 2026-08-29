package ai.rever.boss.plugin.browser

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Where an adopted popup's tab goes, as code.
 *
 * The regression this pins is not hypothetical. A tab opened on
 * `https://internal-analytics.odoo.com/api/event` titled "New Tab", and another on
 * `https://collector.github.com/github/collect` titled "Collector" - both POST-only analytics
 * endpoints that appear in no page's markup, reached because the engine-wide upload callback
 * claimed whichever POST the popup's page fired first and the result was allowed to *replace*
 * the destination. A capture may contribute a body; it may never pick the page.
 */
class PopupDestinationTest {
    private val formBody = "order=1".toByteArray()

    private fun capture(
        url: String,
        body: ByteArray = formBody,
        contentType: String = "application/x-www-form-urlencoded",
    ) = PopupCapture(url = url, body = body, contentType = contentType)

    @Test
    fun `an upload from somewhere other than the navigation cannot choose the destination`() {
        val nav =
            popupDestination(
                navigationUrl = "https://github.com/risa-labs-inc/BossConsole/pull/280",
                createTargetUrl = null,
                capture = capture("https://collector.github.com/github/collect"),
            )

        assertEquals("https://github.com/risa-labs-inc/BossConsole/pull/280", nav?.url)
        assertNull(nav?.postData, "a beacon body must not ride along to an unrelated page")
        assertNull(nav?.contentType)
    }

    @Test
    fun `a beacon cannot supply the destination when the navigation never resolved`() {
        // The catastrophic shape: the URL wait times out, so the capture used to be the only
        // URL in play and the tab opened on the analytics endpoint.
        assertNull(
            popupDestination(
                navigationUrl = null,
                createTargetUrl = null,
                capture = capture("https://internal-analytics.odoo.com/api/event"),
            ),
        )
    }

    @Test
    fun `the popup is dropped rather than sent somewhere arbitrary`() {
        assertNull(popupDestination(navigationUrl = null, createTargetUrl = null, capture = null))
        assertNull(popupDestination(navigationUrl = "", createTargetUrl = "", capture = null))
        assertNull(
            popupDestination(navigationUrl = "about:blank", createTargetUrl = "about:blank", capture = null),
        )
    }

    @Test
    fun `the target recorded at popup creation is used when no navigation started`() {
        val nav =
            popupDestination(
                navigationUrl = "about:blank",
                createTargetUrl = "https://example.com/report",
                capture = null,
            )

        assertEquals("https://example.com/report", nav?.url)
    }

    @Test
    fun `the navigation outranks the creation target when both are known`() {
        val nav =
            popupDestination(
                navigationUrl = "https://example.com/actual",
                createTargetUrl = "https://example.com/intended",
                capture = null,
            )

        assertEquals("https://example.com/actual", nav?.url)
    }

    @Test
    fun `a form POST opened in a new tab keeps its body`() {
        val nav =
            popupDestination(
                navigationUrl = "https://oncoemr.example.com/print",
                createTargetUrl = null,
                capture = capture("https://oncoemr.example.com/print"),
            )

        assertEquals("https://oncoemr.example.com/print", nav?.url)
        assertContentEquals(formBody, nav?.postData)
        assertEquals("application/x-www-form-urlencoded", nav?.contentType)
    }

    @Test
    fun `a form posting to a fragment keeps its body`() {
        // Chromium does not send the fragment to the network, so the upload callback sees the
        // URL without it. Exact equality here silently downgraded the submission to a GET.
        val nav =
            popupDestination(
                navigationUrl = "https://oncoemr.example.com/print#page2",
                createTargetUrl = null,
                capture = capture("https://oncoemr.example.com/print"),
            )

        assertEquals("https://oncoemr.example.com/print#page2", nav?.url)
        assertContentEquals(formBody, nav?.postData)
    }

    @Test
    fun `an ordinary link opens as a plain GET`() {
        val nav = popupDestination("https://example.com/page", null, null)

        assertEquals("https://example.com/page", nav?.url)
        assertNull(nav?.postData)
        assertNull(nav?.contentType)
    }

    @Test
    fun `about blank is never a destination, in any of its forms`() {
        assertNull(usablePopupUrl("about:blank"))
        // Chromium's value for a popup it blocked. The exact-string compare this replaced let
        // it through.
        assertNull(usablePopupUrl("about:blank#blocked"))
        assertNull(usablePopupUrl("about:srcdoc"))
        assertNull(usablePopupUrl(""))
        assertNull(usablePopupUrl("   "))
        assertNull(usablePopupUrl(null))
    }

    @Test
    fun `only http and https can be adopted into a tab`() {
        assertEquals("https://example.com", usablePopupUrl("https://example.com"))
        assertEquals("http://localhost:8080/x", usablePopupUrl("http://localhost:8080/x"))

        // A page controls what it passes to window.open, and the creation-time target is now a
        // fallback destination - so this is the guard that stops it naming a local file.
        assertNull(usablePopupUrl("file:///Users/me/.ssh/id_rsa"))
        assertNull(usablePopupUrl("javascript:alert(1)"))
        assertNull(usablePopupUrl("data:text/html,<h1>x"))
        // Scoped to the document that minted it, so a fresh browser could never load it.
        assertNull(usablePopupUrl("blob:https://example.com/abc-123"))
        assertNull(usablePopupUrl("chrome://settings"))
        assertNull(usablePopupUrl("filesystem:https://example.com/temporary/x"))
    }

    @Test
    fun `a scheme is matched case-insensitively`() {
        assertEquals("HttPs://example.com", usablePopupUrl("HttPs://example.com"))
        assertEquals("HTTP://example.com", usablePopupUrl("HTTP://example.com"))
    }

    @Test
    fun `a non-web creation target cannot become the destination`() {
        assertNull(
            popupDestination(
                navigationUrl = null,
                createTargetUrl = "file:///etc/passwd",
                capture = null,
            ),
        )
    }
}
