package ai.rever.boss.services.auth

import io.github.jan.supabase.exceptions.RestException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Decides how to react when a Supabase session refresh keeps failing.
 *
 * supabase-kt's auto-refresher retries transient failures on its own, but only
 * clears the session when its *scheduled* refresh is rejected with a hard 4xx.
 * The force-refresh path used by authenticated requests (Realtime reconnects,
 * Postgrest calls) instead throws TokenExpiredException inside library-internal
 * coroutines, so the app can sit on an expired session indefinitely. This
 * policy backs the host-side recovery loop in [CoreAuthService].
 */
internal object SessionRecoveryPolicy {

    sealed interface Action {
        /** Transient failure (connectivity, timeout, 5xx) — retry with backoff. */
        data object Retry : Action

        /** The auth server rejected the refresh token — the session is unrecoverable. */
        data object ClearSession : Action
    }

    val initialBackoff: Duration = 10.seconds
    val maxBackoff: Duration = 60.seconds

    /**
     * A refresh failure is fatal only when the auth server explicitly rejected
     * the refresh token (4xx — e.g. revoked, or rotated by another running
     * instance). Everything else — no network, timeouts, 5xx, Cloudflare 52x —
     * may heal on its own and keeps the session. Within 4xx, 408 (request
     * timeout) and 429 (rate limited — GoTrue and the Cloudflare edge both
     * emit these under load) say nothing about the token itself and retry.
     */
    fun actionFor(error: Throwable): Action = when (error) {
        is RestException -> actionForStatus(error.statusCode)
        else -> Action.Retry
    }

    fun actionForStatus(statusCode: Int): Action = when {
        statusCode == 408 || statusCode == 429 -> Action.Retry
        statusCode in 400..499 -> Action.ClearSession
        else -> Action.Retry
    }

    fun nextBackoff(current: Duration): Duration =
        (current * 2).coerceAtMost(maxBackoff)
}
