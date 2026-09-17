package ai.rever.boss.components.auth.screens

import ai.rever.boss.services.auth.MagicLinkErrorService
import ai.rever.boss.utils.DeepLinkHandler
import ai.rever.boss.utils.logging.LogSanitizer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What "Paste magic link manually" does with the link a user copies out of the sign-in email.
 *
 * The email wraps Supabase's confirmation URL in the redirect function
 * (`supabase/templates/email/magic-link.html`: `.../functions/v1/redirect?url={{ .ConfirmationURL }}`), and
 * a copied link can arrive with that inner URL percent-encoded. Measured on a real 9.5.17 sign-in email: both
 * its button and the link below it carried the encoded form, and pasting it did nothing, while the same token
 * with only `url=` decoded signed in.
 *
 * Each case runs the real [processMagicLink] and reads what reached [DeepLinkHandler.deepLinkFlow], the flow
 * `BossAppWithAuth` verifies `boss://auth/verify` links from.
 */
class MagicLinkPasteTest {
    private val token = "0123456789abcdef0123456789abcdef0123456789abcdef01234567"
    private val verify = "https://api.risaboss.com/auth/v1/verify"
    private val redirect = "https://api.risaboss.com/functions/v1/redirect"
    private var successCalls = 0

    @BeforeTest
    fun reset() {
        DeepLinkHandler.clearDeepLink()
        MagicLinkErrorService.clearError()
        successCalls = 0
    }

    @AfterTest
    fun cleanUp() = reset()

    private fun paste(text: String): String? {
        processMagicLink(text) { successCalls++ }
        return DeepLinkHandler.deepLinkFlow.value
    }

    private fun encodedEmailLink(
        type: String = "magiclink",
        tokenParam: String = "token",
    ) = "$redirect?url=https%3a%2f%2fapi.risaboss.com%2fauth%2fv1%2fverify%3f$tokenParam%3d$token" +
        "%26type%3d$type%26redirect_to%3dboss%3a%2f%2fauth%2fverify"

    private fun assertReachesVerification(
        text: String,
        type: String = "magiclink",
    ) {
        assertEquals("boss://auth/verify?token=$token&type=$type", paste(text), text)
        assertNull(MagicLinkErrorService.verificationError.value, text)
        assertEquals(1, successCalls, text)
    }

    private fun assertRefused(text: String) {
        assertNull(paste(text), text)
        assertNotNull(MagicLinkErrorService.verificationError.value, text)
        assertEquals(0, successCalls, text)
    }

    @Test
    fun `the encoded link from the sign-in email reaches verification`() {
        // Exactly the measured shape, lowercase hex, with the line break a clipboard copy can carry.
        assertReachesVerification(encodedEmailLink() + "\r\n")
    }

    @Test
    fun `uppercase percent-encoding reaches verification too`() {
        assertReachesVerification(encodedEmailLink().replace(Regex("%[0-9a-f]{2}")) { it.value.uppercase() })
    }

    @Test
    fun `a first-time user's signup type survives the encoded link`() {
        assertReachesVerification(encodedEmailLink(type = "signup"), type = "signup")
    }

    @Test
    fun `the token_hash name the redirect function also accepts is read`() {
        assertReachesVerification(encodedEmailLink(tokenParam = "token_hash"))
    }

    @Test
    fun `an unencoded redirect link keeps the type that splits off to the outer link`() {
        // redirect.test.ts pins the same shape server-side: &type= lands on the redirect URL itself.
        val unencoded = "$redirect?url=$verify?token=$token&type=signup&redirect_to=boss://auth/verify"
        assertReachesVerification(unencoded, type = "signup")
    }

    @Test
    fun `a direct Supabase verify link still reaches verification`() {
        assertReachesVerification("$verify?token=$token&type=magiclink&redirect_to=boss://auth/verify")
    }

    @Test
    fun `a redirect link that carries the token itself reaches verification`() {
        // The redirect function's own `?token=&type=` form.
        assertReachesVerification("$redirect?token=$token&type=signup", type = "signup")
    }

    @Test
    fun `surrounding whitespace is ignored when the type is the last parameter`() {
        val typeLast = "$verify?redirect_to=boss://auth/verify&token=$token&type=magiclink"
        assertReachesVerification("  " + typeLast + System.lineSeparator())
    }

    @Test
    fun `a verify link copied without its https prefix still reaches verification`() {
        assertReachesVerification("api.risaboss.com/auth/v1/verify?token=$token&type=magiclink")
    }

    @Test
    fun `a link wrapped in one more url parameter is unwrapped`() {
        val wrapped = "https://links.example.com/?url=" + java.net.URLEncoder.encode(encodedEmailLink(), "UTF-8")
        assertReachesVerification(wrapped)
    }

    @Test
    fun `a boss deep link is passed on unchanged`() {
        val deepLink = "boss://auth/verify?token=$token&type=magiclink"
        assertEquals(deepLink, paste(deepLink))
        // The form Supabase's own success redirect uses, which has no query to read.
        val fragment = "boss://auth/verify#access_token=$token&type=magiclink"
        assertEquals(fragment, paste(fragment))
        // A clipboard copy can carry surrounding whitespace, which the query parser would not strip here.
        assertEquals(fragment, paste("  " + fragment + System.lineSeparator()))
        assertEquals(3, successCalls)
    }

    @Test
    fun `text that is not a sign-in link says so instead of doing nothing`() {
        assertRefused("https://www.example.com/page")
        assertRefused("hello there")
        assertRefused("$redirect?url=$verify")
        assertRefused("token=$token&next=/verify")
        assertRefused("$verify?token=%zz")
    }

    @Test
    fun `a token under an address that is neither verify nor the redirect function is not used`() {
        // Nothing but a verify or redirect link carries a sign-in token, and verifying sends the value to Supabase.
        assertRefused("https://www.example.com/account?token=$token")
    }

    @Test
    fun `a decoded token that would add its own parameters is refused`() {
        // Decodes to token "abc&type=recovery".
        val smuggled =
            "$redirect?url=https%3a%2f%2fapi.risaboss.com%2fauth%2fv1%2fverify" +
                "%3ftoken%3dabc%2526type%253drecovery"
        assertRefused(smuggled)
        // Decodes to type "signup&token=other".
        assertRefused("$verify?token=$token&type=signup%26token%3Dother")
    }

    @Test
    fun `nesting deeper than the unwrap limit is refused`() {
        var link = encodedEmailLink()
        repeat(4) { link = "https://links.example.com/?url=" + java.net.URLEncoder.encode(link, "UTF-8") }
        assertRefused(link)
    }

    @Test
    fun `the refusal message is one the waiting screen displays`() {
        assertRefused("hello there")
        val message = MagicLinkErrorService.verificationError.value.orEmpty()
        // MagicLinkWaitingScreen hides any errorMessage containing "sent" or "Check your email".
        assertTrue(message.isNotBlank())
        assertFalse(message.contains("sent"), message)
        assertFalse(message.contains("Check your email"), message)
    }

    @Test
    fun `a pasted sign-in link is logged without its token`() {
        // DeepLinkHandler logs what it is given through maskUriParams, which masks a top-level token only.
        val dispatched = paste(encodedEmailLink())
        assertNotNull(dispatched)
        assertFalse(LogSanitizer.maskUriParams(dispatched).contains(token))
    }
}
