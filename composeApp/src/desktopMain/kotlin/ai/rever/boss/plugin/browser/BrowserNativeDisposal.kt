package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A per-browser daemon executor whose drain signal follows actual execution, not caller cancellation. */
internal class DrainingBrowserExecutor(
    threadName: String,
) : ThreadPoolExecutor(
        1,
        1,
        IDLE_SECONDS,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(),
        { task -> Thread(task, threadName).apply { isDaemon = true } },
    ) {
    private val drained = CompletableDeferred<Unit>()

    init {
        allowCoreThreadTimeOut(true)
    }

    override fun terminated() {
        drained.complete(Unit)
    }

    suspend fun awaitDrained() = drained.await()

    companion object {
        private const val IDLE_SECONDS = 30L
    }
}

/**
 * Closing a tab stops admission immediately; native close waits for all owned workers to finish.
 * A timed-out or cancelled coroutine does not terminate a blocking JxBrowser call. Executor
 * termination does, including synchronous and RPC failures, without relying on a success callback.
 *
 * The wait suspends in a host-owned scope: it consumes no waiting thread and cannot be cancelled
 * by the tab/plugin that requested disposal. A wedged renderer retains its browser and profile
 * until its native calls return (including after engine recovery), rather than closing underneath
 * them after an arbitrary timeout. This is not an abort primitive or an engine-recovery policy.
 */
internal class BrowserNativeDisposal(
    private val executors: List<DrainingBrowserExecutor>,
    private val close: () -> Unit,
) {
    private val started = AtomicBoolean(false)
    private val completion = CompletableDeferred<Unit>()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        executors.forEach { it.shutdown() }
        scope.launch {
            val result =
                runCatching {
                    executors.forEach { it.awaitDrained() }
                    close()
                }
            result.fold(
                onSuccess = { completion.complete(Unit) },
                onFailure = { error ->
                    logger.warn(LogCategory.BROWSER, "Deferred browser close failed", error = error)
                    completion.completeExceptionally(error)
                },
            )
        }
    }

    suspend fun awaitCompletion() = completion.await()

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val logger = BossLogger.forComponent("BrowserNativeDisposal")
    }
}

/**
 * Detach the view on the caller's thread, but own native/profile cleanup independently of its Job.
 * A cancelled service waiter cannot strand the already-unregistered browser or its profile fence.
 * Direct plugin disposal is reconciled through this same path when the host prunes the handle.
 */
internal fun disposeBrowserResources(
    dispose: () -> Unit,
    awaitNativeClose: suspend () -> Unit,
    release: suspend () -> Unit,
): Deferred<Unit> {
    val teardown = runCatching(dispose)
    return BrowserDisposalCleanup.scope.async {
        runCatching {
            // If native close fails, do not delete a potentially still-live profile.
            awaitNativeClose()
            release()
            teardown.getOrThrow()
        }.getOrElse { error ->
            BrowserDisposalCleanup.logger.warn(LogCategory.BROWSER, "Browser resource cleanup failed", error = error)
            throw error
        }
    }
}

private object BrowserDisposalCleanup {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val logger = BossLogger.forComponent("BrowserDisposalCleanup")
}
