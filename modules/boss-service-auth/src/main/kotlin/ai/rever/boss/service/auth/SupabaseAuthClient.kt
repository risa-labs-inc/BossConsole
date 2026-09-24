package ai.rever.boss.service.auth

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Wraps the Supabase Kotlin client to provide auth operations for AuthServiceGrpcImpl.
 *
 * Reads SUPABASE_URL and SUPABASE_ANON_KEY from environment variables at construction time.
 * Uses CIO as the Ktor HTTP engine (added to boss-service-auth dependencies).
 */
class SupabaseAuthClient(
    supabaseUrl: String = System.getenv("SUPABASE_URL") ?: "https://api.risaboss.com",
    supabaseAnonKey: String = System.getenv("SUPABASE_ANON_KEY") ?: "",
) {
    private val logger = LoggerFactory.getLogger(SupabaseAuthClient::class.java)

    private val client =
        createSupabaseClient(
            supabaseUrl = supabaseUrl,
            supabaseKey = supabaseAnonKey,
        ) {
            install(Auth)
        }

    /** Signs in with email + password. Returns [AuthResult.Success] on success. */
    suspend fun signInWithEmailPassword(
        email: String,
        password: String,
    ): AuthResult =
        try {
            client.auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
            buildSuccessResult(fallbackEmail = email)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Email/password sign-in failed for {}", maskEmail(email), e)
            // Same dump, same screen: this used to hand `e.message` - error code, request URL and
            // the whole header list - straight back over gRPC as `errorMessage`.
            AuthResult.Failure(
                describeAuthFailure(e, transient = TRANSIENT_SIGN_IN_MESSAGE, fallback = "Sign-in failed"),
            )
        }

    /**
     * Sends a magic link to the given email.
     * Returns [AuthResult.MagicLinkSent] immediately — the actual session arrives
     * when the user clicks the link and the deep-link callback updates state.
     *
     * Transient failures are retried, and the failure message is prose rather than
     * supabase-kt's raw dump - see [SEND_RETRY_BACKOFF] and [describeSendFailure].
     */
    suspend fun sendMagicLink(email: String): AuthResult =
        sendMagicLink(email, send = { address -> client.auth.signInWith(OTP) { this.email = address } })

    /**
     * The retry loop, with the send passed in so it can be driven in a test without a live
     * Supabase client. Production calls go through [sendMagicLink].
     */
    internal suspend fun sendMagicLink(
        email: String,
        timeSource: TimeSource = TimeSource.Monotonic,
        send: suspend (String) -> Unit,
    ): AuthResult {
        val startedAt = timeSource.markNow()
        var retries = 0
        while (true) {
            try {
                send(email)
                return AuthResult.MagicLinkSent(email)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val elapsed = startedAt.elapsedNow()
                val backoff =
                    SEND_RETRY_BACKOFF
                        .getOrNull(retries)
                        ?.takeIf { isTransientSendFailure(e) && elapsed < SEND_RETRY_BUDGET }
                logger.error(
                    "Magic link send failed for {} (attempt {}, {}ms elapsed, retrying: {})",
                    maskEmail(email),
                    retries + 1,
                    elapsed.inWholeMilliseconds,
                    backoff != null,
                    e,
                )
                if (backoff == null) return AuthResult.Failure(describeSendFailure(e))
                delay(backoff)
                retries++
            }
        }
    }

    /**
     * Whether [error] is worth another attempt.
     *
     * Supabase hands the message to SMTP inside the `/otp` request and does not retry that hop, so
     * a mail-provider hiccup comes back as a 500 (2026-08-24: Gmail answered
     * `451 4.3.0 Mail server temporarily rejected message`, and the identical request minutes later
     * went through). A 4xx is a settled answer and asking again would only spend more of the
     * sender's hourly email budget.
     */
    internal fun isTransientSendFailure(error: Throwable) = error is RestException && error.statusCode in SERVER_ERRORS

    /**
     * One sentence about a failed send, for whoever is looking at the sign-in screen.
     *
     * supabase-kt builds [RestException.message] out of the error code, the request URL and the
     * whole request header list. That dump travels over gRPC as `errorMessage` and lands on screen,
     * so keep it in the log and hand the caller prose.
     */
    internal fun describeSendFailure(error: Throwable): String =
        describeAuthFailure(error, transient = TRANSIENT_SEND_MESSAGE, fallback = "Magic link failed")

    /**
     * Shared mapping for both sign-in routes. [transient] is what a 5xx becomes, which differs by
     * route: a failed magic-link send is about the email, a failed password sign-in is not.
     */
    internal fun describeAuthFailure(
        error: Throwable,
        transient: String,
        fallback: String,
    ): String {
        if (isTransientSendFailure(error)) return transient
        return when ((error as? AuthRestException)?.errorCode) {
            AuthErrorCode.InvalidCredentials -> {
                "That email and password don't match."
            }

            AuthErrorCode.UserNotFound -> {
                "No account found with this email address"
            }

            AuthErrorCode.EmailAddressInvalid -> {
                "That email address doesn't look valid. Please check it and try again."
            }

            AuthErrorCode.EmailAddressNotAuthorized -> {
                "This email address isn't allowed to sign in."
            }

            AuthErrorCode.UserBanned -> {
                "This account has been suspended. Please contact support."
            }

            AuthErrorCode.SignupDisabled, AuthErrorCode.EmailProviderDisabled, AuthErrorCode.OtpDisabled -> {
                "Email sign-in is currently unavailable. Please try again later."
            }

            // `rate_limit_email_sent` is an hourly budget, so "a few minutes" is wrong advice.
            AuthErrorCode.OverEmailSendRateLimit -> {
                "Too many sign-in emails have been sent recently. Please try again in an hour."
            }

            AuthErrorCode.OverRequestRateLimit -> {
                "Too many attempts. Please wait a few minutes before trying again."
            }

            // The server's own description ("Error sending magic link email") reads fine alone.
            else -> {
                (error as? AuthRestException)?.errorDescription?.takeIf { it.isNotBlank() } ?: fallback
            }
        }
    }

    /** Signs out and clears the local session. Errors are logged but not re-thrown. */
    suspend fun signOut() {
        try {
            client.auth.signOut()
        } catch (e: Exception) {
            logger.warn("Sign-out error (clearing local state anyway): {}", e.message)
        }
    }

    /**
     * Attempts to restore a previously stored session (e.g. from disk or env).
     * Call this at service startup before accepting requests.
     */
    suspend fun restoreSession(): AuthResult =
        try {
            client.auth.awaitInitialization()
            buildSuccessResult(fallbackEmail = null)
        } catch (e: Exception) {
            logger.debug("No session to restore: {}", e.message)
            AuthResult.Failure("No session")
        }

    private fun buildSuccessResult(fallbackEmail: String?): AuthResult {
        val session =
            client.auth.currentSessionOrNull()
                ?: return AuthResult.Failure("No session available after auth operation")
        val user = session.user
        return successFrom(
            accessToken = session.accessToken,
            userId = user?.id ?: "",
            email = user?.email ?: fallbackEmail ?: "",
            displayName =
                user
                    ?.userMetadata
                    ?.get("full_name")
                    ?.jsonPrimitive
                    ?.contentOrNull ?: "",
        )
    }

    private fun maskEmail(email: String): String = if (email.length > 3) "${email.take(3)}***" else "***"

    private companion object {
        /**
         * How long to wait between magic-link send attempts, one entry per retry - so one retry,
         * two attempts in all. The wait is at least 2s on purpose: it clears Supabase's
         * `smtp_max_frequency` (1s), which keeps a retry from turning a transient failure into a
         * rate-limit failure. The ceiling stays low because every attempt asks for another email
         * and so spends the project's `rate_limit_email_sent` budget, a number that lives in the
         * Supabase dashboard rather than in this repo.
         */
        private val SEND_RETRY_BACKOFF = listOf(2.seconds)

        /**
         * Stop retrying once this much has elapsed since the first attempt. The failure worth
         * retrying is fast; one that burns the full request timeout has already outlasted anyone's
         * patience, and a second attempt would only double the wait.
         */
        private val SEND_RETRY_BUDGET = 15.seconds

        private val SERVER_ERRORS = 500..599

        private const val TRANSIENT_SEND_MESSAGE = "We couldn't send the email just now. Please try again in a minute."

        private const val TRANSIENT_SIGN_IN_MESSAGE =
            "We couldn't reach the sign-in service just now. Please try again in a minute."
    }
}

sealed class AuthResult {
    data class Success(
        val userId: String,
        val email: String,
        val displayName: String,
        val isAdmin: Boolean,
        val permissions: Set<String>,
        val sessionToken: String,
        val sessionCreatedAt: Long,
    ) : AuthResult()

    data class MagicLinkSent(
        val email: String,
    ) : AuthResult()

    data class Failure(
        val message: String,
    ) : AuthResult()
}

/**
 * The [AuthResult.Success] for a session holding [accessToken]. Admin status and permissions come
 * from the token's claims and nowhere else - there is deliberately no parameter through which
 * `user_metadata` could reach them. Split out of `SupabaseAuthClient.buildSuccessResult`, which
 * needs a live session, so the mapping can be asserted with a crafted token. File scope because
 * the class is at detekt's `TooManyFunctions` ceiling.
 */
internal fun successFrom(
    accessToken: String,
    userId: String,
    email: String,
    displayName: String,
): AuthResult.Success {
    val claims =
        accessTokenClaims(accessToken) ?: run {
            LoggerFactory
                .getLogger(SupabaseAuthClient::class.java)
                .warn("Could not read role claims from the access token; granting no admin status or permissions")
            AccessTokenClaims.NONE
        }
    return AuthResult.Success(
        userId = userId,
        email = email,
        displayName = displayName,
        isAdmin = claims.isAdmin,
        permissions = claims.permissions,
        sessionToken = accessToken,
        sessionCreatedAt = System.currentTimeMillis() / 1000,
    )
}
