package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The process-wide browser cleanup work, and the handle a shutdown needs to wait on it.
 *
 * Both cleanup scopes below run on daemon threads, so nothing on the JVM exit path waited for them:
 * the shutdown hook closed the JxBrowser engine while a native close or a profile release was still
 * queued behind it, and that work was abandoned at exit. Those cleanups call into the engine, which
 * is why they cannot simply outlive it. Sharing one parent [job] is what makes them visible to
 * [awaitDrained]; a supervisor, so a failed cleanup still cannot cancel its siblings.
 *
 * [awaitDrained] is a rendezvous, not a flush guarantee. It waits for the cleanup registered before
 * it, bounded by its deadline, and reports whether it got there. Work registered after it returns is
 * not covered - the process is exiting and the engine is about to close, and refusing that work
 * would strand a browser or its profile rather than save one.
 */
internal object BrowserCleanupDrain {
    val job = SupervisorJob()

    /** How much cleanup is outstanding, for the shutdown log line when a drain gives up. */
    val pending: Int get() = job.children.count()

    /**
     * Wait up to [deadlineMs] for the cleanup outstanding at the time of the call. True if it
     * drained. The bound is real: a wedged renderer keeps its browser and profile exactly as it
     * does on the per-handle path, and the caller reports what was left behind rather than hanging.
     *
     * Joining the snapshot and then re-reading it is what covers work admitted while the drain is
     * already waiting; polling would only narrow that window without closing it.
     */
    suspend fun awaitDrained(deadlineMs: Long): Boolean =
        withTimeoutOrNull(deadlineMs) {
            while (true) {
                val outstanding = job.children.toList()
                if (outstanding.isEmpty()) return@withTimeoutOrNull
                outstanding.forEach { it.join() }
            }
        } != null
}

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
    private val pendingCount = AtomicInteger()
    private val runningCount = AtomicInteger()

    val pending: Int get() = pendingCount.get()
    val inFlight: Int get() = runningCount.get()

    override fun execute(command: Runnable) {
        // Count before submission: dequeueing must never create a false idle observation.
        pendingCount.incrementAndGet()
        try {
            super.execute {
                runningCount.incrementAndGet()
                try {
                    command.run()
                } finally {
                    runningCount.decrementAndGet()
                    pendingCount.decrementAndGet()
                }
            }
        } catch (rejected: RejectedExecutionException) {
            // The only failure `execute` declares. Catching RuntimeException here would also
            // swallow a bug in the accounting itself and report it as a rejected submission.
            pendingCount.decrementAndGet()
            throw rejected
        }
    }

    override fun shutdownNow(): List<Runnable> {
        val discarded = super.shutdownNow()
        pendingCount.addAndGet(-discarded.size)
        return discarded
    }

    init {
        allowCoreThreadTimeOut(true)
    }

    override fun terminated() {
        drained.complete(Unit)
    }

    // terminated() completes under the executor's mainLock. Production waiters use Dispatchers.IO,
    // so their continuations are dispatched; do not put inline/Unconfined work on this signal.
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
    private val handleId: String = "unknown",
    private val warningAfterMs: Long = 10_000L,
    private val reportPending: () -> Unit = {
        logger.warn(LogCategory.BROWSER, "Browser native disposal still pending", mapOf("handleId" to handleId))
    },
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
                    val drained =
                        withTimeoutOrNull(warningAfterMs) {
                            executors.forEach { it.awaitDrained() }
                            true
                        }
                    if (drained == null) {
                        reportPending()
                        // Expiry is diagnostic only. It never authorizes native close.
                        executors.forEach { it.awaitDrained() }
                    }
                    close()
                }
            result.fold(
                onSuccess = { completion.complete(Unit) },
                onFailure = { error ->
                    if (error is CancellationException) {
                        completion.cancel(error)
                        throw error
                    }
                    logger.warn(
                        LogCategory.BROWSER,
                        "Deferred browser close failed",
                        mapOf("handleId" to handleId),
                        error = error,
                    )
                    completion.completeExceptionally(error)
                },
            )
        }
    }

    suspend fun awaitCompletion() = completion.await()

    companion object {
        // Shares the process-wide cleanup job so a shutdown can wait for this scope: see
        // [BrowserCleanupDrain]. Daemon threads mean nothing else on the exit path does.
        private val scope = CoroutineScope(BrowserCleanupDrain.job + Dispatchers.IO)
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
            if (error is CancellationException) throw error
            BrowserDisposalCleanup.logger.warn(LogCategory.BROWSER, "Browser resource cleanup failed", error = error)
            throw error
        }
    }
}

private object BrowserDisposalCleanup {
    // Shares the process-wide cleanup job so a shutdown can wait for this scope: see
    // [BrowserCleanupDrain].
    val scope = CoroutineScope(BrowserCleanupDrain.job + Dispatchers.IO)
    val logger = BossLogger.forComponent("BrowserDisposalCleanup")
}

/**
 * Acquire a profile lease with a bounded wait, leaving a potentially live profile protected.
 * The caller owns the lock on return. A timeout/cancellation during handoff must give it back.
 */
internal suspend fun acquireManagedProfileLock(
    mutex: Mutex,
    timeoutMs: Long = 10_000L,
) {
    var acquired = false
    var delivered = false
    try {
        val completed =
            withTimeoutOrNull(timeoutMs) {
                mutex.lock()
                acquired = true
                true
            }
        check(completed == true) {
            "Managed browser profile is still in use by an open browser or pending native disposal. " +
                "Retry after it closes."
        }
        delivered = true
    } finally {
        if (acquired && !delivered) mutex.unlock()
    }
}

/** Attempt view detachment even after earlier teardown fails, before an idle drain can close native state. */
internal fun finishLocalBrowserDisposal(
    detachView: () -> Unit,
    requestNativeClose: () -> Unit,
) {
    try {
        detachView()
    } finally {
        requestNativeClose()
    }
}
