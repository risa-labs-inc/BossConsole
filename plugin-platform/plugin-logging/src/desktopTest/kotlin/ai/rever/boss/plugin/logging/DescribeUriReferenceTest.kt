package ai.rever.boss.plugin.logging

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [LogSanitizer.describeUri] for a reference with no scheme.
 *
 * `java.net.URI` parses `localhost` or `app?code=x` as a relative reference whose scheme is null, and
 * the description was built by interpolating the scheme, so these read as `null://localhost`. Browser
 * and CLI log lines describe exactly this kind of input: the value a `boss://url` link carries, and the
 * text `CLISecurityValidator.normalizeAndValidateUrl` just rejected.
 */
class DescribeUriReferenceTest {
    private fun assertDescribed(
        expected: String,
        uri: String,
    ) {
        assertEquals(expected, LogSanitizer.describeUri(uri), uri)
    }

    @Test
    fun `a reference with no scheme is described without a null scheme`() {
        assertDescribed("localhost", "localhost")
        assertDescribed("app (with query params)", "app?code=secret")
        assertDescribed("example.com/a/b (with fragment)", "example.com/a/b#token=secret")
    }

    @Test
    fun `a network-path reference keeps its leading slashes`() {
        assertDescribed("//cdn.example.com/f (with query params)", "//cdn.example.com/f?sig=secret")
    }

    @Test
    fun `a query or fragment alone is described without a leading space`() {
        assertDescribed("(with query params)", "?code=secret")
        assertDescribed("(with fragment)", "#access_token=secret")
    }

    @Test
    fun `an absolute URI is described as before`() {
        assertDescribed("https://example.com/cb (with query params)", "https://example.com/cb?code=secret")
        assertDescribed("boss://auth/verify (with query params)", "boss://auth/verify?token=secret")
        assertDescribed("about://", "about:blank")
    }

    @Test
    fun `an authority java-net-URI will not parse as a hostname is still named`() {
        assertDescribed(
            "https://my_host.example.com/a (with query params)",
            "https://my_host.example.com/a?code=secret",
        )
        assertDescribed("http://web_server/status", "http://web_server/status")
        assertDescribed(
            "https://münchen.example.com/a (with query params)",
            "https://münchen.example.com/a?code=secret",
        )
        assertDescribed("//my_host.example.com/f (with query params)", "//my_host.example.com/f?sig=secret")
    }

    @Test
    fun `an unparsed authority drops its port and userinfo, as a parsed one does`() {
        assertDescribed("https://x_y.internal/p (with fragment)", "https://x_y.internal:8443/p#t=1")
        assertDescribed("https://my_host.example.com/o/r", "https://token:secret@my_host.example.com/o/r")
        assertDescribed("https://web_server/a", "https://user@name:secret@web_server/a")
    }

    /**
     * The two shapes that keep [LogSanitizer] from cutting an unparsed authority in the wrong place.
     * Both were measured to reach it on JDK 17: `URI` parses each one and reports a null host.
     */
    @Test
    fun `an unparsed authority is not cut at a colon that is not a port, nor left as a bare marker`() {
        assertDescribed("https://x_y.internal:80a/p", "https://x_y.internal:80a/p")
        assertDescribed("/a", "//user:pass@/a")
    }

    @Test
    fun `a reference that is only a delimiter says so`() {
        assertDescribed("[empty reference]", "?")
        assertDescribed("[empty reference]", "#")
        assertDescribed("[empty reference]", "?#")
    }
}
