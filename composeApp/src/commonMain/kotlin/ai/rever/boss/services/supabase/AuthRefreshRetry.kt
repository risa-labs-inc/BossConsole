package ai.rever.boss.services.supabase

import ai.rever.boss.services.auth.SessionRecoveryPolicy
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import io.ktor.client.call.HttpClientCall
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.takeFrom
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.encodedPath
import io.ktor.http.fromHttpToGmtDate
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Thrown in place of a refresh-token response whose status is transient (408, 429, any 5xx).
 *
 * supabase-kt 3.8.0's scheduled refresh (`AuthImpl.tryImportingSession`) clears the session on
 * every `RestException` whose status is not in its private `NETWORK_ERROR_CODES` (500, 502-504,
 * 520-524, 530), so a 429 or a Cloudflare 525 signed users out. Any other exception it treats as a
 * network error: it keeps the session and retries after `AuthConfig.retryDelay`. Throwing this
 * (an [java.io.IOException], which `KtorSupabaseHttpClient` rewraps as `HttpRequestException`)
 * before the library ever builds a `RestException` routes transient failures down that path.
 *
 * The message carries the status only: never the request body, which holds the refresh token.
 */
internal class TransientAuthRefreshException(
    val statusCode: Int?,
    message: String,
) : java.io.IOException(message)

internal class AuthRefreshRetryConfig {
    /** Attempts per refresh call, the first one included. */
    var maxAttempts: Int = 3

    /** First backoff; doubles per attempt up to [maxBackoff], with jitter. */
    var baseBackoff: Duration = 500.milliseconds
    var maxBackoff: Duration = 4.seconds

    /**
     * Longest wait this plugin will sit through inside one request. supabase-kt's request timeout
     * (10s by default) covers the whole retry sequence, so a longer Retry-After is not slept through
     * here: the call fails fast as transient and later refreshes are refused locally until it passes.
     */
    var maxInlineWait: Duration = 4.seconds

    /** Upper bound on a Retry-After we will honour, so a bogus header cannot park refresh for hours. */
    var maxRetryAfter: Duration = 5.minutes

    var nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() }
    var random: Random = Random.Default
    var sleep: suspend (Duration) -> Unit = { delay(it) }
}

private val logger = BossLogger.forComponent("AuthRefreshRetry")

/**
 * Ktor client plugin for the Supabase HttpClient: retries `POST /auth/v1/token?grant_type=refresh_token`
 * with jittered exponential backoff on 408 / 429 / 5xx / connection errors and timeouts, honours
 * Retry-After, and if the refresh still fails transiently throws [TransientAuthRefreshException]
 * so supabase-kt keeps the session. Real rejections (400 invalid_grant, refresh_token_not_found,
 * 401, 403, ...) pass through untouched and still clear the session, as they should.
 * Every other request is passed straight through.
 */
internal val AuthRefreshRetry =
    createClientPlugin("BossAuthRefreshRetry", ::AuthRefreshRetryConfig) {
        val config = pluginConfig
        // Epoch millis before which the server asked us (Retry-After) not to refresh again.
        val notBefore = AtomicLong(0L)

        on(Send) { request ->
            if (!request.isRefreshTokenGrant()) return@on proceed(request)

            val gatedFor = notBefore.get() - config.nowMillis()
            if (gatedFor > 0) {
                throw TransientAuthRefreshException(
                    statusCode = null,
                    message = "Supabase token refresh deferred for ${gatedFor}ms (honouring Retry-After); session kept",
                )
            }

            var attempt = 1
            while (true) {
                var call: HttpClientCall? = null
                var failure: Throwable? = null
                try {
                    call = proceed(request.copyForAttempt())
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    // Whatever the engine throws for a failed attempt (IOExceptions, but also e.g.
                    // UnresolvedAddressException, an IllegalArgumentException) is a network-level
                    // failure worth another attempt; the last one is rethrown unchanged below.
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    failure = e
                }

                val status = call?.response?.status?.value
                if (call != null && !SessionRecoveryPolicy.isTransientStatus(status!!)) return@on call
                // The outer request was cancelled (supabase-kt's request timeout): stop here.
                if (!request.executionContext.isActive) {
                    throw failure ?: TransientAuthRefreshException(status, "cancelled")
                }

                val retryAfter =
                    call
                        ?.response
                        ?.headers
                        ?.get(HttpHeaders.RetryAfter)
                        ?.let { parseRetryAfter(it, config) }
                if (retryAfter != null && retryAfter.isPositive()) {
                    val until = config.nowMillis() + retryAfter.inWholeMilliseconds
                    notBefore.updateAndGet { maxOf(it, until) }
                }
                val wait = maxOf(retryAfter ?: Duration.ZERO, backoff(config, attempt))
                val reason = status?.let { "HTTP $it" } ?: failure!!::class.simpleName
                if (attempt >= config.maxAttempts || wait > config.maxInlineWait) {
                    logger.warn(
                        LogCategory.NETWORK,
                        "Supabase token refresh failed transiently; keeping session, supabase-kt will retry",
                        mapOf("reason" to reason, "attempts" to attempt, "retryAfter" to retryAfter?.toString()),
                    )
                    // Non-HTTP failures (timeouts, connect errors) already take supabase-kt's retry path.
                    throw failure ?: TransientAuthRefreshException(
                        statusCode = status,
                        message =
                            "Supabase token refresh got transient HTTP $status " +
                                "after $attempt attempt(s); session kept",
                    )
                }
                logger.info(
                    LogCategory.NETWORK,
                    "Supabase token refresh failed transiently; retrying",
                    mapOf("reason" to reason, "attempt" to attempt, "wait" to wait.toString()),
                )
                config.sleep(wait)
                attempt++
            }
            error("unreachable")
        }
    }

private fun HttpRequestBuilder.isRefreshTokenGrant(): Boolean =
    method == HttpMethod.Post &&
        url.encodedPath.endsWith("/auth/v1/token") &&
        url.parameters["grant_type"] == "refresh_token"

/**
 * A fresh builder per attempt, as Ktor's own HttpRequestRetry does: an attempt's execution job
 * must not be the one a previous attempt already completed.
 */
private fun HttpRequestBuilder.copyForAttempt(): HttpRequestBuilder {
    val sub = HttpRequestBuilder().takeFrom(this)
    executionContext.invokeOnCompletion { cause ->
        val job = sub.executionContext as CompletableJob
        if (cause == null) job.complete() else job.completeExceptionally(cause)
    }
    return sub
}

/** Exponential backoff with equal jitter: half the step fixed, half random. */
private fun backoff(
    config: AuthRefreshRetryConfig,
    attempt: Int,
): Duration {
    val step = (config.baseBackoff * (1 shl (attempt - 1).coerceAtMost(16))).coerceAtMost(config.maxBackoff)
    val half = step.inWholeMilliseconds / 2
    return (half + config.random.nextLong(0, half + 1)).milliseconds
}

/** Retry-After is either delta-seconds or an HTTP-date (RFC 9110 10.2.3); anything else is ignored. */
internal fun parseRetryAfter(
    raw: String,
    config: AuthRefreshRetryConfig,
): Duration? {
    val value = raw.trim()
    val parsed =
        value.toLongOrNull()?.seconds
            ?: runCatching { (value.fromHttpToGmtDate().timestamp - config.nowMillis()).milliseconds }.getOrNull()
    return parsed?.coerceIn(Duration.ZERO, config.maxRetryAfter)
}
