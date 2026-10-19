package ai.rever.boss.cli

import ai.rever.boss.utils.DeepLinkHandler
import ai.rever.boss.utils.DeepLinkOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Guards what happens to a `boss://url?url=` request, which is decided by who
 * asked rather than by what the URL points at.
 *
 * `boss://` is registered with the OS, so the same link arrives whether the
 * operator typed `boss url …` or some other program — or web page — asked the
 * OS to open a URL. [urlOpenDisposition] is the one place that distinction is
 * acted on, so these cases are the ones that regress silently.
 */
class UrlOpenOriginTest {
    @Test
    fun `a URL the operator opened themselves opens unattended`() {
        assertEquals(
            UrlOpenDisposition.OPEN,
            urlOpenDisposition("https://example.com", DeepLinkOrigin.OPERATOR_CLI),
        )
        // `boss url` exists to open whatever the operator types, so no URL is
        // special-cased on this path.
        assertEquals(
            UrlOpenDisposition.OPEN,
            urlOpenDisposition("http://localhost:3000/callback?token=x", DeepLinkOrigin.OPERATOR_CLI),
        )
    }

    @Test
    fun `a URL from anywhere else is held for confirmation, never opened`() {
        assertEquals(
            UrlOpenDisposition.CONFIRM,
            urlOpenDisposition("https://example.com", DeepLinkOrigin.EXTERNAL),
        )
        assertEquals(
            UrlOpenDisposition.CONFIRM,
            urlOpenDisposition("https://attacker.example/login", DeepLinkOrigin.EXTERNAL),
        )
    }

    @Test
    fun `a URL too long to display in full is dropped rather than confirmed`() {
        val longUrl = "https://example.com/" + "a".repeat(URL_CONFIRM_MAX_URL_LENGTH)

        // Nobody can meaningfully approve a URL the prompt cannot show.
        assertEquals(
            UrlOpenDisposition.REJECT,
            urlOpenDisposition(longUrl, DeepLinkOrigin.EXTERNAL),
        )
        // The operator's own URL is never displayed, so the display bound does
        // not apply to it.
        assertEquals(
            UrlOpenDisposition.OPEN,
            urlOpenDisposition(longUrl, DeepLinkOrigin.OPERATOR_CLI),
        )
        // Exactly at the display bound the URL still fits, so it confirms.
        val boundaryUrl = "https://e.co/" + "a".repeat(URL_CONFIRM_MAX_URL_LENGTH - 13)
        assertEquals(
            UrlOpenDisposition.CONFIRM,
            urlOpenDisposition(boundaryUrl, DeepLinkOrigin.EXTERNAL),
        )
    }

    @Test
    fun `a URL with no stated origin is treated as external`() {
        // CLICommand.OpenUrl defaults its origin, so a caller that forgets to
        // say gets the cautious handling rather than the operator's.
        val command = CLICommand.OpenUrl("https://example.com")
        assertEquals(DeepLinkOrigin.EXTERNAL, command.origin)
        assertEquals(
            UrlOpenDisposition.CONFIRM,
            urlOpenDisposition(command.url, command.origin),
        )
    }

    @Test
    fun `an operator URL command keeps its origin through the initialization queue`() {
        // The queue holds whole commands: dropping the origin would turn a
        // cold-start `boss url` invocation into an unattributed one.
        val queue = ReadinessQueue<CLICommand>()
        val command = CLICommand.OpenUrl("https://example.com", DeepLinkOrigin.OPERATOR_CLI)

        assertFalse(queue.enqueueOrClaimForCaller(command))
        assertEquals(listOf(command), queue.markReadyAndClaimQueued())
    }

    @Test
    fun `URL deep link decoding carries its origin into the queued command`() {
        val link = "boss://url?url=https%3A%2F%2Fexample.com%2Fpath"
        val external = DeepLinkHandler.urlOpenCommandFromLink(link, DeepLinkOrigin.EXTERNAL)
        val operator = DeepLinkHandler.urlOpenCommandFromLink(link, DeepLinkOrigin.OPERATOR_CLI)

        assertEquals("https://example.com/path", external?.url)
        assertEquals(DeepLinkOrigin.EXTERNAL, external?.origin)
        assertEquals(DeepLinkOrigin.OPERATOR_CLI, operator?.origin)
    }
}
