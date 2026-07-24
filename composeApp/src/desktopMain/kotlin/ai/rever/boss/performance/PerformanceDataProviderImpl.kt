package ai.rever.boss.performance

import ai.rever.boss.plugin.api.ChildProcessData
import ai.rever.boss.plugin.api.GcCollectorData
import ai.rever.boss.plugin.api.MemoryPoolData
import ai.rever.boss.plugin.api.PerformanceDataProvider
import ai.rever.boss.plugin.api.PerformanceSettingsData
import ai.rever.boss.plugin.api.PerformanceSnapshotData
import ai.rever.boss.plugin.api.ThreadData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Implementation of PerformanceDataProvider that adapts PerformanceMonitor
 * and PerformanceSettingsManager to the plugin interface.
 *
 * This adapter converts the internal performance types to the plugin API types,
 * allowing the Performance panel to be extracted as a separate module.
 */
class PerformanceDataProviderImpl : PerformanceDataProvider {
    private val logger = LoggerFactory.getLogger(PerformanceDataProviderImpl::class.java)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _currentSnapshot = MutableStateFlow<PerformanceSnapshotData?>(null)
    override val currentSnapshot: StateFlow<PerformanceSnapshotData?> = _currentSnapshot.asStateFlow()

    private val _history = MutableStateFlow<List<PerformanceSnapshotData>>(emptyList())

    /** Cache child process data to avoid spawning ps every sample tick. Refresh every 5s. */
    @Volatile private var cachedChildProcesses: List<ChildProcessData> = emptyList()

    @Volatile private var childProcessCacheTime: Long = 0L
    private val childProcessCacheIntervalMs = 5_000L
    override val history: StateFlow<List<PerformanceSnapshotData>> = _history.asStateFlow()

    private val _settings = MutableStateFlow(PerformanceSettingsData())
    override val settings: StateFlow<PerformanceSettingsData> = _settings.asStateFlow()

    init {
        // Observe PerformanceMonitor.currentSnapshot and convert to PerformanceSnapshotData
        scope.launch {
            PerformanceMonitor.currentSnapshot.collect { snapshot ->
                _currentSnapshot.value = snapshot?.toSnapshotData()
            }
        }

        // Observe PerformanceMonitor.history and convert to List<PerformanceSnapshotData>
        scope.launch {
            PerformanceMonitor.history.collect { snapshots ->
                _history.value = snapshots.map { it.toSnapshotData() }
            }
        }

        // Observe PerformanceSettingsManager.currentSettings and convert to PerformanceSettingsData
        scope.launch {
            PerformanceSettingsManager.currentSettings.collect { settings ->
                _settings.value = settings.toSettingsData()
            }
        }
    }

    override fun requestGC() {
        PerformanceMonitor.requestGC()
    }

    override suspend fun exportMetrics(): Result<String> = PerformanceMonitor.exportMetrics()

    override suspend fun updateSettings(settings: PerformanceSettingsData) {
        val internalSettings =
            PerformanceSettings(
                enabled = settings.enabled,
                showIndicator = settings.showIndicator,
                memoryWarningThresholdPercent = settings.memoryWarningThresholdPercent,
                memoryCriticalThresholdPercent = settings.memoryCriticalThresholdPercent,
                cpuWarningThresholdPercent = settings.cpuWarningThresholdPercent,
                cpuCriticalThresholdPercent = settings.cpuCriticalThresholdPercent,
                memorySampleIntervalMs = settings.memorySampleIntervalMs,
                cpuSampleIntervalMs = settings.cpuSampleIntervalMs,
                historyRetentionMinutes = settings.historyRetentionMinutes,
                pluginJvmHeapMb = settings.pluginJvmHeapMb,
                pluginJvmInitialHeapMb = settings.pluginJvmInitialHeapMb,
            )
        PerformanceSettingsManager.updateSettings(internalSettings)
    }

    /**
     * Convert internal PerformanceSnapshot to plugin PerformanceSnapshotData.
     */
    private fun PerformanceSnapshot.toSnapshotData(): PerformanceSnapshotData =
        PerformanceSnapshotData(
            timestamp = timestamp,
            heapUsedBytes = memory.heapUsedBytes,
            heapMaxBytes = memory.heapMaxBytes,
            heapUsagePercent = memory.heapUsagePercent,
            nonHeapUsedBytes = memory.nonHeapUsedBytes,
            processLoadPercent = cpu.processLoadPercent,
            systemLoadPercent = cpu.systemLoadPercent,
            activeThreadCount = cpu.activeThreadCount,
            gcCollectionCount = gc.collectionCount,
            gcCollectionTimeMs = gc.collectionTimeMs,
            browserTabCount = resources.browserTabCount,
            terminalCount = resources.terminalCount,
            editorTabCount = resources.editorTabCount,
            panelCount = resources.panelCount,
            windowCount = resources.windowCount,
            memoryPools =
                memory.memoryPools.map { pool ->
                    MemoryPoolData(
                        name = pool.name,
                        type = pool.type,
                        usedBytes = pool.usedBytes,
                        maxBytes = pool.maxBytes,
                        committedBytes = pool.committedBytes,
                    )
                },
            threads =
                cpu.threads.map { thread ->
                    ThreadData(
                        id = thread.id,
                        name = thread.name,
                        state = thread.state,
                        cpuTimeMs = thread.cpuTimeMs,
                        userTimeMs = thread.userTimeMs,
                        blockedCount = thread.blockedCount,
                        waitedCount = thread.waitedCount,
                    )
                },
            gcCollectors =
                gc.gcCollectors.map { collector ->
                    GcCollectorData(
                        name = collector.name,
                        collectionCount = collector.collectionCount,
                        collectionTimeMs = collector.collectionTimeMs,
                    )
                },
            childProcesses = collectChildProcesses(),
        )

    /**
     * Collect metrics from out-of-process plugin child JVMs.
     * Uses a 5-second cache to avoid spawning ps processes every sample tick.
     */
    private fun collectChildProcesses(): List<ChildProcessData> {
        val now = System.currentTimeMillis()
        if (now - childProcessCacheTime < childProcessCacheIntervalMs) {
            // Update uptime on cached entries without re-querying OS
            return cachedChildProcesses.map { it.copy(uptimeMs = it.uptimeMs + (now - childProcessCacheTime)) }
        }

        val result = collectViaProcessHandle().ifEmpty { collectViaKernelRegistry() }
        cachedChildProcesses = result
        childProcessCacheTime = now
        return result
    }

    /**
     * Find plugin child JVMs via Java ProcessHandle API.
     * Searches all descendants of the current process for PluginProcessMainKt.
     * Enriches with uptime from startInstant() and RSS memory from OS query.
     */
    private fun collectViaProcessHandle(): List<ChildProcessData> {
        return try {
            val current = ProcessHandle.current()
            val descendants = current.descendants().toList()
            val now = Instant.now()

            // Batch query RSS and thread counts for all PIDs via ps
            val pids =
                descendants.mapNotNull { h ->
                    if (h
                            .info()
                            .commandLine()
                            .orElse("")
                            .contains("PluginProcessMainKt")
                    ) {
                        h.pid()
                    } else {
                        null
                    }
                }
            val processMetrics = if (pids.isNotEmpty()) queryProcessMetrics(pids) else emptyMap()

            descendants.mapNotNull { handle ->
                try {
                    val cmdLine = handle.info().commandLine().orElse("")
                    if (!cmdLine.contains("PluginProcessMainKt")) return@mapNotNull null

                    // Extract plugin ID from classpath: ...boss-plugin-{name}-{version}.jar
                    val pluginJarRegex = Regex("""boss-plugin-([a-z\-]+)-\d+""")
                    val match = pluginJarRegex.find(cmdLine)
                    val pluginId = match?.groupValues?.get(1) ?: "unknown-${handle.pid()}"
                    val displayName =
                        pluginId.split("-").joinToString(" ") { part ->
                            part.replaceFirstChar { it.uppercase() }
                        }

                    // Compute uptime from process start time
                    val startInstant = handle.info().startInstant().orElse(null)
                    val uptimeMs =
                        if (startInstant != null) {
                            java.time.Duration
                                .between(startInstant, now)
                                .toMillis()
                        } else {
                            0L
                        }

                    // Get OS-level metrics (RSS memory, thread count) from ps query
                    val metrics = processMetrics[handle.pid()]

                    val configuredHeapMb = PerformanceSettingsManager.currentSettings.value.pluginJvmHeapMb

                    ChildProcessData(
                        processId = "plugin-$pluginId",
                        pluginId = pluginId,
                        displayName = displayName,
                        pid = handle.pid(),
                        state = if (handle.isAlive) "RUNNING" else "STOPPED",
                        heapUsedBytes = metrics?.rssBytes ?: 0L,
                        heapMaxBytes = configuredHeapMb.toLong() * 1024 * 1024,
                        activeThreads = metrics?.threadCount ?: 0,
                        uptimeMs = uptimeMs,
                    )
                } catch (e: Exception) {
                    logger.warn("Failed to read process handle {}: {}", handle.pid(), e.message)
                    null
                }
            }
        } catch (e: Exception) {
            logger.warn("ProcessHandle approach failed: {}", e.message)
            emptyList()
        }
    }

    private data class OsProcessMetrics(
        val rssBytes: Long,
        val threadCount: Int,
    )

    /**
     * Query RSS memory (bytes) and thread count for a batch of PIDs using two `ps` calls.
     */
    private fun queryProcessMetrics(pids: List<Long>): Map<Long, OsProcessMetrics> =
        try {
            val pidStr = pids.joinToString(",")

            // Single ps call for RSS (KB)
            val rssProcess =
                ProcessBuilder("ps", "-o", "pid=,rss=", "-p", pidStr)
                    .redirectErrorStream(true)
                    .start()
            val rssOutput = rssProcess.inputStream.bufferedReader().readText()
            rssProcess.waitFor()

            val rssMap = mutableMapOf<Long, Long>()
            for (line in rssOutput.lines()) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2) {
                    val pid = parts[0].toLongOrNull() ?: continue
                    val rssKb = parts[1].toLongOrNull() ?: continue
                    rssMap[pid] = rssKb * 1024
                }
            }

            // Single ps -M call for all PIDs, count thread lines per PID
            // macOS ps -M ignores -o formatting, so parse PID from output columns
            val threadProcess =
                ProcessBuilder("ps", "-M", "-p", pidStr)
                    .redirectErrorStream(true)
                    .start()
            val threadOutput = threadProcess.inputStream.bufferedReader().readText()
            threadProcess.waitFor()

            val threadMap = mutableMapOf<Long, Int>()
            for (line in threadOutput.lines().drop(1)) { // skip header
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val tokens = trimmed.split(Regex("\\s+"))
                // PID is first token if numeric (thread lines), second token if first is username
                val pid =
                    tokens[0].toLongOrNull()
                        ?: tokens.getOrNull(1)?.toLongOrNull()
                        ?: continue
                threadMap[pid] = (threadMap[pid] ?: 0) + 1
            }

            pids.associateWith { pid ->
                OsProcessMetrics(
                    rssBytes = rssMap[pid] ?: 0L,
                    threadCount = threadMap[pid] ?: 0,
                )
            }
        } catch (e: Exception) {
            logger.debug("Failed to query process metrics: {}", e.message)
            emptyMap()
        }

    /**
     * Fallback: query kernel ProcessRegistry via reflection.
     */
    private fun collectViaKernelRegistry(): List<ChildProcessData> {
        return try {
            val bootstrapCls = Class.forName("ai.rever.boss.kernel.KernelBootstrap")
            val companionCls = Class.forName("ai.rever.boss.kernel.KernelBootstrap\$Companion")
            val companion = bootstrapCls.getDeclaredField("Companion").get(null)
            val getInstance = companionCls.getMethod("getInstance")
            val kernel = getInstance.invoke(companion)
            if (kernel == null) {
                logger.debug("KernelBootstrap.getInstance() returned null — not in KERNEL mode")
                return emptyList()
            }

            val registry = bootstrapCls.getMethod("getProcessRegistry").invoke(kernel)
            if (registry == null) {
                logger.warn("KernelBootstrap.getProcessRegistry() returned null")
                return emptyList()
            }
            val registryCls = registry::class.java
            logger.debug("ProcessRegistry class: {}", registryCls.name)

            @Suppress("UNCHECKED_CAST")
            val processes =
                try {
                    val protoTypeCls = Class.forName("ai.rever.boss.process.ProcessType")
                    val pluginType = protoTypeCls.enumConstants?.find { it.toString() == "PLUGIN" }
                    if (pluginType != null) {
                        registryCls
                            .getMethod("getProcessesByType", protoTypeCls)
                            .invoke(registry, pluginType) as? List<*> ?: emptyList<Any>()
                    } else {
                        logger.warn("PLUGIN enum value not found in ProcessType")
                        registryCls.getMethod("getAllProcesses").invoke(registry) as? List<*> ?: emptyList<Any>()
                    }
                } catch (e: Exception) {
                    logger.warn("getProcessesByType failed, trying getAllProcesses: {}", e.message)
                    registryCls.getMethod("getAllProcesses").invoke(registry) as? List<*> ?: emptyList<Any>()
                }

            logger.debug("ProcessRegistry returned {} processes", processes.size)

            processes.mapNotNull { process ->
                try {
                    val processCls = process!!::class.java
                    val config = processCls.getMethod("getConfig").invoke(process)
                    val configCls = config::class.java

                    val processId = configCls.getMethod("getProcessId").invoke(config) as String
                    val displayName = configCls.getMethod("getDisplayName").invoke(config) as String
                    val pid =
                        try {
                            processCls.getMethod("getPid").invoke(process) as Long
                        } catch (_: Exception) {
                            -1L
                        }
                    val isAlive =
                        try {
                            processCls.getMethod("isAlive").invoke(process) as Boolean
                        } catch (_: Exception) {
                            false
                        }

                    ChildProcessData(
                        processId = processId,
                        pluginId = processId.removePrefix("plugin-"),
                        displayName = displayName,
                        pid = pid,
                        state = if (isAlive) "RUNNING" else "STOPPED",
                    )
                } catch (e: Exception) {
                    logger.warn("Failed to read process entry: {}", e.message)
                    null
                }
            }
        } catch (e: ClassNotFoundException) {
            logger.debug("Kernel classes not available: {}", e.message)
            emptyList()
        } catch (e: Exception) {
            logger.warn("Kernel registry reflection failed: {}", e.message, e)
            emptyList()
        }
    }

    /**
     * Convert internal PerformanceSettings to plugin PerformanceSettingsData.
     */
    private fun PerformanceSettings.toSettingsData(): PerformanceSettingsData =
        PerformanceSettingsData(
            enabled = enabled,
            showIndicator = showIndicator,
            memoryWarningThresholdPercent = memoryWarningThresholdPercent,
            memoryCriticalThresholdPercent = memoryCriticalThresholdPercent,
            cpuWarningThresholdPercent = cpuWarningThresholdPercent,
            cpuCriticalThresholdPercent = cpuCriticalThresholdPercent,
            memorySampleIntervalMs = memorySampleIntervalMs,
            cpuSampleIntervalMs = cpuSampleIntervalMs,
            historyRetentionMinutes = historyRetentionMinutes,
            pluginJvmHeapMb = pluginJvmHeapMb,
            pluginJvmInitialHeapMb = pluginJvmInitialHeapMb,
        )
}
