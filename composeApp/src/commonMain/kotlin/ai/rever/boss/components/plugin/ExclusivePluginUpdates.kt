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
 */
internal class ExclusivePluginUpdates {
    private val admissionMutex = Mutex()
    private val activePluginIds = mutableSetOf<String>()

    /**
     * Rejects overlapping host updates for the same plugin while allowing different
     * plugins to update concurrently. One instance must be shared across host windows.
     *
     * Admission is retained until [operation] returns or throws, including its failure
     * and cancellation cleanup.
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
