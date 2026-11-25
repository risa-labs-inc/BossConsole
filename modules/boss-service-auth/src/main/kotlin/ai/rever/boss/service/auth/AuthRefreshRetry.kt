package ai.rever.boss.service.auth

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
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/*
 * Minimal copy of composeApp's
 * composeApp/src/commonMain/kotlin/ai/rever/boss/services/supabase/AuthRefreshRetry.kt
 * (plus SessionRecoveryPolicy.isTransientStatus). composeApp cannot depend on this module and
 * `modules/` is excluded from the build on Windows-ARM64 (settings.gradle.kts), so there is no
 * shared home; see SupabaseAuthClientFailureTest for the same arrangement. KEEP IN LOCKSTEP:
 * the behaviour, defaults and tests of the two copies are meant to match.
 *
 * Why it exists: supabase-kt 3.8.0's scheduled refresh (AuthImpl.tryImportingSession) clears the
 * session on any RestException outside its private NETWORK_ERROR_CODES (500, 502-504, 520-524,
 * 530), so a 429 or Cloudflare 525 under load signed users out (2026-11-25). Any other exception
 * it treats as a network error: keeps the session and retries after AuthConfig.retryDelay.
 */

/** 408, 429 and every 5xx (Cloudflare 520-530 included) say nothing about the refresh token. */
internal fun isTransientRefreshStatus(statusCode: Int): Boolean {
    val timeoutOrRateLimit = statusCode == 408 || statusCode == 429
    return timeoutOrRateLimit || statusCode in 500..599
}

/** Thrown instead of a transient refresh response, or for a refresh refused during the cooldown. */
internal class TransientAuthRefreshException(
    val statusCode: Int?,
    message: String,
) : java.io.IOException(message)

internal class AuthRefreshRetryConfig {
    /** Attempts per refresh call, the first included: at most two requests per supabase-kt tick. */
    var maxAttempts: Int = 2
    var baseBackoff: Duration = 500.milliseconds
    var maxBackoff: Duration = 4.seconds

    /** Longest wait held inside one request (bounded by supabase-kt's 10s request timeout). */
    var maxInlineWait: Duration = 4.seconds

    /** Cross-call cooldown: doubles per consecutive failed call, full jitter, reset on any answer. */
    var baseCooldown: Duration = 15.seconds
    var maxCooldown: Duration = 5.minutes
    var maxRetryAfter: Duration = 5.minutes

    var nowMillis: () -> Long = { System.currentTimeMillis() }
    var random: Random = Random.Default
    var jitter: (ceilingMs: Long) -> Long = { random.nextLong(0, it + 1) }
    var sleep: suspend (Duration) -> Unit = { delay(it) }
}

/** Refuses refreshes locally after a transient failure; see composeApp's RefreshCooldown. */
internal class RefreshCooldown(
    private val config: AuthRefreshRetryConfig,
) {
    private var consecutiveFailures = 0
    private var notBeforeMs = 0L

    @Synchronized
    fun remainingMs(): Long = (notBeforeMs - config.nowMillis()).coerceAtLeast(0)

    @Synchronized
    fun onTransientFailure(retryAfter: Duration?): Duration {
        consecutiveFailures++
        val doublings = (consecutiveFailures - 1).coerceAtMost(MAX_DOUBLINGS)
        val ceiling = (config.baseCooldown * (1 shl doublings)).coerceAtMost(config.maxCooldown)
        val cooldown = maxOf(config.jitter(ceiling.inWholeMilliseconds).milliseconds, retryAfter ?: Duration.ZERO)
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

private val logger = LoggerFactory.getLogger("ai.rever.boss.service.auth.AuthRefreshRetry")

/**
 * Ktor plugin for `POST /auth/v1/token?grant_type=refresh_token` only: cooldown gate, up to
 * [AuthRefreshRetryConfig.maxAttempts] attempts on 408 / 429 / 5xx / network errors, then a
 * [TransientAuthRefreshException] so supabase-kt keeps the session. 400/401/403 pass through.
 * Never logs the request body, which holds the refresh token.
 */
internal val AuthRefreshRetry =
    createClientPlugin("BossAuthRefreshRetry", ::AuthRefreshRetryConfig) {
        val config = pluginConfig
        val cooldown = RefreshCooldown(config)

        on(Send) { request ->
            if (!request.isRefreshTokenGrant()) return@on proceed(request)

            val remaining = cooldown.remainingMs()
            if (remaining > 0) {
                val message = "Supabase token refresh deferred for ${remaining}ms (cooldown)"
                throw TransientAuthRefreshException(null, message)
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
                    // Engines throw non-IOException network failures too (UnresolvedAddressException);
                    // all are worth another attempt, and the last one is rethrown unchanged below.
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    failure = e
                }

                val status = call?.response?.status?.value
                if (call != null && !isTransientRefreshStatus(status!!)) {
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
                val outOfRoom = !request.executionContext.isActive || wait > config.maxInlineWait
                if (attempt >= config.maxAttempts || outOfRoom) {
                    val applied = cooldown.onTransientFailure(retryAfter)
                    logger.warn(
                        "Supabase token refresh failed transiently ({}, {} attempt(s)); " +
                            "keeping session, cooling down {}",
                        reason,
                        attempt,
                        applied,
                    )
                    throw failure ?: TransientAuthRefreshException(
                        status,
                        "Supabase token refresh got transient HTTP $status after $attempt attempt(s)",
                    )
                }
                logger.info("Supabase token refresh failed transiently ({}); retrying in {}", reason, wait)
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

private fun HttpRequestBuilder.copyForAttempt(): HttpRequestBuilder {
    val sub = HttpRequestBuilder().takeFrom(this)
    executionContext.invokeOnCompletion { cause ->
        val job = sub.executionContext as CompletableJob
        if (cause == null) job.complete() else job.completeExceptionally(cause)
    }
    return sub
}

private fun backoff(
    config: AuthRefreshRetryConfig,
    attempt: Int,
): Duration {
    val step = (config.baseBackoff * (1 shl (attempt - 1).coerceAtMost(16))).coerceAtMost(config.maxBackoff)
    val half = step.inWholeMilliseconds / 2
    return (half + config.random.nextLong(0, half + 1)).milliseconds
}

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
