package ai.rever.boss.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Action handlers may have side effects even when their verdict is false or
 * unknown. Never replay an action automatically, including after a lost reply.
 * Other open requests retain the startup retry policy used by auth callbacks.
 */
internal fun forwardDeepLinkWithRetry(
    link: String,
    send: (attempt: Int) -> Boolean,
    pause: () -> Unit,
): Boolean {
    // Match the same raw action key accepted by DeepLinkHandler's query parser.
    val isAction =
        routedDeepLinkHost(link) == DeepLinkHost.PLUGIN &&
            link.substringAfter("?", "").split("&").any { it.startsWith("action=") }
    val attempts = if (isAction) 1 else 3
    for (attempt in 1..attempts) {
        if (send(attempt)) return true
        if (attempt < attempts) pause()
    }
    return false
}

/** Null means unknown at the deadline; false includes a cancelled dispatch. */
internal suspend fun awaitPluginAction(
    verdict: Deferred<Boolean>,
    timeoutMillis: Long,
): Boolean? {
    val result =
        try {
            withTimeoutOrNull(timeoutMillis) { verdict.await() }
        } catch (_: CancellationException) {
            // A cancelled dispatch is a negative verdict, but cancellation of
            // this waiter itself must still propagate to its caller.
            currentCoroutineContext().ensureActive()
            false
        }
    if (result == null) verdict.cancel()
    return result
}
