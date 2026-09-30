package ai.rever.boss.components.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `boss://auth/callback`, the Google / Apple sign-in return. The parser only has to hand over
 * the one shape Supabase's PKCE redirect writes; the sign-in service decides whether anything
 * was waiting for it. Every refusal here is an ambiguity a hostile page could otherwise use.
 */
class AuthDeepLinksOAuthCallbackTest {
    private val code = "7f1c2a9e-4b1d-4a57-9d2e-3b8f0c6a1e22"

    @Test
    fun `reads the PKCE code from the query`() {
        val link = AuthDeepLinks.parse("boss://auth/callback?code=$code")
        assertIs<AuthDeepLink.OAuthCallback>(link)
        assertEquals(code, link.code)
        assertNull(link.error)
    }

    @Test
    fun `reads an error from the query and decodes its description`() {
        val link =
            AuthDeepLinks.parse(
                "boss://auth/callback?error=access_denied&error_code=user_cancelled" +
                    "&error_description=User+cancelled%20the%20flow",
            )
        assertIs<AuthDeepLink.OAuthCallback>(link)
        assertNull(link.code)
        assertEquals("access_denied", link.error)
        assertEquals("User cancelled the flow", link.errorDescription)
    }

    @Test
    fun `reads an error from the fragment`() {
        val link = AuthDeepLinks.parse("boss://auth/callback#error=server_error&error_description=boom")
        assertIs<AuthDeepLink.OAuthCallback>(link)
        assertEquals("server_error", link.error)
    }

    @Test
    fun `accepts GoTrue's error redirect, which repeats the error in the query and the fragment`() {
        // supabase/auth redirectErrors writes error, error_code and error_description into both
        // sections, plus an `sb` marker in the fragment.
        val link =
            AuthDeepLinks.parse(
                "boss://auth/callback?error=access_denied&error_code=user_cancelled&error_description=Query+text" +
                    "#error=access_denied&error_code=user_cancelled&error_description=Fragment+text&sb",
            )
        assertIs<AuthDeepLink.OAuthCallback>(link)
        assertEquals("access_denied", link.error)
        // The description comes from the same section as the error it explains.
        assertEquals("Query text", link.errorDescription)
    }

    @Test
    fun `refuses a query and a fragment that disagree about the error`() {
        assertNull(AuthDeepLinks.parse("boss://auth/callback?error=access_denied#error=server_error"))
    }

    @Test
    fun `a fragment-only error takes its description from the fragment`() {
        val link =
            AuthDeepLinks.parse(
                "boss://auth/callback?error_description=planted#error=server_error&error_description=real",
            )
        assertIs<AuthDeepLink.OAuthCallback>(link)
        assertEquals("real", link.errorDescription)
    }

    @Test
    fun `a description is bounded, stripped of control characters, and dropped when undecodable`() {
        val long = AuthDeepLinks.parse("boss://auth/callback?error=server_error&error_description=${"a".repeat(1000)}")
        assertEquals(300, assertIs<AuthDeepLink.OAuthCallback>(long).errorDescription?.length)

        val controls =
            AuthDeepLinks.parse("boss://auth/callback?error=server_error&error_description=line%0Aone%1B%5B2J")
        assertEquals("lineone[2J", assertIs<AuthDeepLink.OAuthCallback>(controls).errorDescription)

        val malformed = AuthDeepLinks.parse("boss://auth/callback?error=server_error&error_description=bad%ZZ")
        val callback = assertIs<AuthDeepLink.OAuthCallback>(malformed)
        assertEquals("server_error", callback.error)
        assertNull(callback.errorDescription)
    }

    @Test
    fun `refuses a link carrying both a code and an error`() {
        assertNull(AuthDeepLinks.parse("boss://auth/callback?code=$code&error=access_denied"))
        assertNull(AuthDeepLinks.parse("boss://auth/callback?code=$code#error=access_denied"))
    }

    @Test
    fun `refuses a link carrying neither`() {
        assertNull(AuthDeepLinks.parse("boss://auth/callback"))
        assertNull(AuthDeepLinks.parse("boss://auth/callback?state=x"))
    }

    @Test
    fun `refuses duplicates and a code in the fragment`() {
        assertNull(AuthDeepLinks.parse("boss://auth/callback?code=$code&code=other"))
        assertNull(AuthDeepLinks.parse("boss://auth/callback#code=$code"))
        assertNull(AuthDeepLinks.parse("boss://auth/callback?error=access_denied&error=access_denied"))
    }

    @Test
    fun `refuses values outside the producer's shape`() {
        assertNull(AuthDeepLinks.parse("boss://auth/callback?code=a%26type%3Drecovery"))
        assertNull(AuthDeepLinks.parse("boss://auth/callback?error=Access-Denied"))
    }

    @Test
    fun `refuses lookalike routes`() {
        assertNull(AuthDeepLinks.parse("boss://AUTH/callback?code=$code"))
        assertNull(AuthDeepLinks.parse("boss://auth/callback/?code=$code"))
        assertNull(AuthDeepLinks.parse("boss://url?target=auth/callback?code=$code"))
        assertNull(AuthDeepLinks.parse("https://auth/callback?code=$code"))
    }

    @Test
    fun `a mangled callback is auth-shaped for the diagnostic`() {
        assertTrue(AuthDeepLinks.isAuthShaped("boss://auth/callback/?code=$code"))
        assertFalse(AuthDeepLinks.isAuthShaped("boss://url?target=auth/callback"))
    }

    @Test
    fun `toString never prints the code`() {
        val link = AuthDeepLinks.parse("boss://auth/callback?code=$code")
        assertFalse(link.toString().contains(code))
    }
}
