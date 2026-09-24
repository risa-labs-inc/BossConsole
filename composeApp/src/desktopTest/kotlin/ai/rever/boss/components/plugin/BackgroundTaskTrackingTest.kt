package ai.rever.boss.components.plugin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A handle the provider never releases is invisible through `BackgroundTaskProvider`:
 * `getRunningTasks` filters it out because it is no longer active, and `cancelAll` skips it for the
 * same reason. The only symptom is `activeTasks` growing for the lifetime of the window, which is
 * why these assert on the tracked count rather than on anything the interface reports.
 *
 * `Dispatchers.Unconfined` throughout, and it is doing real work rather than standing in for a
 * dispatcher: it runs the body inline on `launch` and resumes a continuation inline on the thread
 * that completes it, so every ordering below is the only one possible rather than the likely one.
 * No sleeps and no timeouts.
 */
class BackgroundTaskTrackingTest {
    /**
     * The handle was stored after `scope.launch`, and the release was a `finally` inside the
     * coroutine. A task that reaches the end of its body before the launching thread stores the
     * handle therefore removed a key that was not there yet, and the handle that landed afterwards
     * was never released.
     *
     * In production the scope is `Dispatchers.Main`. A caller already on the UI thread cannot hit
     * this, because the body is queued behind the rest of the current frame. `launchTask` is
     * plugin-facing and documents no thread requirement, though, and nothing in this repository
     * calls it: every caller is a plugin, running on a thread of its own choosing. A plugin calling
     * it off the UI thread gets exactly this interleaving.
     */
    @Test
    fun `a task that finishes before its handle is stored does not leak the handle`() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val provider = DefaultBackgroundTaskProvider(scope)

            provider.launchTask("probe") { }

            assertEquals(
                0,
                provider.trackedTaskCount(),
                "a finished task must not leave a handle behind; those are invisible and unbounded",
            )
        } finally {
            scope.cancel()
        }
    }

    /**
     * The same defect without the race, which makes it the reachable one.
     *
     * `DefaultPlugin.dispose()` cancels `pluginScope`. A `launchTask` after that creates a
     * coroutine that is cancelled before its body's first statement, so the old `finally` never ran
     * at all and the entry leaked every time, on any thread, with no interleaving required.
     * `invokeOnCompletion` fires immediately for a job that is already complete, cancellation
     * included, so the handle is released on the way out.
     */
    @Test
    fun `a task launched on an already cancelled scope leaves nothing behind`() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        scope.cancel()
        val provider = DefaultBackgroundTaskProvider(scope)

        provider.launchTask("after-dispose") { }

        assertEquals(
            0,
            provider.trackedTaskCount(),
            "the body never runs on a cancelled scope, so only a completion handler can release it",
        )
    }

    /**
     * The completion handler must evict its own handle and no other.
     *
     * `taskId` is the task name plus `System.currentTimeMillis()`, so two same-named tasks launched
     * inside one millisecond share a key and the second `put` overwrites the first handle. A
     * key-only `remove` would then let the first task to finish drop the second, still-running
     * task's handle, which is worse than the leak this PR fixes: `getRunningTasks` stops reporting
     * a live task and `cancelAll` stops cancelling it.
     *
     * The collision is forced through `taskIdOverride` rather than waited for. Two earlier versions
     * of this test tried to produce it from the real clock and both passed against the key-only
     * `remove` they existed to catch, because the two launches never landed in one millisecond: the
     * first `launchTask` in a JVM costs about 11ms of class loading, and warming up on a shape that
     * does not suspend still leaves `CompletableDeferred.await` cold for the first real launch. A
     * test whose coverage depends on winning that race is a test that reports nothing on the runs
     * it loses, which is the case here for the one assertion that cannot fail spuriously.
     *
     * The overwrite at `put` time is untouched here and belongs to #1478's unique ids. This asserts
     * the precondition rather than assuming it, so the shared key cannot quietly stop being shared.
     */
    @Test
    fun `a completing task does not evict a sibling sharing its id`() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val provider = DefaultBackgroundTaskProvider(scope) { "$it-fixed" }
            val first = CompletableDeferred<Unit>()
            val second = CompletableDeferred<Unit>()

            provider.launchTask("sync") { first.await() }
            provider.launchTask("sync") { second.await() }
            assertEquals(
                1,
                provider.trackedTaskCount(),
                "both launches must share one key, or this test covers nothing",
            )

            // Finish only the first. The second is still running, so its handle must survive.
            first.complete(Unit)

            assertEquals(
                1,
                provider.trackedTaskCount(),
                "a task that finished must not take a still-running task's handle with it",
            )
            // The consequence a caller actually meets: a live task the host can still see and stop.
            assertEquals(
                1,
                provider.getRunningTasks().size,
                "the still-running task must remain reachable through the interface",
            )

            second.complete(Unit)
        } finally {
            scope.cancel()
        }
    }

    /** The ordinary path: tracked while it runs, released when it finishes. */
    @Test
    fun `a task that suspends is tracked until it completes`() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val provider = DefaultBackgroundTaskProvider(scope)
            val gate = CompletableDeferred<Unit>()

            provider.launchTask("worker") { gate.await() }
            assertEquals(1, provider.trackedTaskCount(), "a suspended task is still a running task")

            // Unconfined resumes the continuation inline, so the job is complete when this returns.
            gate.complete(Unit)
            assertEquals(0, provider.trackedTaskCount(), "finishing releases the handle")
        } finally {
            scope.cancel()
        }
    }

    /** Cancellation is a completion too, so it must release the handle by the same route. */
    @Test
    fun `a cancelled task releases its handle`() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val provider = DefaultBackgroundTaskProvider(scope)
            val gate = CompletableDeferred<Unit>()

            val handle = provider.launchTask("worker") { gate.await() }
            assertEquals(1, provider.trackedTaskCount(), "a running task is tracked")

            handle?.cancel()
            assertEquals(0, provider.trackedTaskCount(), "cancelling releases the handle")
        } finally {
            scope.cancel()
        }
    }
}
