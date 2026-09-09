package ai.rever.boss.services

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [UrlOpenValidation], the gate on every URL the OS hands BOSS.
 *
 * The rule that made this worth extracting: the previous inline version required
 * the host to contain a `.`, so `http://localhost:3000` was refused with a log
 * line and no tab. For an app whose users are developers, that is the link they
 * click most.
 */
class UrlOpenValidationTest {
    private fun accepts(url: String) = assertTrue(UrlOpenValidation.isOpenable(url), "should accept $url")

    private fun refuses(url: String) = assertFalse(UrlOpenValidation.isOpenable(url), "should refuse $url")

    @Test
    fun `accepts a dev server on localhost`() {
        accepts("http://localhost:3000")
        accepts("http://localhost")
        accepts("http://localhost:8080/api/health?x=1")
        accepts("https://localhost:3000")
    }

    @Test
    fun `accepts single-label and unusual but real hosts`() {
        // An intranet name, a Docker Compose service name (underscores and all),
        // and a machine name with no domain. java.net.URI rejects the underscore
        // one, which is why the parser is hand-rolled.
        accepts("http://wiki/start")
        accepts("http://api_gateway:8080/")
        accepts("http://build-server-07/job/1")
    }

    @Test
    fun `accepts IP literals including bracketed IPv6`() {
        accepts("http://127.0.0.1:5000")
        accepts("http://[::1]:8080/x")
        accepts("http://[fe80::1]")
        accepts("https://[2001:db8::8a2e:370:7334]:443/path")
    }

    @Test
    fun `accepts ordinary web URLs`() {
        accepts("https://example.com")
        accepts("https://www.example.com/a/b?c=d#e")
        accepts("HTTPS://Example.COM/")
    }

    @Test
    fun `accepts port boundaries and an empty default port`() {
        for (host in listOf("localhost", "127.0.0.1", "[::1]")) {
            accepts("http://$host:0/")
            accepts("https://$host:65535/path")
            accepts("https://$host:/path")
        }
    }

    @Test
    fun `refuses ports above the maximum without integer overflow`() {
        for (host in listOf("localhost", "127.0.0.1", "[::1]")) {
            refuses("http://$host:65536/")
            refuses("https://$host:4294967296/path")
            refuses("http://$host:${"9".repeat(100)}/")
        }
    }

    @Test
    fun `leading zeros do not change whether a port is valid`() {
        val zeros = "0".repeat(100)
        accepts("http://localhost:007/")
        refuses("http://localhost:0x50/")
        refuses("http://[::1]:0-1/")
        accepts("http://localhost:${zeros}65535/")
        accepts("http://[::1]:$zeros/")
        refuses("http://localhost:${zeros}65536/")
    }

    @Test
    fun `refuses signed and non ASCII ports`() {
        refuses("http://localhost:-1/")
        refuses("http://localhost:+80/")
        refuses("http://localhost:8.0/")
        refuses("http://localhost:\u0663\u0969/")
    }

    @Test
    fun `refuses every scheme but http and https`() {
        refuses("file:///etc/passwd")
        refuses("javascript:alert(1)")
        refuses("data:text/html,<script>alert(1)</script>")
        refuses("boss://terminal?command=rm")
        refuses("ftp://example.com")
        refuses("example.com")
        refuses("")
    }

    @Test
    fun `refuses a missing or malformed authority`() {
        refuses("http://")
        refuses("http:///path")
        refuses("http://:8080/")
        refuses("http://[::1")
        refuses("http://host:notaport/")
    }

    @Test
    fun `refuses credentials in the authority`() {
        // The classic disguised destination: this points at evil.example, not
        // apple.com. Nothing BOSS does needs embedded credentials, so they are
        // refused rather than stripped.
        refuses("https://apple.com@evil.example/")
        refuses("http://user:pass@example.com/")
    }

    @Test
    fun `refuses whitespace and control characters in the authority`() {
        refuses("http://exa mple.com")
        refuses("http://example\ncom")
        refuses("http://exa\u00A0mple.com")
        refuses("http://exa\u0000mple.com")
        refuses("http://exa<mple>.com")
        refuses("http://exa\"mple.com")
    }
}
