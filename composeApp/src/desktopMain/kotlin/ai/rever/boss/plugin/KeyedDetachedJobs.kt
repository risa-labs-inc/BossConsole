package ai.rever.boss.plugin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * Runs keyed jobs on [scope], DETACHED from the caller's coroutine, coalescing
 * concurrent requests for the same key onto the single in-flight job.
 *
 * Built for plugin reloads driven from a plugin's own coroutine (Toolbox's
 * update flow, the evolver's hot reload): reloading the caller's OWN plugin
 * force-unloads it, cancelling the calling scope mid-operation. Detaching lets
 * the job run to completion — the caller merely observes the cancellation at
 * await — and coalescing keeps a second reload from interleaving with one
 * still mid-uninstall/load (the per-key equivalent of hotSwapApiLayer's
 * apiSwapInProgress guard).
 */
internal class KeyedDetachedJobs<K : Any, V>(
    private val scope: CoroutineScope,
) {
    private val inFlight = HashMap<K, Deferred<V>>()

    /**
     * Run [block] for [key], or join the job already in flight for the same key.
     *
     * Joining means getting the in-flight job's result: inputs [block] reads
     * (e.g. the jar on disk when the job started) are the ones applied, so a
     * request arriving with newer state must run again after the in-flight
     * job completes — coalescing trades that narrow staleness window for
     * never interleaving two executions on one key.
     *
     * If the caller is cancelled while awaiting (typically because the job
     * unloaded the caller's own plugin), the job still runs to completion and
     * [onDetachedFailure] receives any non-cancellation Throwable it dies with
     * — with the caller gone, whatever that callback records is the only
     * trace of the outcome. Each cancelled caller registers its own handler,
     * so one failure may invoke [onDetachedFailure] once per cancelled caller;
     * keep it idempotent (duplicate log lines at worst).
     */
    suspend fun run(
        key: K,
        onDetachedFailure: (Throwable) -> Unit = {},
        block: suspend () -> V,
    ): V {
        val job =
            synchronized(inFlight) {
                inFlight[key]?.takeIf { it.isActive }
                    ?: scope.async { block() }.also { job ->
                        inFlight[key] = job
                        // Identity-checked removal: by the time a finished job's
                        // handler runs, a newer job may already occupy the slot.
                        job.invokeOnCompletion {
                            synchronized(inFlight) {
                                if (inFlight[key] === job) inFlight.remove(key)
                            }
                        }
                    }
            }
        return try {
            job.await()
        } catch (ce: CancellationException) {
            job.invokeOnCompletion { cause ->
                if (cause != null && cause !is CancellationException) onDetachedFailure(cause)
            }
            throw ce
        }
    }
}
