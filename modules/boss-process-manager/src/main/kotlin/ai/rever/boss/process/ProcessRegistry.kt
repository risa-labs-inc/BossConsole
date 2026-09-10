package ai.rever.boss.process

import ai.rever.boss.ipc.proto.PluginCapability
import ai.rever.boss.ipc.proto.ProcessManifest
import ai.rever.boss.ipc.proto.ProcessState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry tracking all child processes managed by the kernel.
 * Thread-safe: registrations happen from IPC handler threads, lookups from UI thread.
 */
class ProcessRegistry {
    private val logger = LoggerFactory.getLogger(ProcessRegistry::class.java)
    private val processes = ConcurrentHashMap<String, ManagedProcess>()
    private val manifests = ConcurrentHashMap<String, ProcessManifest>()
    private val restartCounts = ConcurrentHashMap<String, Int>()

    private val _processCount = MutableStateFlow(0)
    val processCount: StateFlow<Int> = _processCount.asStateFlow()

    fun register(
        id: String,
        process: ManagedProcess,
        manifest: ProcessManifest? = null,
    ) {
        val replaced = processes.put(id, process)
        // Replacing a *dead* handle is normal - that is what a respawn does. Replacing a live one
        // means two callers picked the same process id, and the evicted child becomes invisible to
        // the shutdown hook while still running, i.e. an orphan. Window-owned plugin spawners
        // now use window-scoped identities; retain this diagnostic for any other colliding caller.
        if (replaced != null && replaced !== process && replaced.isAlive) {
            logger.warn(
                "Registered id={} replaced a LIVE handle (pid={} -> {}); the evicted child will not be reaped",
                id,
                replaced.pid,
                process.pid,
            )
        }
        manifest?.let { manifests[id] = it }
        _processCount.value = processes.size
        logger.info("Registered process: id={}, type={}, pid={}", id, process.config.processType, process.pid)
    }

    fun unregister(id: String) {
        processes.remove(id)
        manifests.remove(id)
        restartCounts.remove(id)
        _processCount.value = processes.size
        logger.info("Unregistered process: id={}", id)
    }

    /**
     * Remove [id], but only while it still maps to [process]. Returns whether it did.
     *
     * For callers acting on a process handle they read earlier: a plain [unregister] removes by id,
     * so a respawn that re-registered the same id in between would have its live replacement
     * dropped instead - and since this registry is what the shutdown hook reaps, dropping a live
     * child is exactly how one becomes an orphan.
     */
    fun unregisterIfSame(
        id: String,
        process: ManagedProcess,
    ): Boolean {
        if (!processes.remove(id, process)) return false
        manifests.remove(id)
        restartCounts.remove(id)
        _processCount.value = processes.size
        logger.info("Unregistered process: id={}", id)
        return true
    }

    fun getProcess(id: String): ManagedProcess? = processes[id]

    fun getManifest(id: String): ProcessManifest? = manifests[id]

    fun updateManifest(
        id: String,
        manifest: ProcessManifest,
    ) {
        manifests[id] = manifest
    }

    fun getAllProcesses(): List<ManagedProcess> = processes.values.toList()

    fun getProcessesByType(type: ProcessType): List<ManagedProcess> = processes.values.filter { it.config.processType == type }

    fun getProcessesByState(state: ProcessState): List<ManagedProcess> = processes.values.filter { it.state.value == state }

    /**
     * Get all capabilities aggregated from all registered process manifests.
     * Used by the Mastery orchestrator to discover available actions.
     */
    fun getCapabilities(): List<PluginCapability> = manifests.values.flatMap { it.capabilitiesList }

    /**
     * Find a specific capability by plugin ID and action name.
     */
    fun findCapability(
        pluginId: String,
        action: String,
    ): PluginCapability? = manifests[pluginId]?.capabilitiesList?.find { it.action == action }

    fun getRestartCount(id: String): Int = restartCounts[id] ?: 0

    fun incrementRestartCount(id: String): Int {
        val count = restartCounts.compute(id) { _, v -> (v ?: 0) + 1 }!!
        logger.info("Process {} restart count: {}", id, count)
        return count
    }

    fun resetRestartCount(id: String) {
        restartCounts.remove(id)
    }

    fun contains(id: String): Boolean = processes.containsKey(id)

    val size: Int get() = processes.size
}
