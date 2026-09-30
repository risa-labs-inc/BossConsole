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
import io.github.jan.supabase.auth.status.SessionSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
    /** The provider this state is about, or null when no sign-in is in progress. */
    val provider: OAuthProviderKind?

    data object Idle : OAuthSignInState {
        override val provider: OAuthProviderKind? get() = null
    }

    /**
     * The sign-in page is open in the system browser; waiting for `boss://auth/callback`.
     * [notice] explains a callback that arrived and did not sign in; the sign-in stays open, so a
     * link opened by some other page cannot end it.
     */
    data class WaitingForBrowser(
        override val provider: OAuthProviderKind,
        val authorizeUrl: String,
        val notice: String? = null,
    ) : OAuthSignInState

    /** The callback arrived and the code is being exchanged for a session. */
    data class Exchanging(
        override val provider: OAuthProviderKind,
    ) : OAuthSignInState

    data class Error(
        override val provider: OAuthProviderKind?,
        val message: String,
    ) : OAuthSignInState
}

/** What [OAuthSignInFlow.complete] did with one callback. */
enum class OAuthCompletion {
    /** A session was established; the auth state collector takes it from here. */
    SIGNED_IN,

    /**
     * The callback reported a failure, the exchange failed, or the sign-in had expired;
     * [OAuthSignInFlow.state] says why. Only expiry ends the sign-in.
     */
    FAILED,

    /**
     * Nothing was done: no sign-in was waiting, one callback was already being exchanged, or the
     * sign-in was cancelled or replaced while this one was exchanged.
     */
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
 * is useless without the verifier, which never leaves this machine. Because anyone can send
 * one, a callback that fails (a provider error, a code that does not exchange) does not end the
 * sign-in; it shows a notice and the user's own callback can still arrive. Only a successful
 * exchange, [cancel], a new [start] or expiry ends it.
 *
 * An exchange runs outside the lock, so [cancel] never waits for the network. [exchangeCode]
 * therefore returns the session un-adopted, and it is adopted only if its attempt is still the
 * one waiting when the exchange returns: a sign-in cancelled mid-exchange stays signed out.
 * Codes, verifiers and tokens are never logged.
 */
// One state machine: each private step runs under the one mutex and shares its fields, so
// splitting it would only move that state behind another object's API.
@Suppress("TooManyFunctions")
internal class OAuthSignInFlow(
    private val prepareVerifier: suspend (verifier: String) -> Unit,
    private val buildAuthorizeUrl: (provider: OAuthProviderKind, redirectUrl: String, codeChallenge: String) -> String,
    /** Exchange a code for a session without adopting it; the returned action adopts it. */
    private val exchangeCode: suspend (code: String) -> suspend () -> Unit,
    private val openBrowser: suspend (provider: OAuthProviderKind, url: String) -> Boolean,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val attemptTimeout: Duration = ATTEMPT_TIMEOUT,
) {
    private val logger = BossLogger.forComponent("OAuthSignInService")

    private val _state = MutableStateFlow<OAuthSignInState>(OAuthSignInState.Idle)
    val state: StateFlow<OAuthSignInState> = _state.asStateFlow()

    private val _starting = MutableStateFlow<OAuthProviderKind?>(null)

    /** The provider whose sign-in is being prepared, so the buttons can show it and refuse a second press. */
    val starting: StateFlow<OAuthProviderKind?> = _starting.asStateFlow()

    private val mutex = Mutex()

    /** The waiting sign-in. Replaced by identity on every start, so a stale reference is detectable. */
    private var pending: PendingAttempt? = null

    private class PendingAttempt(
        val provider: OAuthProviderKind,
        val authorizeUrl: String,
        val startedAt: TimeMark,
    ) {
        /** A callback's code is being exchanged; further callbacks are ignored until it settles. */
        var exchanging = false
    }

    /**
     * Open [provider]'s sign-in page in the system browser. A sign-in already waiting is
     * replaced: its verifier is overwritten, so its callback can no longer be exchanged. A press
     * while another start is still being prepared is ignored.
     */
    suspend fun start(provider: OAuthProviderKind): Result<Unit> {
        if (!_starting.compareAndSet(null, provider)) return Result.success(Unit)
        return try {
            startPrepared(provider)
        } finally {
            _starting.value = null
        }
    }

    private suspend fun startPrepared(provider: OAuthProviderKind): Result<Unit> {
        val url =
            prepare(provider) ?: return Result.failure(
                IllegalStateException("Couldn't start ${provider.displayName} sign-in"),
            )
        logger.info(LogCategory.AUTH, "OAuth sign-in started", mapOf("provider" to provider.name))
        if (!openBrowser(provider, url)) {
            // Stay in WaitingForBrowser: the waiting screen offers to copy the link instead.
            logger.warn(LogCategory.AUTH, "Could not open the system browser for OAuth sign-in")
        }
        return Result.success(Unit)
    }

    /** Store a fresh verifier and record the attempt; the authorize URL, or null after showing why not. */
    // The cache write and URL build fail in unrelated ways; any of them fails the start.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun prepare(provider: OAuthProviderKind): String? =
        try {
            mutex.withLock {
                val verifier = generateCodeVerifier()
                prepareVerifier(verifier)
                val url = buildAuthorizeUrl(provider, REDIRECT_URL, codeChallengeOf(verifier))
                pending = PendingAttempt(provider, url, timeSource.markNow())
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
            _state.value =
                OAuthSignInState.Error(provider, "Couldn't start ${provider.displayName} sign-in. Please try again.")
            null
        }

    /** Reopen the waiting sign-in's page, for a browser tab the user closed. Clears a shown notice. */
    suspend fun reopenBrowser(): Boolean {
        val waiting = _state.value as? OAuthSignInState.WaitingForBrowser ?: return false
        _state.update { if (it == waiting) waiting.copy(notice = null) else it }
        return openBrowser(waiting.provider, waiting.authorizeUrl)
    }

    /** Drop the waiting sign-in; its callback is ignored if it still arrives, even mid-exchange. */
    suspend fun cancel() {
        mutex.withLock {
            if (pending != null) logger.info(LogCategory.AUTH, "OAuth sign-in cancelled")
            pending = null
            _state.value = OAuthSignInState.Idle
        }
    }

    /**
     * End a waiting sign-in that has outlived [attemptTimeout], so the waiting screen does not
     * wait for ever. Called periodically by that screen; true when it expired one.
     */
    suspend fun expireIfStale(): Boolean =
        mutex.withLock {
            val attempt = pending
            val stale = attempt != null && !attempt.exchanging && attempt.startedAt.elapsedNow() > attemptTimeout
            if (stale) expire(checkNotNull(attempt))
            stale
        }

    /** Clear a shown error, back to the provider buttons. */
    fun dismissError() {
        _state.update { if (it is OAuthSignInState.Error) OAuthSignInState.Idle else it }
    }

    /**
     * Finish the waiting sign-in with [callback]. One callback is exchanged at a time, so a
     * duplicate delivery (one per open window, or a second OS hand-off) is ignored while it runs.
     */
    suspend fun complete(callback: AuthDeepLink.OAuthCallback): OAuthCompletion =
        when (val claim = mutex.withLock { claim(callback) }) {
            is Claim.Done -> claim.outcome
            is Claim.Exchange -> exchange(claim.attempt, claim.code)
        }

    /** What one callback does to the waiting attempt. Runs under [mutex]; only expiry ends it. */
    private fun claim(callback: AuthDeepLink.OAuthCallback): Claim {
        val attempt = pending
        val code = callback.code
        return when {
            attempt == null -> {
                logIgnored("no sign-in is waiting")
                Claim.Done(OAuthCompletion.IGNORED)
            }

            attempt.exchanging -> {
                logIgnored("a callback for this sign-in is already being exchanged")
                Claim.Done(OAuthCompletion.IGNORED)
            }

            attempt.startedAt.elapsedNow() > attemptTimeout -> {
                logIgnored("the sign-in expired")
                expire(attempt)
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
                showWaiting(attempt, describeCallbackError(attempt.provider, callback.error))
                Claim.Done(OAuthCompletion.FAILED)
            }

            // AuthDeepLinks guarantees exactly one of code and error; a hand-built callback may not.
            code == null -> {
                Claim.Done(OAuthCompletion.IGNORED)
            }

            else -> {
                attempt.exchanging = true
                _state.value = OAuthSignInState.Exchanging(attempt.provider)
                Claim.Exchange(attempt, code)
            }
        }
    }

    // supabase-kt surfaces RestException, HttpRequestException and IO failures alike.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun exchange(
        attempt: PendingAttempt,
        code: String,
    ): OAuthCompletion {
        val adoptSession =
            try {
                exchangeCode(code)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { mutex.withLock { settle(attempt, notice = null) } }
                throw e
            } catch (e: Exception) {
                logger.warn(
                    LogCategory.AUTH,
                    "OAuth code exchange failed",
                    mapOf("provider" to attempt.provider.name),
                    error = e,
                )
                return mutex.withLock {
                    settle(attempt, describeExchangeFailure(attempt.provider, e))
                    OAuthCompletion.FAILED
                }
            }
        return mutex.withLock { adopt(attempt, adoptSession) }
    }

    /** Adopt an exchanged session if [attempt] is still the waiting sign-in. Runs under [mutex]. */
    // importSession surfaces storage and serialization failures alike.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun adopt(
        attempt: PendingAttempt,
        adoptSession: suspend () -> Unit,
    ): OAuthCompletion {
        attempt.exchanging = false
        if (pending !== attempt) {
            logger.info(LogCategory.AUTH, "OAuth session discarded: that sign-in was cancelled or replaced")
            return OAuthCompletion.IGNORED
        }
        // The code is spent and the verifier deleted, so this attempt ends here either way.
        pending = null
        return try {
            adoptSession()
            logger.info(LogCategory.AUTH, "OAuth sign-in completed", mapOf("provider" to attempt.provider.name))
            _state.value = OAuthSignInState.Idle
            OAuthCompletion.SIGNED_IN
        } catch (e: CancellationException) {
            _state.value = OAuthSignInState.Idle
            throw e
        } catch (e: Exception) {
            logger.warn(
                LogCategory.AUTH,
                "Could not save the OAuth session",
                mapOf("provider" to attempt.provider.name),
                error = e,
            )
            val message = "${attempt.provider.displayName} sign-in failed. Please try again."
            _state.value = OAuthSignInState.Error(attempt.provider, message)
            OAuthCompletion.FAILED
        }
    }

    /** An exchange for [attempt] ended without a session: back to waiting, if it still is. Runs under [mutex]. */
    private fun settle(
        attempt: PendingAttempt,
        notice: String?,
    ) {
        attempt.exchanging = false
        if (pending === attempt) showWaiting(attempt, notice)
    }

    private fun showWaiting(
        attempt: PendingAttempt,
        notice: String?,
    ) {
        _state.value = OAuthSignInState.WaitingForBrowser(attempt.provider, attempt.authorizeUrl, notice)
    }

    /** End [attempt] as too old. Runs under [mutex]. */
    private fun expire(attempt: PendingAttempt) {
        pending = null
        val message = "That ${attempt.provider.displayName} sign-in took too long. Please try again."
        _state.value = OAuthSignInState.Error(attempt.provider, message)
    }

    private sealed interface Claim {
        data class Done(
            val outcome: OAuthCompletion,
        ) : Claim

        class Exchange(
            val attempt: PendingAttempt,
            val code: String,
        ) : Claim
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
        exchangeCode = { code ->
            val auth = SupabaseConfig.client.auth
            val session = auth.exchangeCodeForSession(code, saveSession = false)
            val adopt: suspend () -> Unit = { auth.importSession(session, source = SessionSource.External) }
            adopt
        },
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
private suspend fun runAndWait(command: List<String>): Boolean = withContext(Dispatchers.IO) { exitsCleanly(command) }

/** [runAndWait] for a caller already on an IO thread. A command still running at the deadline is killed. */
private fun exitsCleanly(command: List<String>): Boolean =
    runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        // Nothing reads the output; closing our end keeps a chatty opener from blocking on a full pipe.
        process.inputStream.close()
        val exited = process.waitFor(OPEN_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
        if (!exited) process.destroyForcibly()
        exited && process.exitValue() == 0
    }.getOrDefault(false)

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

/** The platform opener; true only when it exited 0, so a missing `xdg-open` reaches the copy-link fallback. */
private fun openWithPlatformCommand(url: String): Boolean {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val command =
        when {
            os.contains("mac") -> listOf("open", url)
            os.contains("win") -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
            else -> listOf("xdg-open", url)
        }
    return exitsCleanly(command)
}
