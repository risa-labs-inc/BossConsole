package ai.rever.boss.components.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the structural contract of the auth deep-link segment.
 *
 * `boss://` is registered with the OS, so any web page can open one of these links, and the
 * auth segment drives session-establishing actions (email verification, authentication
 * completed) with no confirmation. [AuthDeepLinks.parse] therefore refuses every link that is
 * not exactly a known producer's shape before anything acts on it. Each `assertNull` below is
 * a refusal the old substring matching let through: a `passkey/authenticated` smuggled inside
 * a parameter value, a lookalike host, a session id scraped out of the fragment, an ambiguous
 * duplicate parameter. A regression on any of these reopens a login-CSRF-shaped door that any
 * web page can walk a user's OS into.
 *
 * The merge-contract round is pinned here too: a token in both the query and the fragment is
 * refused instead of resolved by first match, the mail clients' `#_=_` mangling of a sign-in
 * email's link keeps parsing, a producer token that `encodeURIComponent` leaves unescaped
 * still reads verbatim, and [AuthDeepLinks.completesPasskeyCeremony] completes only the
 * ceremony the app actually started.
 */
class AuthDeepLinksTest {
    private val sessionId = "123e4567-e89b-12d3-a456-426614174000"
    private val accessToken = "eyJhbGciOiJIUzI1NiJ9.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
    private val supabaseFragmentLink =
        "boss://auth/verify#access_token=$accessToken&refresh_token=r1&expires_in=3600&token_type=bearer"

    @Test
    fun `parse reads a Supabase fragment magic link and tolerates its extra fragment params`() {
        val link = AuthDeepLinks.parse(supabaseFragmentLink)
        assertIs<AuthDeepLink.MagicLinkVerify>(link)
        assertEquals(accessToken, link.token)
        assertEquals("magiclink", link.type)
    }

    @Test
    fun `parse reads a query-token magic link with its type`() {
        val link = AuthDeepLinks.parse("boss://auth/verify?token=t&type=signup")
        assertIs<AuthDeepLink.MagicLinkVerify>(link)
        assertEquals("t", link.token)
        assertEquals("signup", link.type)
    }

    @Test
    fun `parse defaults the magic-link type when neither section carries one`() {
        val link = AuthDeepLinks.parse("boss://auth/verify?token=t")
        assertIs<AuthDeepLink.MagicLinkVerify>(link)
        assertEquals("magiclink", link.type)
    }

    @Test
    fun `parse prefers a fragment type over the default`() {
        val link = AuthDeepLinks.parse("boss://auth/verify#access_token=x&type=recovery")
        assertIs<AuthDeepLink.MagicLinkVerify>(link)
        assertEquals("recovery", link.type)
    }

    @Test
    fun `parse reads a passkey registered callback`() {
        val link = AuthDeepLinks.parse("boss://passkey/registered?sessionId=$sessionId")
        assertIs<AuthDeepLink.PasskeyRegistered>(link)
        assertEquals(sessionId, link.sessionId)
    }

    @Test
    fun `parse reads a passkey authenticated callback`() {
        val link = AuthDeepLinks.parse("boss://passkey/authenticated?sessionId=$sessionId")
        assertIs<AuthDeepLink.PasskeyAuthenticated>(link)
        assertEquals(sessionId, link.sessionId)
    }

    @Test
    fun `parse accepts an uppercase scheme because URI schemes are case-insensitive`() {
        val link = AuthDeepLinks.parse("BOSS://auth/verify?token=t&type=signup")
        assertIs<AuthDeepLink.MagicLinkVerify>(link)
        assertEquals("t", link.token)
    }

    @Test
    fun `parse accepts a token at the length bound`() {
        val atBound = "a".repeat(2048)
        val link = AuthDeepLinks.parse("boss://auth/verify?token=$atBound")
        assertIs<AuthDeepLink.MagicLinkVerify>(link)
        assertEquals(atBound, link.token)
    }

    @Test
    fun `parse refuses a passkey route smuggled inside a parameter value`() {
        assertNull(AuthDeepLinks.parse("boss://url?target=passkey/authenticated?sessionId=$sessionId"))
    }

    @Test
    fun `parse refuses an auth route smuggled inside a parameter value`() {
        assertNull(AuthDeepLinks.parse("boss://file/open?path=/tmp/auth/verify&token=x"))
    }

    @Test
    fun `parse refuses the passkey route on a hostile host`() {
        assertNull(AuthDeepLinks.parse("boss://evil/passkey/authenticated?sessionId=$sessionId"))
    }

    @Test
    fun `parse refuses a host that only prefixes a real one`() {
        assertNull(AuthDeepLinks.parse("boss://passkey-evil/registered?sessionId=$sessionId"))
    }

    @Test
    fun `parse refuses a session id that is not a UUID`() {
        assertNull(AuthDeepLinks.parse("boss://passkey/registered?sessionId=not-a-uuid"))
    }

    @Test
    fun `parse refuses a session id carried in the fragment instead of the query`() {
        assertNull(AuthDeepLinks.parse("boss://passkey/registered#sessionId=$sessionId"))
        assertNull(AuthDeepLinks.parse("boss://passkey/registered?sessionId=$sessionId#sessionId=$sessionId"))
    }

    @Test
    fun `parse refuses a duplicated session id`() {
        assertNull(AuthDeepLinks.parse("boss://passkey/registered?sessionId=$sessionId&sessionId=$sessionId"))
    }

    @Test
    fun `parse refuses a passkey callback without a session id`() {
        assertNull(AuthDeepLinks.parse("boss://passkey/registered"))
        assertNull(AuthDeepLinks.parse("boss://passkey/authenticated?email=attacker@evil.example"))
    }

    @Test
    fun `parse refuses an unknown path under a known host`() {
        assertNull(AuthDeepLinks.parse("boss://passkey/enroll?sessionId=$sessionId"))
        assertNull(AuthDeepLinks.parse("boss://auth/reset?token=t"))
    }

    @Test
    fun `parse refuses a non-boss scheme`() {
        assertNull(AuthDeepLinks.parse("https://auth/verify?token=t&type=signup"))
        assertNull(AuthDeepLinks.parse("evilboss://passkey/registered?sessionId=$sessionId"))
    }

    @Test
    fun `parse refuses a token with characters no producer URL ever carries`() {
        assertNull(AuthDeepLinks.parse("boss://auth/verify?token=abc+def&type=signup"))
        assertNull(AuthDeepLinks.parse("boss://auth/verify#access_token=a%20b"))
    }

    @Test
    fun `parse refuses a magic-link host with no token at all`() {
        assertNull(AuthDeepLinks.parse("boss://auth/verify"))
        assertNull(AuthDeepLinks.parse("boss://auth/verify?email=attacker@evil.example"))
    }

    @Test
    fun `parse refuses a duplicated token`() {
        assertNull(AuthDeepLinks.parse("boss://auth/verify?token=a&token=b"))
        assertNull(AuthDeepLinks.parse("boss://auth/verify#access_token=a&access_token=b"))
    }

    @Test
    fun `parse refuses a token beyond the length bound`() {
        assertNull(AuthDeepLinks.parse("boss://auth/verify?token=${"a".repeat(2049)}"))
    }

    @Test
    fun `parse refuses a case-tweaked host because the allowlist is case-sensitive`() {
        assertNull(AuthDeepLinks.parse("boss://AUTH/verify?token=t&type=signup"))
        assertNull(AuthDeepLinks.parse("boss://Passkey/registered?sessionId=$sessionId"))
    }

    @Test
    fun `parse refuses a token carried in both the query and the fragment`() {
        assertNull(AuthDeepLinks.parse("boss://auth/verify?token=a#access_token=b"))
        assertNull(AuthDeepLinks.parse("boss://auth/verify?token=a#token=b"))
    }

    @Test
    fun `parse refuses a token duplicated under two names in the fragment`() {
        assertNull(AuthDeepLinks.parse("boss://auth/verify#access_token=a&token=b"))
    }

    @Test
    fun `parse refuses a valid query token beside an empty fragment access token`() {
        // `#access_token=` carries no token-shaped value, so it does not trip the
        // ambiguity veto; the empty value is still selected over the valid query
        // token and fails tokenShape, and the link fails closed. Pinned so the
        // outcome is recorded rather than accidental.
        assertNull(AuthDeepLinks.parse("boss://auth/verify?token=t#access_token="))
    }

    @Test
    fun `parse lets the fragment type win when both sections carry one`() {
        // The refuse-on-ambiguity rule is the token's, not the type's: a `type` in both
        // sections is not a smuggle — the value is not a secret and the server rejects a
        // mismatched one — so the fragment wins as it does against the default. Pinned so
        // the difference from the token's cross-section refusal stays a visible choice.
        val link = AuthDeepLinks.parse("boss://auth/verify?token=t&type=signup#type=recovery")
        assertIs<AuthDeepLink.MagicLinkVerify>(link)
        assertEquals("t", link.token)
        assertEquals("recovery", link.type)
    }

    @Test
    fun `parse refuses a token containing a semicolon because ampersand is the only separator`() {
        // `;` never splits a query pair: `token=a;token=b` is one value carrying a `;`,
        // a character no producer's token has, so tokenShape refuses it rather than
        // resolving the pair by first match. Matches MagicLinkPaste's ktor
        // parseQueryString, which also splits on `&` alone.
        assertNull(AuthDeepLinks.parse("boss://auth/verify?token=a;token=b"))
        assertNull(AuthDeepLinks.parse("boss://passkey/registered?sessionId=$sessionId;x=y"))
    }

    @Test
    fun `parse accepts a query-token link mangled with a mail-client fragment`() {
        // Some mail clients append `#_=_` to a sign-in email's link. The fragment carries no
        // token-shaped value, so the cross-section ambiguity rule must not refuse it.
        val link = AuthDeepLinks.parse("boss://auth/verify?token=t&type=magiclink#_=_")
        assertIs<AuthDeepLink.MagicLinkVerify>(link)
        assertEquals("t", link.token)
        assertEquals("magiclink", link.type)
    }

    @Test
    fun `parse reads a producer token that encodeURIComponent leaves unescaped`() {
        // The redirect function embeds the token with encodeURIComponent, so every character
        // it escapes (%xx) is a character tokenShape refuses by design: the parser never
        // decodes. The tokens it leaves unescaped — GoTrue's hex token hash from the sign-in
        // email, and the JWTs of the Supabase success redirect — stay inside the unreserved
        // set [A-Za-z0-9._~-] that encodeURIComponent never escapes, and must keep parsing
        // verbatim, exactly as before the cross-section ambiguity rule.
        val tokenHash = "0123456789abcdef0123456789abcdef0123456789abcdef01234567"
        val unescapedChars = "aZ09.-~_"
        val fromEmail = AuthDeepLinks.parse("boss://auth/verify?token=$tokenHash&type=signup")
        assertIs<AuthDeepLink.MagicLinkVerify>(fromEmail)
        assertEquals(tokenHash, fromEmail.token)
        assertEquals("signup", fromEmail.type)
        val fromRedirect = AuthDeepLinks.parse("boss://auth/verify#access_token=$unescapedChars")
        assertIs<AuthDeepLink.MagicLinkVerify>(fromRedirect)
        assertEquals(unescapedChars, fromRedirect.token)
    }

    @Test
    fun `parse refuses a trailing slash after the allowlisted host path`() {
        // The shape some Linux .desktop deliveries produce when the Exec line quotes the URI
        // with a trailing slash: the slash is part of hostPath, so it is not the producer's
        // route.
        assertNull(AuthDeepLinks.parse("boss://auth/verify/"))
        assertNull(AuthDeepLinks.parse("boss://auth/verify/?token=t"))
    }

    @Test
    fun `parse refuses an empty authority before the auth host path`() {
        // boss:///auth/verify makes hostPath start with '/', so it is not the producer's
        // route either. Both OS-mangled forms are pinned so the refusal stays recorded.
        assertNull(AuthDeepLinks.parse("boss:///auth/verify?token=t"))
    }

    @Test
    fun `completesPasskeyCeremony binds the callback to the ceremony the app started`() {
        assertTrue(AuthDeepLinks.completesPasskeyCeremony("boss://passkey/registered?sessionId=$sessionId", sessionId))
        assertTrue(
            AuthDeepLinks.completesPasskeyCeremony("boss://passkey/authenticated?sessionId=$sessionId", sessionId),
        )
    }

    @Test
    fun `completesPasskeyCeremony refuses a callback that is not the started ceremony`() {
        val otherSessionId = "123e4567-e89b-12d3-a456-426614174999"
        assertFalse(
            AuthDeepLinks.completesPasskeyCeremony(
                "boss://passkey/authenticated?sessionId=$otherSessionId",
                sessionId,
            ),
        )
        assertFalse(AuthDeepLinks.completesPasskeyCeremony("boss://auth/verify?token=t", sessionId))
        assertFalse(
            AuthDeepLinks.completesPasskeyCeremony(
                "boss://evil/passkey/authenticated?sessionId=$sessionId",
                sessionId,
            ),
        )
        assertFalse(
            AuthDeepLinks.completesPasskeyCeremony(
                "boss://passkey/authenticated?sessionId=$sessionId#sessionId=$sessionId",
                sessionId,
            ),
        )
    }

    @Test
    fun `isAuthShaped names the OS-mangled forms without matching smuggled routes`() {
        assertTrue(AuthDeepLinks.isAuthShaped("boss://auth/verify/"))
        assertTrue(AuthDeepLinks.isAuthShaped("boss:///auth/verify?token=t"))
        // The case-folded host parse refuses as its allowlist test pins; this diagnostic
        // still names it, which is the point of the check being case-insensitive.
        assertTrue(AuthDeepLinks.isAuthShaped("boss://AUTH/verify?token=t"))
        assertFalse(AuthDeepLinks.isAuthShaped("boss://file/open?path=/tmp/auth/verify&token=x"))
        assertFalse(AuthDeepLinks.isAuthShaped("boss://url?target=passkey/authenticated?sessionId=$sessionId"))
        assertFalse(AuthDeepLinks.isAuthShaped("https://auth/verify?token=t"))
    }
}
