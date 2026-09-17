package ai.rever.boss.components.plugin

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class PluginUpdateAlreadyInProgressException(
    val pluginId: String,
) : IllegalStateException("An update for plugin '$pluginId' is already in progress")

/**
 * Rejects overlapping host updates for the same plugin while allowing different
 * plugins to update concurrently. One instance must be shared across host windows.
 *
 * Only PluginUpdateBridge.performUpdate participates. Store-version swaps,
 * dependency installs, and other processes do not share this admission set.
 */
internal class ExclusivePluginUpdates {
    private val admissionMutex = Mutex()
    private val activePluginIds = mutableSetOf<String>()

    /**
     * Returns a failed result for a busy plugin; exceptions from [operation] propagate.
     * Admission is retained until [operation] returns or throws, including its failure
     * and cancellation cleanup. The bridge acquires it before dependent-restart consent,
     * so it can remain held through the prompt's five-minute timeout before downloading.
     */
    suspend fun <T> run(
        pluginId: String,
        operation: suspend () -> Result<T>,
    ): Result<T> {
        val admitted = admissionMutex.withLock { activePluginIds.add(pluginId) }
        if (!admitted) {
            return Result.failure(PluginUpdateAlreadyInProgressException(pluginId))
        }

        return try {
            operation()
        } finally {
            withContext(NonCancellable) {
                admissionMutex.withLock {
                    activePluginIds.remove(pluginId)
                }
            }
        }
    }
}
