package ai.rever.boss.services.auth

import ai.rever.boss.components.auth.AuthDeepLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

/**
 * The sign-in flow's guarantees: a callback is acted on only while this process's own sign-in
 * is waiting and before it expires; a callback that fails does not end that sign-in, since any
 * page can send one; a session is adopted only if its sign-in is still the one waiting; and the
 * verifier handed to the cache is the one whose challenge went into the authorize URL.
 */
class OAuthSignInFlowTest {
    private val time = TestTimeSource()
    private var storedVerifier: String? = null
    private var sentChallenge: String? = null
    private var sentRedirect: String? = null
    private val exchanged = mutableListOf<String>()
    private val adopted = mutableListOf<String>()
    private var exchangeFailure: Exception? = null
    private var adoptFailure: Exception? = null

    /** Codes the fake Supabase refuses, as it would a forged one. */
    private val rejectedCodes = mutableSetOf<String>()
    private var verifierClears = 0

    /** When set, an exchange waits here after it starts, so a test can act mid-exchange. */
    private var exchangeGate: CompletableDeferred<Unit>? = null
    private val exchangeEntered = CompletableDeferred<Unit>()
    private val opened = mutableListOf<String>()

    private fun newFlow(prepareVerifier: suspend (String) -> Unit = { storedVerifier = it }) =
        OAuthSignInFlow(
            prepareVerifier = prepareVerifier,
            buildAuthorizeUrl = { provider, redirect, challenge ->
                sentRedirect = redirect
                sentChallenge = challenge
                "https://example.supabase.co/auth/v1/authorize?provider=${provider.name.lowercase()}"
            },
            exchangeCode = { code ->
                exchangeEntered.complete(Unit)
                exchangeGate?.await()
                exchangeFailure?.let { throw it }
                if (code in rejectedCodes) error("invalid flow state")
                exchanged += code
                val adopt: suspend () -> Unit = {
                    adoptFailure?.let { throw it }
                    adopted += code
                }
                adopt
            },
            openBrowser = { _, url ->
                opened += url
                true
            },
            clearVerifier = { verifierClears++ },
            timeSource = time,
        )

    private val flow = newFlow()

    private fun codeCallback(code: String = "the-code") = AuthDeepLink.OAuthCallback(code, null, null)

    private val cancelledByUser = AuthDeepLink.OAuthCallback(null, "access_denied", "User cancelled")

    private fun errorCallback() = cancelledByUser

    @Test
    fun `a forged code in flight cannot swallow the real one that arrives meanwhile`(): Unit =
        runBlocking {
            flow.start(OAuthProviderKind.GOOGLE)
            rejectedCodes += "forged"
            exchangeGate = CompletableDeferred()

            val forged = async { flow.complete(codeCallback("forged")) }
            exchangeEntered.await()
            // The user's own callback lands while the forged one is still with Supabase.
            assertEquals(OAuthCompletion.IGNORED, flow.complete(codeCallback("real")))
            checkNotNull(exchangeGate).complete(Unit)

            assertEquals(OAuthCompletion.SIGNED_IN, forged.await())
            assertEquals(listOf("real"), adopted)
            assertIs<OAuthSignInState.Idle>(flow.state.value)
        }

    @Test
    fun `a sign-in stops exchanging after its cap and asks the user to start again`(): Unit =
        runBlocking {
            flow.start(OAuthProviderKind.APPLE)
            repeat(OAuthSignInFlow.MAX_EXCHANGES_PER_ATTEMPT) { i ->
                rejectedCodes += "forged-$i"
                assertEquals(OAuthCompletion.FAILED, flow.complete(codeCallback("forged-$i")))
            }

            assertEquals(OAuthCompletion.FAILED, flow.complete(codeCallback("real")))
            assertTrue(exchanged.isEmpty())
            assertEquals(
                OAuthSignInFlow.tooManyCallbacks(OAuthProviderKind.APPLE),
                assertIs<OAuthSignInState.WaitingForBrowser>(flow.state.value).notice,
            )
        }

    @Test
    fun `cancel and expiry clear the verifier, a failed exchange keeps it for the real callback`(): Unit =
        runBlocking {
            flow.start(OAuthProviderKind.GOOGLE)
            rejectedCodes += "forged"
            flow.complete(codeCallback("forged"))
            assertEquals(0, verifierClears, "a failed exchange must leave the verifier for a retry")

            flow.cancel()
            assertEquals(1, verifierClears)

            flow.start(OAuthProviderKind.GOOGLE)
            time += 11.minutes
            assertTrue(flow.expireIfStale())
            assertEquals(2, verifierClears)

            flow.start(OAuthProviderKind.GOOGLE)
            time += 11.minutes
            assertEquals(OAuthCompletion.FAILED, flow.complete(codeCallback()))
            assertEquals(3, verifierClears)

            // Cancelling with nothing waiting touches nothing.
            flow.cancel()
            assertEquals(3, verifierClears)
        }

    @Test
    fun `start stores a verifier whose S256 challenge is sent, and opens the browser`(): Unit =
        runBlocking {
            flow.start(OAuthProviderKind.GOOGLE)

            val verifier = checkNotNull(storedVerifier)
            assertEquals(64, verifier.length)
            assertEquals(OAuthSignInFlow.codeChallengeOf(verifier), sentChallenge)
            assertEquals("boss://auth/callback", sentRedirect)
            assertEquals(1, opened.size)
            val state = assertIs<OAuthSignInState.WaitingForBrowser>(flow.state.value)
            assertNull(state.notice)
            assertEquals(OAuthProviderKind.GOOGLE, state.provider)
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
            assertEquals(OAuthCompletion.IGNORED, flow.complete(errorCallback()))
            assertTrue(exchanged.isEmpty())
            assertIs<OAuthSignInState.Idle>(flow.state.value)
        }

    @Test
    fun `a waiting sign-in completes once and a later delivery is ignored`(): Unit =
        runBlocking {
            flow.start(OAuthProviderKind.APPLE)

            assertEquals(OAuthCompletion.SIGNED_IN, flow.complete(codeCallback()))
            assertEquals(OAuthCompletion.IGNORED, flow.complete(codeCallback()))
            assertEquals(listOf("the-code"), exchanged)
            assertEquals(listOf("the-code"), adopted)
            assertIs<OAuthSignInState.Idle>(flow.state.value)
        }

    @Test
    fun `a second callback while one is exchanging is ignored`(): Unit =
        runBlocking {
            flow.start(OAuthProviderKind.GOOGLE)
            exchangeGate = CompletableDeferred()

            val first = async { flow.complete(codeCallback("first")) }
            exchangeEntered.await()
            assertIs<OAuthSignInState.Exchanging>(flow.state.value)
            assertEquals(OAuthCompletion.IGNORED, flow.complete(codeCallback("second")))
            assertEquals(OAuthCompletion.IGNORED, flow.complete(errorCallback()))

            checkNotNull(exchangeGate).complete(Unit)
            assertEquals(OAuthCompletion.SIGNED_IN, first.await())
            assertEquals(listOf("first"), exchanged)
        }

    @Test
    fun `a provider error shows a notice and leaves the sign-in open`(): Unit =
        runBlocking {
            flow.start(OAuthProviderKind.APPLE)

            assertEquals(OAuthCompletion.FAILED, flow.complete(errorCallback()))

            val state = assertIs<OAuthSignInState.WaitingForBrowser>(flow.state.value)
            assertEquals("Apple sign-in was cancelled.", state.notice)
            flow.reopenBrowser()
            assertNull(assertIs<OAuthSignInState.WaitingForBrowser>(flow.state.value).notice)
            // A forged error link cannot end the real sign-in: its callback still works.
            assertEquals(OAuthCompletion.SIGNED_IN, flow.complete(codeCallback()))
            assertEquals(listOf("the-code"), adopted)
        }

    @Test
    fun `a failed exchange shows a notice and the real callback can still sign in`(): Unit =
        runBlocking {
            flow.start(OAuthProviderKind.GOOGLE)
            exchangeFailure = IllegalStateException("network down")

            assertEquals(OAuthCompletion.FAILED, flow.complete(codeCallback("forged")))
            val state = assertIs<OAuthSignInState.WaitingForBrowser>(flow.state.value)
            assertEquals("Google sign-in failed. Please try again.", state.notice)

            exchangeFailure = null
            assertEquals(OAuthCompletion.SIGNED_IN, flow.complete(codeCallback()))
            assertEquals(listOf("the-code"), adopted)
        }

    @Test
    fun `cancelling during the exchange discards the session it returns`(): Unit =
        runBlocking {
            flow.start(OAuthProviderKind.GOOGLE)
            exchangeGate = CompletableDeferred()

            val outcome = async { flow.complete(codeCallback()) }
            exchangeEntered.await()
            flow.cancel()
            assertIs<OAuthSignInState.Idle>(flow.state.value)
            checkNotNull(exchangeGate).complete(Unit)

            assertEquals(OAuthCompletion.IGNORED, outcome.await())
            assertEquals(listOf("the-code"), exchanged)
            assertTrue(adopted.isEmpty())
            assertIs<OAuthSignInState.Idle>(flow.state.value)
        }

    @Test
    fun `a new start during the exchange discards the old session and keeps the new sign-in`(): Unit =
        runBlocking {
            flow.start(OAuthProviderKind.GOOGLE)
            exchangeGate = CompletableDeferred()

            val outcome = async { flow.complete(codeCallback("old")) }
            exchangeEntered.await()
            flow.start(OAuthProviderKind.APPLE)
            checkNotNull(exchangeGate).complete(Unit)

            assertEquals(OAuthCompletion.IGNORED, outcome.await())
            assertTrue(adopted.isEmpty())
            val waiting = assertIs<OAuthSignInState.WaitingForBrowser>(flow.state.value)
            assertEquals(OAuthProviderKind.APPLE, waiting.provider)
        }

    @Test
    fun `an exchange whose caller is cancelled goes back to waiting`(): Unit =
        runBlocking {
            flow.start(OAuthProviderKind.GOOGLE)
            exchangeGate = CompletableDeferred()

            val job = launch { flow.complete(codeCallback("interrupted")) }
            exchangeEntered.await()
            job.cancelAndJoin()

            assertIs<OAuthSignInState.WaitingForBrowser>(flow.state.value)
            exchangeGate = null
            assertEquals(OAuthCompletion.SIGNED_IN, flow.complete(codeCallback()))
        }

    /**
     * Cancels [complete]'s caller after the exchange returned but while it waits for the lock: a
     * second start holds the lock inside its prepareVerifier, then is cancelled itself so the
     * Google attempt is still the waiting one. Returns the flow once both have finished.
     */
    private suspend fun cancelWhileWaitingForTheLock(): OAuthSignInFlow =
        kotlinx.coroutines.coroutineScope {
            val lockHeld = CompletableDeferred<Unit>()
            var starts = 0
            val flow =
                newFlow(prepareVerifier = { verifier ->
                    if (++starts == 1) {
                        storedVerifier = verifier
                    } else {
                        lockHeld.complete(Unit)
                        kotlinx.coroutines.awaitCancellation()
                    }
                })
            flow.start(OAuthProviderKind.GOOGLE)
            val gate = CompletableDeferred<Unit>().also { exchangeGate = it }

            val caller = launch { flow.complete(codeCallback()) }
            exchangeEntered.await()
            val holder = launch { flow.start(OAuthProviderKind.APPLE) }
            lockHeld.await()
            gate.complete(Unit)
            kotlinx.coroutines.yield() // the exchange returns and queues on the held lock
            assertIs<OAuthSignInState.Exchanging>(flow.state.value)

            caller.cancel()
            holder.cancelAndJoin()
            caller.join()
            flow
        }

    @Test
    fun `an exchange cancelled while it waits for the lock still adopts its session`(): Unit =
        runBlocking {
            val flow = cancelWhileWaitingForTheLock()

            assertEquals(listOf("the-code"), adopted)
            assertIs<OAuthSignInState.Idle>(flow.state.value)
        }

    @Test
    fun `a failed exchange cancelled while it waits for the lock goes back to waiting`(): Unit =
        runBlocking {
            exchangeFailure = IllegalStateException("bad code")
            val flow = cancelWhileWaitingForTheLock()

            assertIs<OAuthSignInState.WaitingForBrowser>(flow.state.value)
            exchangeFailure = null
            assertEquals(OAuthCompletion.SIGNED_IN, flow.complete(codeCallback("the-real-code")))
        }

    @Test
    fun `a session that cannot be saved ends the sign-in with an error`(): Unit =
        runBlocking {
            flow.start(OAuthProviderKind.APPLE)
            adoptFailure = IllegalStateException("keychain locked")

            assertEquals(OAuthCompletion.FAILED, flow.complete(codeCallback()))
            assertIs<OAuthSignInState.Error>(flow.state.value)
            assertEquals(OAuthCompletion.IGNORED, flow.complete(codeCallback()))
        }

    @Test
    fun `an expired sign-in is refused without exchanging`(): Unit =
        runBlocking {
            flow.start(OAuthProviderKind.GOOGLE)
            time += 11.minutes

            assertEquals(OAuthCompletion.FAILED, flow.complete(codeCallback()))
            assertTrue(exchanged.isEmpty())
            assertIs<OAuthSignInState.Error>(flow.state.value)
            assertEquals(OAuthCompletion.IGNORED, flow.complete(codeCallback()))
        }

    @Test
    fun `the waiting screen's poll expires a sign-in whose callback never came`(): Unit =
        runBlocking {
            assertFalse(flow.expireIfStale())
            flow.start(OAuthProviderKind.GOOGLE)
            time += 9.minutes
            assertFalse(flow.expireIfStale())
            assertIs<OAuthSignInState.WaitingForBrowser>(flow.state.value)

            time += 2.minutes
            assertTrue(flow.expireIfStale())
            val state = assertIs<OAuthSignInState.Error>(flow.state.value)
            assertEquals("That Google sign-in took too long. Please try again.", state.message)
            assertFalse(flow.expireIfStale())
        }

    @Test
    fun `an exchange in flight is never expired under it`(): Unit =
        runBlocking {
            flow.start(OAuthProviderKind.GOOGLE)
            exchangeGate = CompletableDeferred()
            val outcome = async { flow.complete(codeCallback()) }
            exchangeEntered.await()

            time += 11.minutes
            assertFalse(flow.expireIfStale())
            checkNotNull(exchangeGate).complete(Unit)
            assertEquals(OAuthCompletion.SIGNED_IN, outcome.await())
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
    fun `a second press while a start is being prepared opens nothing more`(): Unit =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val slow =
                newFlow(prepareVerifier = {
                    entered.complete(Unit)
                    gate.await()
                })

            val first = async { slow.start(OAuthProviderKind.GOOGLE) }
            entered.await()
            assertEquals(OAuthProviderKind.GOOGLE, slow.starting.value)
            assertEquals(OAuthStart.IGNORED, slow.start(OAuthProviderKind.APPLE))
            gate.complete(Unit)
            first.await()

            assertEquals(1, opened.size)
            assertNull(slow.starting.value)
            assertEquals(OAuthProviderKind.GOOGLE, slow.state.value.provider)
        }

    @Test
    fun `a verifier that cannot be stored fails the start and opens nothing`(): Unit =
        runBlocking {
            val broken = newFlow(prepareVerifier = { error("disk full") })

            assertEquals(OAuthStart.FAILED, broken.start(OAuthProviderKind.GOOGLE))
            assertTrue(opened.isEmpty())
            assertNull(broken.starting.value)
            assertIs<OAuthSignInState.Error>(broken.state.value)
            assertEquals(OAuthCompletion.IGNORED, broken.complete(codeCallback()))

            broken.dismissError()
            assertIs<OAuthSignInState.Idle>(broken.state.value)
            assertNull(broken.state.value.provider)
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
                    exchangeCode = { { } },
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
