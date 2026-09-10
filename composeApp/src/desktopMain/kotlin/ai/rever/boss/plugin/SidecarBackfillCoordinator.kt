package ai.rever.boss.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates authenticated signature backfill for installed system-plugin JARs.
 * Authentication avoids the store permission gate; update completion wakes deferred JARs.
 * Each completed attempt is counted even when unsigned: getDownloadUrl records a download,
 * so ordinary failures must not create a retry loop. A signature-only store route would
 * remove that cost (see #108). Cancellation and lost authentication may retry.
 * The supplied scope must dispatch file probes and persistence onto an I/O dispatcher.
 *
 * Split out of [PluginStoreSetup] (BossConsole#447 step 7): already a standalone class
 * with its own state and lifecycle, driven entirely by the constructor lambdas below -
 * being nested at the bottom of a 2,000-line file was purely accidental. Same package as
 * before, so no caller or test needed to change.
 */
internal class SidecarBackfillCoordinator(
    private val scope: CoroutineScope,
    private val sidecarExists: (File) -> Boolean,
    private val updateInFlight: (String) -> Boolean,
    private val persist: suspend (File) -> Unit,
) {
    private data class JarStamp(
        val path: String,
        val length: Long,
        val modifiedAt: Long,
    )

    private data class PendingJar(
        val pluginId: String,
        val jarFile: File,
        val stamp: JarStamp,
    )

    private data class AttemptKey(
        val pluginId: String,
        val stamp: JarStamp,
    )

    private val pending = ConcurrentHashMap<String, PendingJar>()
    private val attempted = ConcurrentHashMap.newKeySet<AttemptKey>()
    private val drainMutex = Mutex()
    private val authenticationLosses = AtomicLong()

    @Volatile
    private var authenticated = false

    fun setAuthenticated(available: Boolean) {
        if (!available) authenticationLosses.incrementAndGet()
        authenticated = available
        if (available) requestDrain()
    }

    fun enqueue(
        pluginId: String,
        jarFile: File,
    ) {
        if (sidecarExists(jarFile)) return
        val stamp = stampOf(jarFile) ?: return
        pending[pluginId] = PendingJar(pluginId, jarFile, stamp)
        requestDrain()
    }

    fun onUpdateCheckCompleted(pluginId: String) {
        if (pending.containsKey(pluginId)) requestDrain()
    }

    private fun requestDrain() {
        if (!authenticated) return
        scope.launch {
            drainMutex.withLock { drainOnce() }
        }
    }

    private suspend fun drainOnce() {
        if (!authenticated) return

        pending.entries.toList().forEach { (_, entry) ->
            if (!authenticated) return
            if (!isCurrentAndUnsigned(entry)) {
                pending.remove(entry.pluginId, entry)
                return@forEach
            }
            if (updateInFlight(entry.pluginId)) return@forEach

            val attemptKey = AttemptKey(entry.pluginId, entry.stamp)
            if (!attempted.add(attemptKey)) {
                pending.remove(entry.pluginId, entry)
                return@forEach
            }
            if (!pending.remove(entry.pluginId, entry)) return@forEach

            val authenticationGeneration = authenticationLosses.get()
            try {
                persist(entry.jarFile)
                val lostAuthentication = !authenticated || authenticationGeneration != authenticationLosses.get()
                if (lostAuthentication && !sidecarExists(entry.jarFile)) {
                    attempted.remove(attemptKey)
                    pending.putIfAbsent(entry.pluginId, entry)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                attempted.remove(attemptKey)
                pending.putIfAbsent(entry.pluginId, entry)
                throw cancelled
            } catch (_: Exception) {
                // Unexpected failures also consume the attempt, preventing wakeup-driven retries.
            }
        }
    }

    private fun isCurrentAndUnsigned(entry: PendingJar): Boolean {
        val currentStamp = stampOf(entry.jarFile)
        return currentStamp == entry.stamp && !sidecarExists(entry.jarFile)
    }

    private fun stampOf(jarFile: File): JarStamp? {
        if (!jarFile.exists()) return null
        return JarStamp(jarFile.absolutePath, jarFile.length(), jarFile.lastModified())
    }
}
