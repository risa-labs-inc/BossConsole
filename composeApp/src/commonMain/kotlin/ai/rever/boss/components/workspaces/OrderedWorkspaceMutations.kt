package ai.rever.boss.components.workspaces

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** A single writer orders disk mutation and its memory publication as one transaction. */
internal class OrderedWorkspaceMutations(
    scope: CoroutineScope,
) {
    private class Request(
        val run: suspend () -> Unit,
        val cancel: () -> Unit,
    )

    private val pending = Channel<Request>(Channel.UNLIMITED, onUndeliveredElement = { it.cancel() })
    private val logger = BossLogger.forComponent("WorkspaceMutations")

    init {
        val worker =
            scope.launch {
                for (request in pending) request.run()
            }
        worker.invokeOnCompletion { pending.cancel() }
    }

    /** Awaiter cancellation does not interrupt an already admitted disk transaction. */
    // This queue boundary isolates arbitrary backend failures so later requests can still run.
    @Suppress("TooGenericExceptionCaught")
    fun <T> submit(action: suspend () -> T): Deferred<Result<T>> {
        val completion = CompletableDeferred<Result<T>>()
        val request =
            Request(
                run = {
                    try {
                        completion.complete(Result.success(action()))
                    } catch (e: CancellationException) {
                        completion.cancel(e)
                        // Cancellation of this request is not shutdown of the writer. Stop the
                        // queue only when its owning scope was cancelled too.
                        currentCoroutineContext().ensureActive()
                    } catch (e: Exception) {
                        logger.warn(LogCategory.WORKSPACE, "Space mutation failed", error = e)
                        completion.complete(Result.failure(e))
                    }
                },
                cancel = { completion.cancel() },
            )
        if (pending.trySend(request).isFailure) completion.cancel()
        return completion
    }
}
