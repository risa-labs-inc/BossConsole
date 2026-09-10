package ai.rever.boss.plugin.browser

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Owns the PID capture and page-helper job for the latest main-frame commit.
 *
 * Frame acquisition stays on the navigation callback thread to name this commit's document;
 * acquiring it later could observe the next navigation. Only the follow-up is dispatched.
 * Empty/about:blank commits refresh the PID too, or a dashboard could retain a heavy page's PID.
 * The native calls run outside [lock], so a blocked renderer cannot hold up invalidation. The
 * generation check and PID publication share that lock: cancellation alone leaves a check/write
 * race with a newer commit or renderer death. The frame type at the call site is JxBrowser Frame; keeping
 * it out of this state machine lets the ordering be tested without starting Chromium.
 */
internal class NavigationPageInjection(
    private val rendererPid: RendererPid,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val reportPidFailure: (Exception) -> Unit,
) {
    private val lock = Any()
    private var generation = 0L
    private var pending: Job? = null

    fun <T : Any> onCommit(
        url: String,
        mainFrame: () -> T?,
        readPid: (T) -> Int?,
        inject: suspend (T) -> Unit,
    ) {
        // Must precede even a failed or absent frame lookup. Otherwise the previous document's
        // job can survive the early return, and its PID can still be reported for this commit.
        val commit = invalidate()
        val frame = mainFrame() ?: return
        val followUp =
            scope.launch(dispatcher, start = CoroutineStart.LAZY) {
                val pid = readOptionalPid { readPid(frame) }
                synchronized(lock) {
                    ensureActive()
                    if (generation != commit) return@launch
                    rendererPid.onCommit(pid)
                }
                ensureActive()
                // Dashboard and empty URLs still refresh the PID, but never receive helpers.
                if (url.isNotEmpty() && url != "about:blank") inject(frame)
            }
        // Acquisition can overlap another commit or a close. Never let the slower callback
        // replace the newer job. Publish a lazy job so it cannot run before it is cancellable.
        synchronized(lock) {
            if (generation == commit) {
                pending = followUp
            } else {
                followUp.cancel()
            }
        }
        followUp.start()
    }

    // A diagnostic failure must not remove page helpers. Cancellation and fatal errors still stop work.
    @Suppress("TooGenericExceptionCaught")
    private fun readOptionalPid(read: () -> Int?): Int? =
        try {
            read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportPidFailure(e)
            null
        }

    /** Also used on renderer death, browser close and disposal; a later reload may commit again. */
    fun onGone() {
        invalidate()
    }

    private fun invalidate(): Long {
        val (commit, previous) =
            synchronized(lock) {
                generation++
                val previous = pending
                pending = null
                rendererPid.onGone()
                generation to previous
            }
        // Cancellation can run completion handlers inline. Do not run them under the state lock.
        previous?.cancel()
        return commit
    }
}
