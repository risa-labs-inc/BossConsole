package ai.rever.boss.services.auth

import ai.rever.boss.components.auth.AuthDeepLink
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

/**
 * The sign-in flow's guarantees: a callback is acted on only while this process's own sign-in
 * is waiting, once, and before it expires; the verifier handed to the cache is the one whose
 * challenge went into the authorize URL.
 */
class OAuthSignInFlowTest {
    private val time = TestTimeSource()
    private var storedVerifier: String? = null
    private var sentChallenge: String? = null
    private var sentRedirect: String? = null
    private val exchanged = mutableListOf<String>()
    private var exchangeFailure: Exception? = null
    private val opened = mutableListOf<String>()

    private val flow =
        OAuthSignInFlow(
            prepareVerifier = { storedVerifier = it },
            buildAuthorizeUrl = { provider, redirect, challenge ->
                sentRedirect = redirect
                sentChallenge = challenge
                "https://example.supabase.co/auth/v1/authorize?provider=${provider.name.lowercase()}"
            },
            exchangeCode = { code ->
                exchangeFailure?.let { throw it }
                exchanged += code
            },
            openBrowser = { _, url ->
                opened += url
                true
            },
            timeSource = time,
        )

    private fun codeCallback(code: String = "the-code") = AuthDeepLink.OAuthCallback(code, null, null)

    @Test
    fun `start stores a verifier whose S256 challenge is sent, and opens the browser`(): Unit =
        runBlocking {
            flow.start(OAuthProviderKind.GOOGLE)

            val verifier = checkNotNull(storedVerifier)
            assertEquals(64, verifier.length)
            assertEquals(OAuthSignInFlow.codeChallengeOf(verifier), sentChallenge)
            assertEquals("boss://auth/callback", sentRedirect)
            assertEquals(1, opened.size)
            assertIs<OAuthSignInState.WaitingForBrowser>(flow.state.value)
        }

    @Test
    fun `the S256 challenge matches the RFC 7636 test vector`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            OAuthSignInFlow.codeChallengeOf("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `a callback with no sign-in waiting is ignored and exchanges nothing`(): Unit =
        runBlocking {
            assertEquals(OAuthCompletion.IGNORED, flow.complete(codeCallback()))
            assertTrue(exchanged.isEmpty())
            assertIs<OAuthSignInState.Idle>(flow.state.value)
        }

    @Test
    fun `a waiting sign-in completes once and a duplicate delivery is ignored`(): Unit =
        runBlocking {
            flow.start(OAuthProviderKind.APPLE)

            assertEquals(OAuthCompletion.SIGNED_IN, flow.complete(codeCallback()))
            assertEquals(OAuthCompletion.IGNORED, flow.complete(codeCallback()))
            assertEquals(listOf("the-code"), exchanged)
            assertIs<OAuthSignInState.Idle>(flow.state.value)
        }

    @Test
    fun `an expired sign-in is refused without exchanging`(): Unit =
        runBlocking {
            flow.start(OAuthProviderKind.GOOGLE)
            time += 11.minutes

            assertEquals(OAuthCompletion.FAILED, flow.complete(codeCallback()))
            assertTrue(exchanged.isEmpty())
            assertIs<OAuthSignInState.Error>(flow.state.value)
        }

    @Test
    fun `a cancelled sign-in ignores its late callback`(): Unit =
        runBlocking {
            flow.start(OAuthProviderKind.GOOGLE)
            flow.cancel()

            assertEquals(OAuthCompletion.IGNORED, flow.complete(codeCallback()))
            assertTrue(exchanged.isEmpty())
        }

    @Test
    fun `a provider error becomes readable text and consumes the attempt`(): Unit =
        runBlocking {
            flow.start(OAuthProviderKind.APPLE)

            val outcome = flow.complete(AuthDeepLink.OAuthCallback(null, "access_denied", "User cancelled"))

            assertEquals(OAuthCompletion.FAILED, outcome)
            val state = assertIs<OAuthSignInState.Error>(flow.state.value)
            assertEquals("Apple sign-in was cancelled.", state.message)
            assertEquals(OAuthCompletion.IGNORED, flow.complete(codeCallback()))
        }

    @Test
    fun `a failed exchange reports an error and leaves nothing waiting`(): Unit =
        runBlocking {
            flow.start(OAuthProviderKind.GOOGLE)
            exchangeFailure = IllegalStateException("network down")

            assertEquals(OAuthCompletion.FAILED, flow.complete(codeCallback()))
            val state = assertIs<OAuthSignInState.Error>(flow.state.value)
            assertEquals("Google sign-in failed. Please try again.", state.message)

            flow.dismissError()
            assertIs<OAuthSignInState.Idle>(flow.state.value)
        }

    @Test
    fun `a verifier that cannot be stored fails the start and opens nothing`(): Unit =
        runBlocking {
            val broken =
                OAuthSignInFlow(
                    prepareVerifier = { error("disk full") },
                    buildAuthorizeUrl = { _, _, _ -> "unused" },
                    exchangeCode = {},
                    openBrowser = { _, url ->
                        opened += url
                        true
                    },
                )

            assertTrue(broken.start(OAuthProviderKind.GOOGLE).isFailure)
            assertTrue(opened.isEmpty())
            assertIs<OAuthSignInState.Error>(broken.state.value)
            assertEquals(OAuthCompletion.IGNORED, broken.complete(codeCallback()))
        }

    @Test
    fun `verifiers are fresh per start`(): Unit =
        runBlocking {
            flow.start(OAuthProviderKind.GOOGLE)
            val first = storedVerifier
            flow.start(OAuthProviderKind.GOOGLE)
            assertTrue(first != storedVerifier)
        }

    @Test
    fun `Apple opens in Safari on macOS and nothing else picks a browser`() {
        val url = "https://api.risaboss.com/auth/v1/authorize?provider=apple"
        assertEquals(
            listOf("open", "-a", "Safari", url),
            preferredBrowserCommand(OAuthProviderKind.APPLE, "Mac OS X", url),
        )
        assertNull(preferredBrowserCommand(OAuthProviderKind.GOOGLE, "Mac OS X", url))
        assertNull(preferredBrowserCommand(OAuthProviderKind.APPLE, "Windows 11", url))
        assertNull(preferredBrowserCommand(OAuthProviderKind.APPLE, "Linux", url))
    }

    @Test
    fun `reopening the page passes the waiting provider`(): Unit =
        runBlocking {
            val providers = mutableListOf<OAuthProviderKind>()
            val flow =
                OAuthSignInFlow(
                    prepareVerifier = {},
                    buildAuthorizeUrl = { _, _, _ -> "https://example/authorize" },
                    exchangeCode = {},
                    openBrowser = { provider, _ ->
                        providers += provider
                        true
                    },
                )
            flow.start(OAuthProviderKind.APPLE)
            flow.reopenBrowser()
            assertEquals(listOf(OAuthProviderKind.APPLE, OAuthProviderKind.APPLE), providers)
        }
}
