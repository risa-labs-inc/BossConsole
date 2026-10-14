package ai.rever.boss.sharing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.io.IOException

private const val RECOVERY_MS = 30_000L

internal fun transientAppSharingFailure(error: Exception): Boolean =
    error is IOException ||
        (
            error is AppSharingException &&
                (error.status == 408 || error.status == 429 || (error.status ?: 0) in 500..599)
        )

/** Retry only established-session health checks, never admission or SFU mutations. */
@Suppress("TooGenericExceptionCaught") // Classify transport errors; all other exceptions propagate.
internal suspend fun recoverAppSharingRequest(
    onInterrupted: () -> Unit = {},
    request: suspend () -> JsonObject,
): JsonObject {
    try {
        val response = request()
        currentCoroutineContext().ensureActive()
        return response
    } catch (error: Exception) {
        // Cancellation is not a transport error and propagates through this test.
        if (!transientAppSharingFailure(error)) throw error
        onInterrupted()
    }
    return withTimeoutOrNull(RECOVERY_MS) { retryAppSharingRequest(request) }
        ?: throw AppSharingException("recovery_timed_out", 503)
}

@Suppress("TooGenericExceptionCaught") // The same classification also applies to subsequent retries.
private suspend fun retryAppSharingRequest(request: suspend () -> JsonObject): JsonObject {
    var retryDelay = 1000L
    while (true) {
        delay(retryDelay)
        try {
            val response = request()
            currentCoroutineContext().ensureActive()
            return response
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!transientAppSharingFailure(error)) throw error
        }
        retryDelay = (retryDelay * 2).coerceAtMost(5000)
    }
}
