package ai.rever.boss.plugin.loader

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.coroutines.delay
import java.lang.ref.WeakReference

/**
 * Watches for classloader garbage collection after plugin unload.
 *
 * This follows IntelliJ IDEA's GCWatcher pattern to verify that
 * classloaders are properly released after plugin unloading.
 *
 * Memory leaks from unreleased classloaders are a common issue with
 * dynamic plugin systems, so this provides early detection.
 */
object ClassLoaderGCWatcher {
    private val logger = BossLogger.forComponent("ClassLoaderGCWatcher")

    /**
     * Default timeout for waiting for classloader GC in milliseconds.
     */
    const val DEFAULT_GC_TIMEOUT_MS = 30_000L

    /**
     * Interval between GC checks in milliseconds.
     */
    const val GC_CHECK_INTERVAL_MS = 500L

    /**
     * Wait for a classloader to be garbage collected.
     *
     * @param pluginId The ID of the plugin whose classloader we're watching
     * @param classLoaderRef Weak reference to the classloader
     * @param timeoutMs Maximum time to wait for GC
     * @return Result indicating success or failure with details
     */
    suspend fun waitForGC(
        pluginId: String,
        classLoaderRef: WeakReference<PluginClassLoader>,
        timeoutMs: Long = DEFAULT_GC_TIMEOUT_MS
    ): GCWaitResult {
        val startTime = System.currentTimeMillis()
        var attemptCount = 0

        logger.debug(LogCategory.SYSTEM, "Starting GC watch for plugin classloader", mapOf(
            "pluginId" to pluginId,
            "timeoutMs" to timeoutMs
        ))

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            attemptCount++

            // Suggest GC
            System.gc()
            System.runFinalization()

            // Brief delay to allow GC to run
            delay(GC_CHECK_INTERVAL_MS)

            // Check if classloader was collected
            if (classLoaderRef.get() == null) {
                val elapsedMs = System.currentTimeMillis() - startTime
                logger.info(LogCategory.SYSTEM, "Plugin classloader was garbage collected", mapOf(
                    "pluginId" to pluginId,
                    "elapsedMs" to elapsedMs,
                    "attempts" to attemptCount
                ))
                return GCWaitResult.Collected(elapsedMs, attemptCount)
            }
        }

        // Timeout - classloader was not collected
        val elapsedMs = System.currentTimeMillis() - startTime
        logger.warn(LogCategory.SYSTEM, "Plugin classloader was NOT garbage collected (possible leak)", mapOf(
            "pluginId" to pluginId,
            "elapsedMs" to elapsedMs,
            "attempts" to attemptCount
        ))

        return GCWaitResult.NotCollected(
            pluginId = pluginId,
            elapsedMs = elapsedMs,
            attempts = attemptCount,
            possibleLeakSources = identifyPossibleLeakSources(classLoaderRef.get())
        )
    }

    /**
     * Check if a classloader has been collected without waiting.
     *
     * @param classLoaderRef Weak reference to the classloader
     * @return True if the classloader has been garbage collected
     */
    fun isCollected(classLoaderRef: WeakReference<PluginClassLoader>): Boolean {
        return classLoaderRef.get() == null
    }

    /**
     * Try to identify possible sources of memory leaks.
     *
     * This is a best-effort diagnostic that examines common leak patterns.
     */
    private fun identifyPossibleLeakSources(classLoader: PluginClassLoader?): List<String> {
        if (classLoader == null) return emptyList()

        val sources = mutableListOf<String>()

        // Check for common leak indicators
        try {
            // Active threads created by the classloader
            val threadGroup = Thread.currentThread().threadGroup
            val threads = arrayOfNulls<Thread>(threadGroup.activeCount() * 2)
            threadGroup.enumerate(threads)

            val pluginThreads = threads.filterNotNull().filter {
                it.contextClassLoader === classLoader
            }

            if (pluginThreads.isNotEmpty()) {
                sources.add("Active threads using plugin classloader: ${pluginThreads.size}")
                pluginThreads.take(5).forEach { thread ->
                    sources.add("  - Thread: ${thread.name} (state: ${thread.state})")
                }
            }
        } catch (e: Exception) {
            logger.debug(LogCategory.SYSTEM, "Error checking for leak sources", mapOf(
                "error" to (e.message ?: "unknown")
            ))
        }

        if (sources.isEmpty()) {
            sources.add("No obvious leak sources identified. Check for static references, caches, or listeners.")
        }

        return sources
    }
}

/**
 * Result of waiting for classloader garbage collection.
 */
sealed class GCWaitResult {
    /**
     * Classloader was successfully garbage collected.
     */
    data class Collected(
        /**
         * Time taken for collection in milliseconds.
         */
        val elapsedMs: Long,

        /**
         * Number of GC attempts made.
         */
        val attempts: Int
    ) : GCWaitResult() {
        override val isSuccess: Boolean = true
    }

    /**
     * Classloader was not garbage collected within the timeout.
     * This indicates a potential memory leak.
     */
    data class NotCollected(
        /**
         * The plugin ID.
         */
        val pluginId: String,

        /**
         * Time waited in milliseconds.
         */
        val elapsedMs: Long,

        /**
         * Number of GC attempts made.
         */
        val attempts: Int,

        /**
         * Possible sources of the memory leak.
         */
        val possibleLeakSources: List<String>
    ) : GCWaitResult() {
        override val isSuccess: Boolean = false
    }

    /**
     * Whether the classloader was successfully collected.
     */
    abstract val isSuccess: Boolean
}
