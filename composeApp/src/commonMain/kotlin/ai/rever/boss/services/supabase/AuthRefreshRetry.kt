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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/*
 * A minimal copy of this plugin lives in modules/boss-service-auth (AuthRefreshRetry.kt there),
 * which cannot depend on composeApp. Keep the two in step.
 */

/**
 * Thrown in place of a refresh-token response whose status is transient (408, 429, any 5xx), and
 * for refreshes refused locally during the cooldown.
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
    /**
     * Attempts per refresh call, the first one included. Kept at 2 so one supabase-kt retry tick
     * costs at most two requests: GoTrue rate-limits token refresh per IP and the fleet shares NAT IPs.
     */
    var maxAttempts: Int = 2

    /** In-call backoff before the second attempt; doubles per attempt up to [maxBackoff], with jitter. */
    var baseBackoff: Duration = 500.milliseconds
    var maxBackoff: Duration = 4.seconds

    /**
     * Longest wait this plugin will sit through inside one request. supabase-kt's request timeout
     * (10s by default) covers the whole call, so a longer Retry-After is not slept through here:
     * the call fails fast and the cooldown refuses later refreshes until it passes.
     */
    var maxInlineWait: Duration = 4.seconds

    /** Cooldown after the first failed refresh call; doubles per consecutive failure up to [maxCooldown]. */
    var baseCooldown: Duration = 15.seconds
    var maxCooldown: Duration = 5.minutes

    /** Upper bound on a Retry-After we will honour, so a bogus header cannot park refresh for hours. */
    var maxRetryAfter: Duration = 5.minutes

    var nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() }
    var random: Random = Random.Default

    /** Full jitter: a uniformly random wait in `0..ceiling` ms. Replaceable so tests are deterministic. */
    var jitter: (ceilingMs: Long) -> Long = { random.nextLong(0, it + 1) }
    var sleep: suspend (Duration) -> Unit = { delay(it) }
}

/**
 * Cross-call refresh cooldown. After a refresh call ends in a transient failure, later refresh calls
 * are refused locally, nothing sent, until the cooldown passes: [AuthRefreshRetryConfig.baseCooldown]
 * doubled per consecutive failure up to [AuthRefreshRetryConfig.maxCooldown], with full jitter, or a
 * longer Retry-After. Any non-transient answer resets it.
 *
 * Without it every pod retried on supabase-kt's fixed 10s `retryDelay`, so an outage turned into a
 * refresh storm against GoTrue's per-IP token-refresh limit, which is what produced the 429s.
 */
internal class RefreshCooldown(
    private val config: AuthRefreshRetryConfig,
) {
    private var consecutiveFailures = 0
    private var notBeforeMs = 0L

    @Synchronized
    fun remainingMs(): Long = (notBeforeMs - config.nowMillis()).coerceAtLeast(0)

    /** Records a failed refresh call and returns the cooldown now in force. */
    @Synchronized
    fun onTransientFailure(retryAfter: Duration?): Duration {
        consecutiveFailures++
        val doublings = (consecutiveFailures - 1).coerceAtMost(MAX_DOUBLINGS)
        val ceiling = (config.baseCooldown * (1 shl doublings)).coerceAtMost(config.maxCooldown)
        val jittered = config.jitter(ceiling.inWholeMilliseconds).milliseconds
        val cooldown = maxOf(jittered, retryAfter ?: Duration.ZERO)
        notBeforeMs = maxOf(notBeforeMs, config.nowMillis() + cooldown.inWholeMilliseconds)
        return cooldown
    }

    @Synchronized
    fun onAnswer() {
        consecutiveFailures = 0
        notBeforeMs = 0L
    }

    private companion object {
        const val MAX_DOUBLINGS = 16
    }
}

private val logger = BossLogger.forComponent("AuthRefreshRetry")

/**
 * Ktor client plugin for the Supabase HttpClient, acting only on
 * `POST /auth/v1/token?grant_type=refresh_token`:
 * - during the [RefreshCooldown], refuses the refresh locally with [TransientAuthRefreshException];
 * - otherwise makes up to [AuthRefreshRetryConfig.maxAttempts] attempts, retrying 408 / 429 / 5xx /
 *   connection errors and timeouts after a short jittered backoff (or a short Retry-After);
 * - if the call still fails transiently, starts or extends the cooldown and throws
 *   [TransientAuthRefreshException] (or the original network error), so supabase-kt keeps the session.
 *
 * Real rejections (400 invalid_grant, refresh_token_not_found, 401, 403, ...) pass through untouched
 * and still clear the session, as they should. Every other request is passed straight through.
 */
internal val AuthRefreshRetry =
    createClientPlugin("BossAuthRefreshRetry", ::AuthRefreshRetryConfig) {
        val config = pluginConfig
        val cooldown = RefreshCooldown(config)

        on(Send) { request ->
            if (!request.isRefreshTokenGrant()) return@on proceed(request)

            val remaining = cooldown.remainingMs()
            if (remaining > 0) {
                throw TransientAuthRefreshException(
                    statusCode = null,
                    message = "Supabase token refresh deferred for ${remaining}ms (cooldown); session kept",
                )
            }

            var attempt = 1
            while (true) {
                var call: HttpClientCall? = null
                var failure: Exception? = null
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
                if (call != null && !SessionRecoveryPolicy.isTransientStatus(status!!)) {
                    cooldown.onAnswer()
                    return@on call
                }

                val retryAfter =
                    call
                        ?.response
                        ?.headers
                        ?.get(HttpHeaders.RetryAfter)
                        ?.let { parseRetryAfter(it, config) }
                val wait = maxOf(retryAfter ?: Duration.ZERO, backoff(config, attempt))
                val reason = status?.let { "HTTP $it" } ?: failure!!::class.simpleName
                // The outer request was cancelled (supabase-kt's request timeout), or we are out of
                // attempts, or the server wants a longer pause than one request can hold: give up.
                val outOfRoom = !request.executionContext.isActive || wait > config.maxInlineWait
                if (attempt >= config.maxAttempts || outOfRoom) {
                    val applied = cooldown.onTransientFailure(retryAfter)
                    logger.warn(
                        LogCategory.NETWORK,
                        "Supabase token refresh failed transiently; keeping session, cooling down",
                        mapOf("reason" to reason, "attempts" to attempt, "cooldown" to applied.toString()),
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

/** In-call backoff with equal jitter: half the step fixed, half random. */
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
