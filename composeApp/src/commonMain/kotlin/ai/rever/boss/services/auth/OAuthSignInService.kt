package ai.rever.boss.services.auth

import ai.rever.boss.components.auth.AuthDeepLink
import ai.rever.boss.services.supabase.SupabaseConfig
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.Apple
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.OAuthProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** The identity providers BOSS offers besides email and passkeys. */
enum class OAuthProviderKind(
    val displayName: String,
) {
    GOOGLE("Google"),
    APPLE("Apple"),
}

/** Where a Google or Apple sign-in stands, for the login screen. */
sealed interface OAuthSignInState {
    data object Idle : OAuthSignInState

    /** The sign-in page is open in the system browser; waiting for `boss://auth/callback`. */
    data class WaitingForBrowser(
        val provider: OAuthProviderKind,
        val authorizeUrl: String,
    ) : OAuthSignInState

    /** The callback arrived and the code is being exchanged for a session. */
    data class Exchanging(
        val provider: OAuthProviderKind,
    ) : OAuthSignInState

    data class Error(
        val provider: OAuthProviderKind?,
        val message: String,
    ) : OAuthSignInState
}

/** What [OAuthSignInFlow.complete] did with one callback. */
enum class OAuthCompletion {
    /** A session was established; the auth state collector takes it from here. */
    SIGNED_IN,

    /** The callback reported a failure, or the exchange failed; [OAuthSignInFlow.state] says why. */
    FAILED,

    /** No sign-in was waiting for a callback, so the link was dropped without acting on it. */
    IGNORED,
}

/**
 * Google and Apple sign-in through Supabase, as a PKCE authorization-code flow in the system
 * browser that returns to `boss://auth/callback`.
 *
 * BOSS does not use supabase-kt's `signInWith(Google)` on desktop: that starts a localhost ktor
 * server, which the host build excludes on purpose (`KtorServerAbsentFromHostTest`). Instead
 * [start] writes a fresh PKCE verifier into Auth's encrypted code-verifier cache, opens the
 * authorize URL with the matching challenge, and [complete] exchanges the returned code with
 * `exchangeCodeForSession`, which reads that verifier back.
 *
 * PKCE is enabled for this flow only, through the authorize URL's query, not with a global
 * `flowType = PKCE`: under a global PKCE flow the magic-link OTP send would write its own
 * verifier into the same single-slot cache and could clobber a sign-in in progress.
 *
 * `boss://` is registered with the OS, so any page can open a callback link. A callback is
 * acted on only while a sign-in this process started is waiting and not expired, and the code
 * is useless without the verifier, which never leaves this machine. Codes, verifiers and tokens
 * are never logged.
 */
internal class OAuthSignInFlow(
    private val prepareVerifier: suspend (verifier: String) -> Unit,
    private val buildAuthorizeUrl: (provider: OAuthProviderKind, redirectUrl: String, codeChallenge: String) -> String,
    private val exchangeCode: suspend (code: String) -> Unit,
    private val openBrowser: suspend (provider: OAuthProviderKind, url: String) -> Boolean,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val attemptTimeout: Duration = ATTEMPT_TIMEOUT,
) {
    private val logger = BossLogger.forComponent("OAuthSignInService")

    private val _state = MutableStateFlow<OAuthSignInState>(OAuthSignInState.Idle)
    val state: StateFlow<OAuthSignInState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var pending: PendingAttempt? = null

    private class PendingAttempt(
        val provider: OAuthProviderKind,
        val startedAt: TimeMark,
    )

    /**
     * Open [provider]'s sign-in page in the system browser. A sign-in already waiting is
     * replaced: its verifier is overwritten, so its callback can no longer be exchanged.
     */
    // The cache write and URL build fail in unrelated ways; any of them fails the start.
    @Suppress("TooGenericExceptionCaught")
    suspend fun start(provider: OAuthProviderKind): Result<Unit> {
        val url =
            try {
                mutex.withLock {
                    val verifier = generateCodeVerifier()
                    prepareVerifier(verifier)
                    val url = buildAuthorizeUrl(provider, REDIRECT_URL, codeChallengeOf(verifier))
                    pending = PendingAttempt(provider, timeSource.markNow())
                    _state.value = OAuthSignInState.WaitingForBrowser(provider, url)
                    url
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(
                    LogCategory.AUTH,
                    "Could not prepare OAuth sign-in",
                    mapOf("provider" to provider.name),
                    error = e,
                )
                return fail(provider, "Couldn't start ${provider.displayName} sign-in. Please try again.")
            }
        logger.info(LogCategory.AUTH, "OAuth sign-in started", mapOf("provider" to provider.name))
        if (!openBrowser(provider, url)) {
            // Stay in WaitingForBrowser: the waiting screen offers to copy the link instead.
            logger.warn(LogCategory.AUTH, "Could not open the system browser for OAuth sign-in")
        }
        return Result.success(Unit)
    }

    /** Reopen the waiting sign-in's page, for a browser tab the user closed. */
    suspend fun reopenBrowser(): Boolean {
        val waiting = _state.value as? OAuthSignInState.WaitingForBrowser ?: return false
        return openBrowser(waiting.provider, waiting.authorizeUrl)
    }

    /** Drop the waiting sign-in; its callback is ignored if it still arrives. */
    suspend fun cancel() {
        mutex.withLock {
            if (pending != null) logger.info(LogCategory.AUTH, "OAuth sign-in cancelled")
            pending = null
            _state.value = OAuthSignInState.Idle
        }
    }

    /** Clear a shown error, back to the provider buttons. */
    fun dismissError() {
        _state.update { if (it is OAuthSignInState.Error) OAuthSignInState.Idle else it }
    }

    /**
     * Finish the waiting sign-in with [callback]. The attempt is consumed before anything else,
     * so a duplicate delivery (one per open window, or a second OS hand-off) is ignored.
     */
    suspend fun complete(callback: AuthDeepLink.OAuthCallback): OAuthCompletion =
        when (val claim = mutex.withLock { claim(callback) }) {
            is Claim.Done -> claim.outcome
            is Claim.Exchange -> exchange(claim.provider, claim.code)
        }

    /** What one callback does to the waiting attempt. Runs under [mutex]; always consumes it. */
    private fun claim(callback: AuthDeepLink.OAuthCallback): Claim {
        val attempt = pending
        pending = null
        val code = callback.code
        return when {
            attempt == null -> {
                logIgnored("no sign-in is waiting")
                Claim.Done(OAuthCompletion.IGNORED)
            }

            attempt.startedAt.elapsedNow() > attemptTimeout -> {
                logIgnored("the sign-in expired")
                fail(attempt.provider, "That ${attempt.provider.displayName} sign-in took too long. Please try again.")
                Claim.Done(OAuthCompletion.FAILED)
            }

            callback.error != null -> {
                logger.warn(
                    LogCategory.AUTH,
                    "OAuth provider returned an error",
                    mapOf(
                        "provider" to attempt.provider.name,
                        "error" to callback.error,
                        "description" to (callback.errorDescription ?: ""),
                    ),
                )
                fail(attempt.provider, describeCallbackError(attempt.provider, callback.error))
                Claim.Done(OAuthCompletion.FAILED)
            }

            // AuthDeepLinks guarantees exactly one of code and error; a hand-built callback may not.
            code == null -> {
                _state.value = OAuthSignInState.Idle
                Claim.Done(OAuthCompletion.IGNORED)
            }

            else -> {
                _state.value = OAuthSignInState.Exchanging(attempt.provider)
                Claim.Exchange(attempt.provider, code)
            }
        }
    }

    // supabase-kt surfaces RestException, HttpRequestException and IO failures alike.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun exchange(
        provider: OAuthProviderKind,
        code: String,
    ): OAuthCompletion =
        try {
            exchangeCode(code)
            logger.info(LogCategory.AUTH, "OAuth sign-in completed", mapOf("provider" to provider.name))
            _state.value = OAuthSignInState.Idle
            OAuthCompletion.SIGNED_IN
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(LogCategory.AUTH, "OAuth code exchange failed", mapOf("provider" to provider.name), error = e)
            fail(provider, describeExchangeFailure(provider, e))
            OAuthCompletion.FAILED
        }

    private sealed interface Claim {
        data class Done(
            val outcome: OAuthCompletion,
        ) : Claim

        data class Exchange(
            val provider: OAuthProviderKind,
            val code: String,
        ) : Claim
    }

    private fun fail(
        provider: OAuthProviderKind?,
        message: String,
    ): Result<Unit> {
        _state.value = OAuthSignInState.Error(provider, message)
        return Result.failure(Exception(message))
    }

    private fun logIgnored(reason: String) {
        logger.warn(LogCategory.AUTH, "OAuth callback ignored", mapOf("reason" to reason))
    }

    companion object {
        const val REDIRECT_URL = "boss://auth/callback"
        val ATTEMPT_TIMEOUT = 10.minutes

        private const val VERIFIER_BYTES = 48
        private val random = SecureRandom()

        /** An RFC 7636 verifier: 64 unpadded base64url characters. */
        internal fun generateCodeVerifier(): String {
            val bytes = ByteArray(VERIFIER_BYTES).also(random::nextBytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        /** The S256 challenge for [verifier]. */
        internal fun codeChallengeOf(verifier: String): String {
            val hash = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
        }

        internal fun describeCallbackError(
            provider: OAuthProviderKind,
            error: String,
        ): String =
            when (error) {
                "access_denied" -> {
                    "${provider.displayName} sign-in was cancelled."
                }

                "temporarily_unavailable", "server_error" -> {
                    "${provider.displayName} sign-in is having trouble right now. Please try again in a minute."
                }

                else -> {
                    "${provider.displayName} sign-in failed. Please try again."
                }
            }

        internal fun describeExchangeFailure(
            provider: OAuthProviderKind,
            error: Throwable,
        ): String =
            when ((error as? AuthRestException)?.errorCode) {
                AuthErrorCode.FlowStateNotFound, AuthErrorCode.FlowStateExpired, AuthErrorCode.BadCodeVerifier -> {
                    "That ${provider.displayName} sign-in expired or was already used. Please try again."
                }

                AuthErrorCode.ProviderDisabled, AuthErrorCode.SignupDisabled -> {
                    "${provider.displayName} sign-in is currently unavailable."
                }

                AuthErrorCode.UserBanned -> {
                    "This account has been suspended. Please contact support."
                }

                else -> {
                    "${provider.displayName} sign-in failed. Please try again."
                }
            }

        internal fun oauthProviderOf(kind: OAuthProviderKind): OAuthProvider =
            when (kind) {
                OAuthProviderKind.GOOGLE -> Google
                OAuthProviderKind.APPLE -> Apple
            }
    }
}

/** The process-wide Google/Apple sign-in, wired to the app's Supabase client. */
internal val OAuthSignInService: OAuthSignInFlow by lazy {
    OAuthSignInFlow(
        prepareVerifier = { verifier ->
            SupabaseConfig.client.auth.codeVerifierCache
                .saveCodeVerifier(verifier)
        },
        buildAuthorizeUrl = { provider, redirectUrl, challenge ->
            SupabaseConfig.client.auth.getOAuthUrl(OAuthSignInFlow.oauthProviderOf(provider), redirectUrl) {
                queryParams["code_challenge"] = challenge
                queryParams["code_challenge_method"] = "s256"
            }
        },
        exchangeCode = { code -> SupabaseConfig.client.auth.exchangeCodeForSession(code) },
        openBrowser = { provider, url -> openSignInPage(provider, url) },
    )
}

/**
 * The command that opens [provider]'s sign-in page in a specific browser, or null to use the
 * default browser.
 *
 * Apple's page opens in Safari on macOS whatever the default browser is: only Safari can offer
 * the Mac's own Apple Account with Touch ID there, which is the closest a Developer ID app can
 * get to the native Sign in with Apple sheet (Apple limits that sheet to Mac App Store apps).
 * Google stays in the default browser, where the user is usually already signed in to Google.
 */
internal fun preferredBrowserCommand(
    provider: OAuthProviderKind,
    osName: String,
    url: String,
): List<String>? =
    if (provider == OAuthProviderKind.APPLE && osName.lowercase().contains("mac")) {
        listOf("open", "-a", "Safari", url)
    } else {
        null
    }

/** Open [provider]'s sign-in [url], in its preferred browser when it has one; false when nothing opened. */
private suspend fun openSignInPage(
    provider: OAuthProviderKind,
    url: String,
): Boolean {
    val preferred = preferredBrowserCommand(provider, System.getProperty("os.name").orEmpty(), url)
    if (preferred != null && runAndWait(preferred)) return true
    return openInSystemBrowser(url)
}

/** Whether [command] exited 0 within a few seconds; `open -a` exits non-zero when the app is missing. */
private suspend fun runAndWait(command: List<String>): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            process.waitFor(OPEN_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0
        }.getOrDefault(false)
    }

private const val OPEN_TIMEOUT_SECONDS = 10L

/** Open [url] in the user's default browser; false when no method worked. */
private suspend fun openInSystemBrowser(url: String): Boolean =
    withContext(Dispatchers.IO) {
        val desktopOpened =
            runCatching {
                java.awt.Desktop.isDesktopSupported() &&
                    java.awt.Desktop
                        .getDesktop()
                        .isSupported(java.awt.Desktop.Action.BROWSE) &&
                    run {
                        java.awt.Desktop
                            .getDesktop()
                            .browse(java.net.URI.create(url))
                        true
                    }
            }.getOrDefault(false)
        desktopOpened || openWithPlatformCommand(url)
    }

private fun openWithPlatformCommand(url: String): Boolean {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val command =
        when {
            os.contains("mac") -> listOf("open", url)
            os.contains("win") -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
            else -> listOf("xdg-open", url)
        }
    return runCatching {
        ProcessBuilder(command).redirectErrorStream(true).start()
        true
    }.getOrDefault(false)
}
