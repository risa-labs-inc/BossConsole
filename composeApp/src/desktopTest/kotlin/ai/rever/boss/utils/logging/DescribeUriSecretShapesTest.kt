package ai.rever.boss.utils.logging

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins what [LogSanitizer.describeUri] keeps for URLs whose secret sits under a name
 * [LogSanitizer.maskUriParams] does not list.
 *
 * Browser and opened-link log lines use `describeUri` rather than `maskUriParams` because the latter
 * redacts by exact parameter name, and a tab's URL carries credentials under names no list keeps up
 * with. Measured on fourteen credential-bearing URL shapes, `maskUriParams` hid four and `describeUri`
 * twelve; the two it missed carry the token in the path. Those sites now depend on this output dropping
 * the query, the fragment and the userinfo, so it is pinned here rather than assumed.
 *
 * Not covered, by either function: a credential in the path itself (`/reset-password/<token>`, a
 * Slack webhook). `describeUri` also drops the port and reads an opaque URI such as `about:blank`
 * as `about://`, which costs the log a little detail and exposes nothing. A reference with no scheme
 * keeps its whole path, and for input that is not a URL at all (`localhost`) that is the input.
 */
class DescribeUriSecretShapesTest {
    private fun assertDescribed(
        expected: String,
        url: String,
    ) {
        assertEquals(expected, LogSanitizer.describeUri(url), url)
    }

    @Test
    fun `query credentials under names maskUriParams does not list are dropped`() {
        assertDescribed(
            "https://x.firebaseapp.com/__/auth/action (with query params)",
            "https://x.firebaseapp.com/__/auth/action?mode=resetPassword&oobCode=SECRET",
        )
        assertDescribed(
            "https://b.s3.amazonaws.com/f.pdf (with query params)",
            "https://b.s3.amazonaws.com/f.pdf?X-Amz-Credential=AKIA&X-Amz-Signature=SECRET",
        )
        assertDescribed(
            "https://a.blob.core.windows.net/c/f (with query params)",
            "https://a.blob.core.windows.net/c/f?sv=2022&sig=SECRET",
        )
        assertDescribed("https://zoom.us/j/123 (with query params)", "https://zoom.us/j/123?pwd=SECRET")
        assertDescribed(
            "https://idp.example.com/token (with query params)",
            "https://idp.example.com/token?client_id=a&client_secret=SECRET",
        )
        assertDescribed("https://api.example.com/v1 (with query params)", "https://api.example.com/v1?apikey=SECRET")
        assertDescribed(
            "https://example.com/passkey (with query params)",
            "https://example.com/passkey?sessionId=SECRET",
        )
    }

    @Test
    fun `fragment credentials and hash-routed queries are dropped`() {
        assertDescribed(
            "https://app.example.com/cb (with fragment)",
            "https://app.example.com/cb#access_token=SECRET&token_type=bearer",
        )
        assertDescribed("https://app.example.com/ (with fragment)", "https://app.example.com/#/reset?token=SECRET")
        assertDescribed(
            "https://example.com/a/b (with query and fragment)",
            "https://example.com:8443/a/b?token=SECRET#state=SECRET",
        )
    }

    @Test
    fun `userinfo is dropped`() {
        assertDescribed("https://github.com/o/r.git", "https://x-access-token:SECRET@github.com/o/r.git")
    }

    /**
     * The same URL with a host `java.net.URI` will not parse as a hostname.
     *
     * `URI.getHost()` and `getRawUserInfo()` are BOTH null for an authority containing an underscore
     * or a non-ASCII label, so the parser offers no help at all here: measured on JDK 17,
     * `https://x-access-token:SECRET@my_host.example.com/o/r.git` reports `host=null`,
     * `userInfo=null` and `rawAuthority=x-access-token:SECRET@my_host.example.com`. Naming the
     * authority means reading that raw string, and the secret is in it.
     */
    @Test
    fun `userinfo is dropped from an authority the parser rejects too`() {
        assertDescribed(
            "https://my_host.example.com/o/r.git",
            "https://x-access-token:SECRET@my_host.example.com/o/r.git",
        )
        assertDescribed("https://web_server/a", "https://user@name:SECRET@web_server/a")
    }

    @Test
    fun `a deep link's query carrying another URL or a command is dropped`() {
        // DeepLinkHandler logs every incoming link: maskUriParams passed these whole, since none of
        // url, command or oobCode is a name it lists.
        assertDescribed(
            "boss://url (with query params)",
            "boss://url?url=https%3A%2F%2Fapp.example.com%2Fcb%3FoobCode%3DSECRET",
        )
        assertDescribed("boss://url (with query params)", "boss://url?url=https://app.example.com/cb?code=SECRET")
        assertDescribed("boss://terminal (with query params)", "boss://terminal?command=export%20API_TOKEN%3DSECRET")
        assertDescribed("boss://auth/verify (with query params)", "boss://auth/verify?oobCode=SECRET")
    }

    @Test
    fun `a reference with no scheme is described without one`() {
        // What CLICommandHandler logs for input normalizeAndValidateUrl rejected, and what a
        // boss://url link can carry before that check runs. It used to read "null://localhost".
        assertDescribed("localhost", "localhost")
        assertDescribed("app (with query params)", "app?code=SECRET")
        assertDescribed("example.com/reset (with query params)", "example.com/reset?token=SECRET")
        assertDescribed("//cdn.example.com/f (with query params)", "//cdn.example.com/f?sig=SECRET")
        assertDescribed("about://", "about:blank")
    }

    @Test
    fun `input java-net-URI rejects gives a placeholder rather than the text`() {
        // CLICommandHandler logs exactly the input that failed validation, which is often not a URI.
        assertDescribed("[uri-parse-error]", "https://exa mple.com/?token=SECRET")
        assertDescribed("[uri-parse-error]", "https://x.example/?q=%zz&token=SECRET")
    }
}
