package ai.rever.boss.process

import ai.rever.boss.ipc.proto.ProcessManifest
import ai.rever.boss.ipc.proto.PluginCapability
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

    fun register(id: String, process: ManagedProcess, manifest: ProcessManifest? = null) {
        processes[id] = process
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

    fun getProcess(id: String): ManagedProcess? = processes[id]

    fun getManifest(id: String): ProcessManifest? = manifests[id]

    fun updateManifest(id: String, manifest: ProcessManifest) {
        manifests[id] = manifest
    }

    fun getAllProcesses(): List<ManagedProcess> = processes.values.toList()

    fun getProcessesByType(type: ProcessType): List<ManagedProcess> =
        processes.values.filter { it.config.processType == type }

    fun getProcessesByState(state: ProcessState): List<ManagedProcess> =
        processes.values.filter { it.state.value == state }

    /**
     * Get all capabilities aggregated from all registered process manifests.
     * Used by the Mastery orchestrator to discover available actions.
     */
    fun getCapabilities(): List<PluginCapability> =
        manifests.values.flatMap { it.capabilitiesList }

    /**
     * Find a specific capability by plugin ID and action name.
     */
    fun findCapability(pluginId: String, action: String): PluginCapability? =
        manifests[pluginId]?.capabilitiesList?.find { it.action == action }

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
