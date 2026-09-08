package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CancellationException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext

/**
 * A blocking browser round trip, confined to one thread and answered within a deadline.
 *
 * `Frame.executeJavaScript` and `JsObject.putProperty` block until the *renderer* replies, and a
 * renderer has every right not to: one parked on a modal `window.prompt` cannot run script until the
 * dialog is answered, and one being swapped out mid-navigation never answers at all. Nothing can
 * interrupt the wait - the call has no suspension point, so a `withTimeoutOrNull` wrapped *around*
 * it is not a bound.
 *
 * Made from `Dispatchers.Main` that is a dead application, not a slow call: the EDT parks forever,
 * AppKit's main thread parks behind it, and the macOS menu bar goes with the window.
 *
 * Two threads are load-bearing, and they must be different ones:
 *
 *  - **[dispatcher]** - one daemon thread the blocking call is confined to. Daemon, because nothing
 *    can interrupt a call already inside JxBrowser and a wedged renderer must not hold up exit.
 *    Single, because that caps the cost of a wedge at one parked thread; later calls queue behind it
 *    and time out on schedule.
 *  - **[waitDispatcher]** - where the *wait* runs. Both on one thread and the timeout cannot fire:
 *    resuming the awaiting continuation needs a dispatch onto the very thread the blocking call is
 *    holding, so the wait would last as long as the renderer takes and the bound would be no bound.
 *
 * The hop to [waitDispatcher] is inside [call] rather than left to the caller on purpose. When the
 * bound depended on the caller's context, a caller that happened to be confined to a single thread
 * lost it silently, and nothing in the signature said so. The deadline is a property of this class
 * or it is not a property at all.
 *
 * **Scope of a wedge.** One instance is one blast radius, so give one to each thing that can wedge
 * independently - a tab, a peer, an integration - rather than sharing one process-wide. A shared
 * instance turns "this tab stopped answering" into "every call in the process costs a full deadline
 * and answers null", for as long as a dialog nobody knows about stays open. The thread costs nothing
 * until the first call and retires itself after [IDLE_THREAD_TTL_SECONDS] idle, so per-instance is
 * cheap even where instances are churned through without ever making a call.
 *
 * One residual constraint cannot be fixed here, so it is warned about at runtime instead: a caller
 * running **on [dispatcher] itself** can still have its *resumption* blocked, because resuming needs
 * that one thread back and the call it gave up on is holding it. The timeout fires and the value is
 * ready; delivering it is what waits. So do not `await` a [call] from a coroutine confined to
 * [dispatcher] - the scopes built on it launch fire-and-forget work, they do not await. The EDT is
 * safe either way, which is the property that matters.
 */
internal class BoundedBrowserCall(
    private val threadName: String,
    private val waitDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val logger = BossLogger.forComponent("BoundedBrowserCall")

    /**
     * Core thread that retires when idle, rather than [java.util.concurrent.Executors]'s permanent
     * one. That is what makes an instance cheap enough to hand out per tab: an instance that never
     * makes a call never creates a thread, and one that goes quiet gives its thread back.
     */
    private val executor =
        ThreadPoolExecutor(
            1,
            1,
            IDLE_THREAD_TTL_SECONDS,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(),
        ) { runnable ->
            Thread(runnable, threadName).apply { isDaemon = true }
        }.apply { allowCoreThreadTimeOut(true) }

    /**
     * The one thread every blocking round trip runs on.
     *
     * Exposed so fire-and-forget browser work (injection, teardown) can be launched straight onto
     * it and stay ordered against the calls made through [call].
     */
    val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()

    /**
     * Root of the calls, so a timed-out one is NOT a child of the coroutine that gave up on it.
     * Cancelling it could not interrupt the blocking call anyway, and as a child it would be
     * cancelled by the very timeout it is supposed to outlive.
     */
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * Run [block] on [dispatcher], answering null if it has not returned within [timeoutMs].
     *
     * Null is also the answer when [block] throws (reported to [onError]) and when this call's own
     * scope is gone because [shutdown] has run - a disposed browser answers null like every other
     * failure here rather than throwing into a plugin's coroutine. A cancellation belonging to the
     * *caller* still propagates.
     *
     * A timed-out call is not abandoned cheaply: it keeps [dispatcher]'s thread until the renderer
     * answers, and later calls queue behind it. That is the trade - a tab that stops answering
     * degrades to null instead of freezing the application - and it is why the timeout is always
     * logged. Silently answering null forever is a worse thing to debug than a freeze, because
     * nothing points at the page that caused it; the WARN names the thread, which carries the id of
     * whatever owns this instance.
     */
    // The CancellationException below is swallowed on purpose and cannot carry information worth
    // keeping: it is either the caller's, in which case ensureActive rethrows it untouched, or it is
    // the executor rejecting a dispatch after shutdown, which is this class's own bookkeeping.
    @Suppress("SwallowedException")
    suspend fun <T> call(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        onError: (Throwable) -> Unit = {},
        onTimeout: () -> Unit = {},
        block: () -> T?,
    ): T? {
        warnIfCallerIsConfinedToOurThread()
        val job = scope.async { runCatching { block() } }
        return try {
            val outcome = withContext(waitDispatcher) { withTimeoutOrNull(timeoutMs) { job.await() } }
            // Not `?.getOrElse`: on timeout `withTimeoutOrNull` answers null, which would short
            // circuit straight past every report and leave the wedge completely silent.
            if (outcome == null) reportTimeout(timeoutMs, onTimeout) else outcome.getOrElse { reportError(it, onError) }
        } catch (e: CancellationException) {
            // Distinguishes "the caller gave up" from "this browser went away underneath us".
            // ensureActive rethrows only the former; a rejected dispatch after shutdown lands here
            // as a cancellation this call owns, and owes the plugin a null rather than a throw.
            coroutineContext.ensureActive()
            null
        } finally {
            if (!job.isCompleted) job.cancel()
        }
    }

    private fun <T> reportTimeout(
        timeoutMs: Long,
        onTimeout: () -> Unit,
    ): T? {
        logger.warn(
            LogCategory.BROWSER,
            "Browser round trip timed out - the renderer did not answer",
            mapOf("thread" to threadName, "timeoutMs" to timeoutMs.toString()),
        )
        onTimeout()
        return null
    }

    private fun <T> reportError(
        error: Throwable,
        onError: (Throwable) -> Unit,
    ): T? {
        onError(error)
        return null
    }

    /**
     * The deadlock [call]'s KDoc describes, named at the moment it happens.
     *
     * On the happy path awaiting from [dispatcher] works - the block finishes, the thread frees, the
     * hop back succeeds - so it passes every test and every manual run, and hangs forever only once
     * a renderer wedges. That is the exact freeze this class exists to prevent, so it must not be
     * discoverable only by reproducing it. A warning rather than a throw: this sits on a plugin-facing
     * path, and turning a latent hang into a thrown exception in someone else's coroutine is its own
     * kind of surprise.
     */
    private suspend fun warnIfCallerIsConfinedToOurThread() {
        if (coroutineContext[ContinuationInterceptor] === dispatcher) {
            logger.warn(
                LogCategory.BROWSER,
                "Browser round trip awaited from its own dispatcher - this will hang if the renderer wedges",
                mapOf("thread" to threadName),
            )
        }
    }

    /**
     * Run [block] on [dispatcher] without waiting for it.
     *
     * For the round trips a **non-suspend** caller has to make - a plugin-facing `fun` that returns
     * Unit, an injection, a teardown. No deadline applies and none is missing: there is no wait to
     * bound, the caller returns immediately either way, and a wedged renderer costs this instance's
     * one thread and nothing else. Awaiting instead is what could not be done from here anyway,
     * since a non-suspend caller has no context to suspend in.
     *
     * Queued on the same unbounded queue as [call] and NOT cancelled by anything, so a caller
     * producing a *stream* through this must check [backlog] and drop. See [backlog].
     */
    fun post(block: () -> Unit) {
        scope.launch {
            // runCatching for the reason [call] uses it: JxBrowser throws from a torn-down frame,
            // and the narrowest useful type here is Throwable. A backstop rather than the reporting
            // path - callers own their own failures - but without it a throw from fire-and-forget
            // work reaches the default handler and prints to stderr, bypassing BossLogger entirely.
            runCatching { block() }.onFailure { error ->
                logger.warn(
                    LogCategory.BROWSER,
                    "Fire-and-forget browser call failed",
                    mapOf("thread" to threadName),
                    error = error,
                )
            }
        }
    }

    /**
     * Work queued on [dispatcher] and not yet started.
     *
     * The queue is unbounded, which is fine for anything awaited - [call]'s `finally` cancels the job
     * it gave up on, so a stale one resumes with cancellation instead of running when the renderer
     * recovers. It is NOT fine for a fire-and-forget stream: nothing cancels those, so against a
     * wedged renderer they pile up holding their payloads. A caller producing such a stream should
     * read this and drop rather than enqueue.
     *
     * Counts only what has not *started*. A stream whose queue this gates must therefore also count
     * whatever it holds in a queue of its own upstream of here, or the gate reads zero in exactly
     * the state it was written for - see `CoBrowseRtcPeerImpl.sendDom`.
     */
    val backlog: Int get() = executor.queue.size

    /**
     * Stop accepting new calls.
     *
     * `shutdown()` and not `shutdownNow()`: a call already inside JxBrowser cannot be interrupted,
     * so interrupting would buy nothing, and work already queued still deserves to run. The thread
     * is daemon, so a wedged call cannot hold up exit.
     *
     * Not a full teardown, and deliberately not: [scope] is left uncancelled and [dispatcher] left
     * open, because cancelling the scope would drop work already queued - which is the teardown its
     * owner usually just posted. Both are reachable-object concerns only, and the executor's thread
     * retires on its own after [IDLE_THREAD_TTL_SECONDS], so the whole instance becomes garbage with
     * whatever owned it. Later calls answer null via the rejected-dispatch path in [call].
     */
    fun shutdown() {
        executor.shutdown()
    }

    companion object {
        /**
         * How long a renderer round trip waits before giving up and answering null.
         *
         * Generous rather than tight: it bounds a call someone asked for and that may legitimately
         * be slow, and its only job is to make the wait finite. A page that never answers - one
         * parked on a modal `window.prompt` - used to hold its thread forever, and that thread used
         * to be the EDT.
         *
         * Named here rather than duplicated per call site: two copies with a comment claiming they
         * match is exactly the pair this repo pins in a test elsewhere.
         */
        const val DEFAULT_TIMEOUT_MS = 10_000L

        /** Long enough to serve a burst of calls on one thread, short enough not to hold one idle. */
        private const val IDLE_THREAD_TTL_SECONDS = 30L
    }
}
