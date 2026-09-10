package ai.rever.boss.plugin

import kotlinx.coroutines.CancellationException

/**
 * GitHub-path failures are repairable; cancellation must never enqueue another channel.
 * The caller also catches failures after publication. Those queued entries are safe
 * no-ops because the drain checks manifest-based presence again before downloading.
 */
internal fun queueStoreRepairAfterGitHubFailure(
    failure: Exception,
    enqueue: (String) -> Unit,
) {
    if (failure is CancellationException) throw failure
    // The exception type identifies DNS, TLS or timeout failures without copying URL credentials.
    enqueue("GitHub request failed (${failure.javaClass.simpleName})")
}
