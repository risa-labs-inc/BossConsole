package ai.rever.boss.utils

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.arkivanov.essenty.lifecycle.Lifecycle
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy

/**
 * Isolates independent, synchronous teardown actions. It neither schedules work nor
 * changes ownership: callers still claim resources once and preserve release ordering.
 * Use only while releasing resources, never to hide startup or normal operation failures.
 */
internal class CleanupRunner(
    component: String,
    private val context: Map<String, String> = emptyMap(),
) {
    private val logger = BossLogger.forComponent(component)

    // Plugin teardown can throw LinkageError after its classloader has closed.
    @Suppress("TooGenericExceptionCaught")
    fun run(
        action: String,
        cleanup: () -> Unit,
    ): Boolean =
        try {
            cleanup()
            true
        } catch (t: Throwable) {
            logger.warn(LogCategory.UI, "Cleanup failed (continuing)", context + ("action" to action), t)
            false
        }

    /** Finish later lifecycle phases after a callback fails, without redelivering a phase. */
    fun destroyLifecycle(
        action: String,
        lifecycle: LifecycleRegistry,
    ) {
        // Essenty advances state before callbacks. A failed pause/stop can therefore
        // continue from its new state; a failed destroy is already terminal.
        while (lifecycle.state != Lifecycle.State.DESTROYED) {
            val previousState = lifecycle.state
            run(action) { lifecycle.destroy() }
            if (lifecycle.state == previousState) return
        }
    }
}
