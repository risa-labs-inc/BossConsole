package ai.rever.boss.kernel

import ai.rever.boss.ipc.BossIpcServer
import ai.rever.boss.ipc.IpcAddressResolver
import ai.rever.boss.ipc.services.EventBusServiceImpl
import ai.rever.boss.ipc.services.KernelServiceImpl
import ai.rever.boss.ipc.services.StateServiceImpl
import ai.rever.boss.kernel.services.*
import ai.rever.boss.plugin.api.*
import ai.rever.boss.process.ManagedProcess
import ai.rever.boss.process.ProcessConfig
import ai.rever.boss.process.ProcessMode
import ai.rever.boss.process.ProcessMonitor
import ai.rever.boss.process.ProcessRegistry
import ai.rever.boss.process.ProcessSpawner
import ai.rever.boss.process.ProcessType
import ai.rever.boss.process.RestartPolicy
import ai.rever.boss.ipc.proto.ProcessState
import io.grpc.BindableService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Bootstraps the microkernel infrastructure when running in KERNEL mode.
 *
 * In MONOLITH mode, this class does nothing — all code runs in-process as before.
 * In KERNEL mode, it:
 * 1. Starts a gRPC server for child processes to connect to
 * 2. Spawns the orchestrator process (M7 fix — structure in place, classpath pending)
 * 3. Spawns service processes (auth, workspace, etc.)
 * 4. Monitors all child processes via ProcessMonitor, auto-respawns on failure
 * 5. Provides graceful shutdown cascade
 */
class KernelBootstrap(private val mode: ProcessMode = ProcessMode.MONOLITH) {

    companion object {
        /** Singleton instance, set during initialize(). Access from DefaultPlugin via reflection. */
        @Volatile
        var instance: KernelBootstrap? = null
            private set
    }

    private val logger = LoggerFactory.getLogger(KernelBootstrap::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Infrastructure components (null when in MONOLITH mode)
    var ipcServer: BossIpcServer? = null; private set
    var processRegistry: ProcessRegistry? = null; private set
    var processSpawner: ProcessSpawner? = null; private set
    var processMonitor: ProcessMonitor? = null; private set
    var kernelService: KernelServiceImpl? = null; private set
    var eventBusService: EventBusServiceImpl? = null; private set
    var stateService: StateServiceImpl? = null; private set
    var kernelAddress: String? = null; private set

    /**
     * Initialize the kernel infrastructure. No-op in MONOLITH mode.
     */
    fun initialize() {
        if (mode == ProcessMode.MONOLITH) {
            logger.info("Running in MONOLITH mode — microkernel infrastructure disabled")
            return
        }

        logger.info("Initializing KERNEL mode...")

        // Create infrastructure
        kernelAddress = IpcAddressResolver.kernelAddress()
        val registry = ProcessRegistry()
        val spawner = ProcessSpawner(kernelAddress!!)
        processRegistry = registry
        processSpawner = spawner
        processMonitor = ProcessMonitor(registry, scope)

        // Register JVM shutdown hook to kill child processes on exit/crash
        Runtime.getRuntime().addShutdownHook(Thread({
            try {
                logger.info("JVM shutdown hook: cleaning up child processes...")
                processRegistry?.getAllProcesses()?.forEach { process ->
                    try {
                        process.destroy()
                        process.process.waitFor(2, TimeUnit.SECONDS)
                        if (process.isAlive) process.destroyForcibly()
                    } catch (_: Exception) {
                        process.destroyForcibly()
                    }
                }
                ipcServer?.stop()
            } catch (_: Exception) {}
        }, "kernel-shutdown-hook"))

        // Create gRPC services
        kernelService = KernelServiceImpl(
            onProcessRegistered = { id, manifest, _ ->
                logger.info("Process registered via IPC: {}", id)
                registry.updateManifest(id, manifest)
                registry.getProcess(id)?.updateState(ProcessState.PROCESS_STATE_RUNNING)
            },
            onShutdownRequested = { id, force ->
                val process = registry.getProcess(id)
                if (process != null) {
                    if (force) process.destroyForcibly() else process.destroy()
                    // Don't unregister — process monitor will detect the exit and
                    // trigger auto-respawn if restartPolicy == ON_FAILURE
                    true
                } else {
                    false
                }
            },
        )
        eventBusService = EventBusServiceImpl()
        stateService = StateServiceImpl()

        // Start gRPC server
        ipcServer = BossIpcServer(kernelAddress!!)
            .addService(kernelService!!)
            .addService(eventBusService!!)
            .addService(stateService!!)
            .start()

        // Wire IPC event bridge to forward events cross-process (M8 fix)
        val bridge = IpcEventBridgeImpl(eventBusService!!, scope)
        wireEventBridges(bridge)

        // Start process monitor
        processMonitor!!.startGlobalMonitor()

        // Listen for failures: auto-respawn ON_FAILURE processes (C2+M7 fix)
        scope.launch {
            processMonitor!!.failures.collect { failure ->
                val process = registry.getProcess(failure.processId)
                if (process != null && process.config.restartPolicy == RestartPolicy.ON_FAILURE) {
                    val restartCount = registry.getRestartCount(failure.processId)
                    if (restartCount < process.config.maxRestarts) {
                        logger.info(
                            "Auto-respawning process {} (attempt {}/{})",
                            failure.processId, restartCount + 1, process.config.maxRestarts
                        )
                        try {
                            val newProcess = spawner.spawn(process.config)
                            registry.register(failure.processId, newProcess, registry.getManifest(failure.processId))
                            registry.incrementRestartCount(failure.processId)
                        } catch (e: Exception) {
                            logger.error("Auto-respawn failed for {}: {}", failure.processId, e.message)
                        }
                    } else {
                        logger.error(
                            "Process {} exceeded max restarts ({}), not respawning",
                            failure.processId, process.config.maxRestarts
                        )
                    }
                } else {
                    logger.error(
                        "Process failure detected: {} - {} (no auto-respawn: policy={})",
                        failure.processId,
                        failure.errorMessage,
                        process?.config?.restartPolicy,
                    )
                }
            }
        }

        // Spawn child services (M7 fix — structure in place)
        spawnServices(registry, spawner)

        // Store singleton for DefaultPlugin to access via reflection
        instance = this

        logger.info("KERNEL mode initialized. IPC server at: {}", kernelAddress)
    }

    /**
     * Spawn the standard set of microkernel service processes.
     *
     * NOTE: Classpath must point to fat JARs produced by each service module's
     * shadowJar/fatJar task. In development, run:
     *   ./gradlew :boss-orchestrator:shadowJar :boss-service-auth:shadowJar
     * to build them first.
     */
    private fun spawnServices(registry: ProcessRegistry, spawner: ProcessSpawner) {
        val bossDataDir = System.getenv("BOSS_DATA_DIR")
            ?: try {
                ai.rever.boss.plugin.pathutils.BossDirectories.rootDir.absolutePath
            } catch (_: Exception) {
                "${System.getProperty("user.home")}/.boss"
            }

        val orchestratorJar = "$bossDataDir/services/boss-orchestrator-all.jar"
        val authJar = "$bossDataDir/services/boss-service-auth-all.jar"

        spawnIfJarExists(
            spawner, registry,
            ProcessConfig(
                processId = "boss-orchestrator",
                processType = ProcessType.ORCHESTRATOR,
                displayName = "BOSS Orchestrator",
                mainClass = "ai.rever.boss.orchestrator.OrchestratorMainKt",
                classpath = orchestratorJar,
                restartPolicy = RestartPolicy.ON_FAILURE,
                maxRestarts = 5,
            ),
            orchestratorJar,
        )

        spawnIfJarExists(
            spawner, registry,
            ProcessConfig(
                processId = "boss-service-auth",
                processType = ProcessType.SERVICE,
                displayName = "BOSS Auth Service",
                mainClass = "ai.rever.boss.service.auth.AuthServiceMainKt",
                classpath = authJar,
                restartPolicy = RestartPolicy.ON_FAILURE,
                maxRestarts = 3,
            ),
            authJar,
        )

        val masteryOrchestratorJar = "$bossDataDir/services/boss-mastery-orchestrator-all.jar"
        spawnIfJarExists(
            spawner, registry,
            ProcessConfig(
                processId = "boss-mastery-orchestrator",
                processType = ProcessType.SERVICE,
                displayName = "BOSS Mastery Orchestrator",
                mainClass = "ai.rever.boss.mastery.orchestrator.MasteryOrchestratorMainKt",
                classpath = masteryOrchestratorJar,
                restartPolicy = RestartPolicy.ON_FAILURE,
                maxRestarts = 3,
            ),
            masteryOrchestratorJar,
        )

        val workspaceJar = "$bossDataDir/services/boss-service-workspace-all.jar"
        spawnIfJarExists(
            spawner, registry,
            ProcessConfig(
                processId = "boss-service-workspace",
                processType = ProcessType.SERVICE,
                displayName = "BOSS Workspace Service",
                mainClass = "ai.rever.boss.service.workspace.WorkspaceServiceMainKt",
                classpath = workspaceJar,
                restartPolicy = RestartPolicy.ON_FAILURE,
                maxRestarts = 3,
            ),
            workspaceJar,
        )

        val settingsJar = "$bossDataDir/services/boss-service-settings-all.jar"
        spawnIfJarExists(
            spawner, registry,
            ProcessConfig(
                processId = "boss-service-settings",
                processType = ProcessType.SERVICE,
                displayName = "BOSS Settings Service",
                mainClass = "ai.rever.boss.service.settings.SettingsServiceMainKt",
                classpath = settingsJar,
                restartPolicy = RestartPolicy.ON_FAILURE,
                maxRestarts = 3,
            ),
            settingsJar,
        )

        val filesystemJar = "$bossDataDir/services/boss-service-filesystem-all.jar"
        spawnIfJarExists(
            spawner, registry,
            ProcessConfig(
                processId = "boss-service-filesystem",
                processType = ProcessType.SERVICE,
                displayName = "BOSS FileSystem Service",
                mainClass = "ai.rever.boss.service.filesystem.FileSystemServiceMainKt",
                classpath = filesystemJar,
                restartPolicy = RestartPolicy.ON_FAILURE,
                maxRestarts = 3,
            ),
            filesystemJar,
        )

        val terminalJar = "$bossDataDir/services/boss-app-terminal-all.jar"
        spawnIfJarExists(
            spawner, registry,
            ProcessConfig(
                processId = "boss-app-terminal",
                processType = ProcessType.APP,
                displayName = "BOSS Terminal App",
                mainClass = "ai.rever.boss.app.terminal.TerminalServiceMainKt",
                classpath = terminalJar,
                restartPolicy = RestartPolicy.ON_FAILURE,
                maxRestarts = 5,
            ),
            terminalJar,
        )

        val editorJar = "$bossDataDir/services/boss-app-editor-all.jar"
        spawnIfJarExists(
            spawner, registry,
            ProcessConfig(
                processId = "boss-app-editor",
                processType = ProcessType.APP,
                displayName = "BOSS Editor App",
                mainClass = "ai.rever.boss.app.editor.EditorServiceMainKt",
                classpath = editorJar,
                restartPolicy = RestartPolicy.ON_FAILURE,
                maxRestarts = 5,
            ),
            editorJar,
        )

        val browserJar = "$bossDataDir/services/boss-app-browser-all.jar"
        spawnIfJarExists(
            spawner, registry,
            ProcessConfig(
                processId = "boss-app-browser",
                processType = ProcessType.APP,
                displayName = "BOSS Browser App",
                mainClass = "ai.rever.boss.app.browser.BrowserServiceMainKt",
                classpath = browserJar,
                restartPolicy = RestartPolicy.ON_FAILURE,
                maxRestarts = 5,
            ),
            browserJar,
        )
    }

    private fun spawnIfJarExists(
        spawner: ProcessSpawner,
        registry: ProcessRegistry,
        config: ProcessConfig,
        jarPath: String,
    ) {
        if (java.io.File(jarPath).exists()) {
            try {
                val process: ManagedProcess = spawner.spawn(config)
                registry.register(config.processId, process)
                processMonitor?.startMonitoring(config.processId)
                logger.info("Spawned service: {} at {}", config.processId, jarPath)
            } catch (e: Exception) {
                logger.warn("Failed to spawn {}: {}", config.processId, e.message)
            }
        } else {
            logger.info(
                "Service JAR not found for {} at {} — skipping spawn (build fat JARs first)",
                config.processId, jarPath
            )
        }
    }

    /**
     * Wire the IPC event bridge to all 12 event buses so events are forwarded
     * cross-process in KERNEL mode (M8 fix).
     */
    private fun wireEventBridges(bridge: IpcEventBridgeImpl) {
        ai.rever.boss.components.events.DashboardEventBus.ipcBridge = bridge
        ai.rever.boss.components.events.WorkspaceEventBus.ipcBridge = bridge
        ai.rever.boss.components.events.KeyboardEventBus.ipcBridge = bridge
        ai.rever.boss.components.events.NavigationTargetBus.ipcBridge = bridge
        ai.rever.boss.components.events.URLEventBus.ipcBridge = bridge
        ai.rever.boss.components.events.GitTerminalEventBus.ipcBridge = bridge
        ai.rever.boss.components.events.PanelEventBus.ipcBridge = bridge
        ai.rever.boss.components.events.RunEventBus.ipcBridge = bridge
        ai.rever.boss.components.events.RunnerTerminalEventBus.ipcBridge = bridge
        ai.rever.boss.components.events.FileEventBus.ipcBridge = bridge
        ai.rever.boss.components.events.TerminalEventBus.ipcBridge = bridge
        ai.rever.boss.components.events.TerminalLinkEventBus.ipcBridge = bridge
        logger.info("IPC event bridges wired to all 12 event buses")
    }

    /**
     * Shut down all child processes and the kernel server.
     * Called during application shutdown.
     */
    fun shutdown() {
        if (mode == ProcessMode.MONOLITH) return

        logger.info("Shutting down KERNEL mode...")

        // 1. Stop process monitor
        processMonitor?.stopAll()

        // 2. Clear event bridges
        wireEventBridges(IpcEventBridgeImpl(null, scope))

        // 3. Shut down all child processes (apps first, then services)
        processRegistry?.getProcessesByType(ProcessType.PLUGIN)?.forEach { it.destroy() }
        processRegistry?.getProcessesByType(ProcessType.APP)?.forEach { it.destroy() }
        processRegistry?.getProcessesByType(ProcessType.ORCHESTRATOR)?.forEach { it.destroy() }
        processRegistry?.getProcessesByType(ProcessType.SERVICE)?.forEach { it.destroy() }

        // 4. Wait for graceful shutdown with 2s per-process timeout
        processRegistry?.getAllProcesses()?.forEach { process ->
            try {
                if (!process.process.waitFor(2, TimeUnit.SECONDS)) {
                    logger.warn("Process did not exit in 2s: {}", process.config.processId)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        // 5. Force kill any remaining
        processRegistry?.getAllProcesses()?.filter { it.isAlive }?.forEach {
            logger.warn("Force-killing process: {}", it.config.processId)
            it.destroyForcibly()
        }

        // 6. Stop IPC server
        ipcServer?.stop()

        // 7. Cancel scope
        scope.cancel()

        logger.info("KERNEL mode shut down complete")
    }

    val isKernelMode: Boolean get() = mode == ProcessMode.KERNEL

    /**
     * Register the 15 kernel-side gRPC services that expose in-process providers
     * to out-of-process plugin child processes.
     *
     * Called from DefaultPlugin after all providers are initialized, since the
     * service bridges wrap the same provider instances used by in-process plugins.
     */
    fun registerPluginServices(
        performanceDataProvider: PerformanceDataProvider? = null,
        downloadDataProvider: DownloadDataProvider? = null,
        gitDataProvider: GitDataProvider? = null,
        logDataProvider: LogDataProvider? = null,
        activeTabsProvider: ActiveTabsProvider? = null,
        secretDataProvider: SecretDataProvider? = null,
        supabaseDataProvider: SupabaseDataProvider? = null,
        splitViewOperations: SplitViewOperations? = null,
        contextMenuProvider: ContextMenuProvider? = null,
        runConfigurationDataProvider: RunConfigurationDataProvider? = null,
        panelEventProvider: PanelEventProvider? = null,
        roleManagementProvider: RoleManagementProvider? = null,
        directoryPickerProvider: DirectoryPickerProvider? = null,
        projectDataProvider: ProjectDataProvider? = null,
        notificationProvider: NotificationProvider? = null,
    ) {
        if (mode == ProcessMode.MONOLITH || ipcServer == null) return

        val services = mutableListOf<BindableService>()

        performanceDataProvider?.let { services += PerformanceServiceBridge(it) }
        downloadDataProvider?.let { services += DownloadServiceBridge(it) }
        gitDataProvider?.let { services += GitServiceBridge(it) }
        logDataProvider?.let { services += LogServiceBridge(it) }
        activeTabsProvider?.let { services += ActiveTabsServiceBridge(it) }
        secretDataProvider?.let { services += SecretServiceBridge(it) }
        supabaseDataProvider?.let { services += SupabaseServiceBridge(it) }
        splitViewOperations?.let { services += SplitViewServiceBridge(it) }
        contextMenuProvider?.let { services += ContextMenuServiceBridge(it) }
        runConfigurationDataProvider?.let { services += RunConfigServiceBridge(it) }
        panelEventProvider?.let { services += PanelEventServiceBridge(it) }
        roleManagementProvider?.let { services += RoleManagementServiceBridge(it) }
        directoryPickerProvider?.let { services += DirectoryPickerServiceBridge(it) }
        projectDataProvider?.let { services += ProjectDataServiceBridge(it) }
        notificationProvider?.let { services += NotificationServiceBridge(it) }

        services.forEach { service ->
            ipcServer!!.addService(service)
        }

        logger.info("Registered {} plugin gRPC services on kernel IPC server", services.size)
    }
}
