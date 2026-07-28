package ai.rever.boss.kernel

import ai.rever.boss.ipc.BossIpcClient
import ai.rever.boss.ipc.BossIpcServer
import ai.rever.boss.ipc.IpcAddressResolver
import ai.rever.boss.ipc.proto.OrchestratorServiceGrpcKt
import ai.rever.boss.ipc.proto.ProcessFailureReport
import ai.rever.boss.ipc.proto.ProcessState
import ai.rever.boss.ipc.proto.RepairAction
import ai.rever.boss.ipc.proto.RepairStrategy
import ai.rever.boss.ipc.services.EventBusServiceImpl
import ai.rever.boss.ipc.services.KernelServiceImpl
import ai.rever.boss.ipc.services.StateServiceImpl
import ai.rever.boss.kernel.services.*
import ai.rever.boss.kernel.ui.RemoteUiSurfaceRegistry
import ai.rever.boss.plugin.api.*
import ai.rever.boss.process.ManagedProcess
import ai.rever.boss.process.ProcessConfig
import ai.rever.boss.process.ProcessFailure
import ai.rever.boss.process.ProcessMode
import ai.rever.boss.process.ProcessMonitor
import ai.rever.boss.process.ProcessRegistry
import ai.rever.boss.process.ProcessSpawner
import ai.rever.boss.process.ProcessType
import ai.rever.boss.process.RestartPolicy
import io.grpc.BindableService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Find [jarName] in [dir], tolerating the version every `fatJar` task actually puts in the name.
 *
 * The kernel has always looked for `boss-orchestrator-all.jar`, and no service module strips its
 * version — `fatJar` produces `boss-orchestrator-1.0.0-all.jar`. Nothing ever matched, which is
 * part of why no service has ever spawned. Rather than renaming nine artifacts, accept both: the
 * exact name first, then the newest versioned sibling.
 */
internal fun serviceJarIn(
    dir: File,
    jarName: String,
): File? {
    if (!dir.isDirectory) return null

    val prefix = jarName.removeSuffix("-all.jar")
    return File(dir, jarName).takeIf { it.isFile }
        ?: dir
            .listFiles { f -> f.isFile && f.name.startsWith("$prefix-") && f.name.endsWith("-all.jar") }
            ?.maxByOrNull { it.lastModified() }
}

/**
 * Where a service's fat JAR actually is, preferring an operator-supplied copy.
 *
 * Three places, in order: `$BOSS_DATA_DIR/services` so an operator can drop in a build of
 * their own; the app's bundled resources, which is where packaged builds ship them; and the
 * module build directory, so `./gradlew run` works straight after `:<service>:fatJar` without
 * anyone copying files around. Nothing is copied — a staged duplicate is one more thing to go
 * stale.
 *
 * Falls back to the `$BOSS_DATA_DIR` path when none exists, so [spawnIfJarExists] reports the
 * location an operator would populate.
 */
private fun resolveServiceJar(
    bossDataDir: String,
    jarName: String,
): String {
    // "boss-orchestrator-all.jar" is built by module "boss-orchestrator" under modules/.
    val moduleName = jarName.removeSuffix("-all.jar")
    val installedDir = File("$bossDataDir/services")
    val searchDirs =
        listOfNotNull(
            installedDir,
            System.getProperty("compose.application.resources.dir")?.let { File(it, "services") },
            File("modules/$moduleName/build/libs"),
            File("../modules/$moduleName/build/libs"),
        )

    return searchDirs
        .firstNotNullOfOrNull { serviceJarIn(it, jarName) }
        ?.absolutePath
        ?: File(installedDir, jarName).path
}

private val notifyLogger = LoggerFactory.getLogger("KernelRepairNotice")

/**
 * Surface a repair the operator has to decide on, using the same toast plugin crashes use.
 *
 * Logged as well as shown: a service can die before the window exists, and `showMessage` posts to a
 * flow that nobody is collecting yet — the notice would otherwise be lost with no trace.
 */
private fun notifyOperator(
    processId: String,
    summary: String,
) {
    notifyLogger.warn("Repair for {} needs operator attention: {}", processId, summary)
    ai.rever.boss.components.bars.horizontal.StatusMessageManager
        .showMessage("$processId needs attention: $summary", durationMs = 10_000)
}

/** What the kernel does about a crashed child once the orchestrator has had its say. */
internal sealed interface Recovery {
    /** Bring it back as configured. Also the answer when there is no advice to act on. */
    data object Respawn : Recovery

    /** Bring it back with different JVM args — the analyzer's answer to an OOM. */
    data class RespawnTuned(
        val jvmArgs: List<String>,
    ) : Recovery

    /** Tell the operator, then bring it back anyway. */
    data class NotifyAndRespawn(
        val reason: String,
    ) : Recovery
}

/**
 * Turn repair advice into what the kernel will actually do.
 *
 * Pure, because this is the part with judgement in it and the rest is process plumbing. A null
 * [action] means the orchestrator could not be asked — it is the fallback path, and it must come
 * out as the plain respawn the kernel did before any of this existed.
 *
 * Every branch ends in the process coming back. A repair that needs a human still restarts it
 * first: withholding recovery until someone clicks would leave an operator with a dead service and
 * a notification, which is worse than what they had.
 */
internal fun recoveryFor(action: RepairAction?): Recovery {
    if (action == null) return Recovery.Respawn

    val needsOperator =
        action.requiresUserApproval || action.strategy == RepairStrategy.REPAIR_STRATEGY_ESCALATE

    return when {
        needsOperator -> {
            Recovery.NotifyAndRespawn(action.description.ifBlank { action.strategy.name })
        }

        // RESET_STATE is a restart on this side too: the orchestrator has already named the
        // snapshot to restore, and the kernel's job is to bring the process back up.
        action.strategy == RepairStrategy.REPAIR_STRATEGY_RESTART ||
            action.strategy == RepairStrategy.REPAIR_STRATEGY_RESET_STATE -> {
            Recovery.Respawn
        }

        action.strategy == RepairStrategy.REPAIR_STRATEGY_RESTART_TUNED -> {
            action.restart.jvmArgsOverrideList
                .takeIf { it.isNotEmpty() }
                ?.let { Recovery.RespawnTuned(it) }
                ?: Recovery.Respawn
        }

        // PATCH_CONFIG / PATCH_SOURCE reach here only if they ever stop requiring approval.
        // Nothing on this side applies a patch, so restore service and let the operator know.
        else -> {
            Recovery.NotifyAndRespawn(action.description.ifBlank { action.strategy.name })
        }
    }
}

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
class KernelBootstrap(
    private val mode: ProcessMode = ProcessMode.MONOLITH,
) {
    companion object {
        /** Singleton instance, set during initialize(). Access from DefaultPlugin via reflection. */
        @Volatile
        var instance: KernelBootstrap? = null
            private set

        /** Process id of the self-healing orchestrator, as spawned by [spawnServices]. */
        const val ORCHESTRATOR_PROCESS_ID = "boss-orchestrator"

        /**
         * How long the kernel waits for repair advice before recovering on its own.
         *
         * Every crash pays this at worst, and the fallback restores exactly the behaviour that
         * existed before the orchestrator was consulted — so it is set to lose no meaningful time
         * when the orchestrator is wedged.
         */
        const val REPAIR_ADVICE_TIMEOUT_MS = 2_000L
    }

    private val logger = LoggerFactory.getLogger(KernelBootstrap::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Infrastructure components (null when in MONOLITH mode)
    var ipcServer: BossIpcServer? = null
        private set
    var processRegistry: ProcessRegistry? = null
        private set
    var processSpawner: ProcessSpawner? = null
        private set
    var processMonitor: ProcessMonitor? = null
        private set
    var kernelService: KernelServiceImpl? = null
        private set
    var eventBusService: EventBusServiceImpl? = null
        private set
    var stateService: StateServiceImpl? = null
        private set
    var kernelAddress: String? = null
        private set

    /**
     * IPC address of each registered child, so the kernel can call *them*.
     *
     * [KernelServiceImpl] has always known these — it just handed them to a callback that dropped
     * them on the floor, which is why nothing on this side could ever reach the orchestrator.
     */
    private val serviceAddresses = ConcurrentHashMap<String, String>()

    /** Cached orchestrator channel, keyed by the address it was opened against. */
    private var orchestratorClient: Pair<String, BossIpcClient>? = null

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
        Runtime.getRuntime().addShutdownHook(
            Thread({
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
                } catch (_: Exception) {
                }
            }, "kernel-shutdown-hook"),
        )

        // Create gRPC services
        kernelService =
            KernelServiceImpl(
                onProcessRegistered = { id, manifest, ipcAddress ->
                    logger.info("Process registered via IPC: {} at {}", id, ipcAddress)
                    registry.updateManifest(id, manifest)
                    registry.getProcess(id)?.updateState(ProcessState.PROCESS_STATE_RUNNING)
                    if (ipcAddress.isNotBlank()) serviceAddresses[id] = ipcAddress
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

        // Start gRPC server.
        //
        // PluginUIServiceBridge goes in here rather than in registerPluginServices() below for two
        // reasons. It has to exist before any plugin connects — processes are spawned immediately after
        // this, and a plugin whose RegisterUI lands on a server with no PluginUIService gets UNIMPLEMENTED
        // and no UI at all. And registerPluginServices() exists to gate each bridge on a host provider
        // being present, which this one has nothing to gate on: its dependency is the surface registry,
        // which the host owns for its whole lifetime.
        //
        // Note it is NOT because late registration is destructive. It used to be — addService() on a
        // running server rebuilt it, tearing down the socket, which was survivable for unary bridges and
        // fatal for StreamUI — but BossIpcServer now routes late services through a MutableHandlerRegistry
        // and never touches the socket. Adding a service to a running server is safe; the ordering above
        // is about existence, not about protecting streams.
        ipcServer =
            BossIpcServer(kernelAddress!!)
                .addService(kernelService!!)
                .addService(eventBusService!!)
                .addService(stateService!!)
                .addService(PluginUIServiceBridge(RemoteUiSurfaceRegistry.shared))
                .start()

        // Wire IPC event bridge to forward events cross-process (M8 fix)
        val bridge = IpcEventBridgeImpl(eventBusService!!, scope)
        wireEventBridges(bridge)

        // Start process monitor
        processMonitor!!.startGlobalMonitor()

        // Listen for failures: ask the orchestrator what to do, else auto-respawn (C2+M7 fix).
        //
        // Each failure is handled in its own coroutine. Handling them inline would serialize the
        // advice deadline: a bad build taking several services down at once would make the last of
        // N crashes wait N×2s, with the monitor's suspending emit blocked behind it. Per-process
        // recovery is independent, and the monitor emits once per detection.
        scope.launch {
            processMonitor!!.failures.collect { failure ->
                launch { handleFailure(registry, spawner, failure) }
            }
        }

        // Spawn child services (M7 fix — structure in place)
        spawnServices(registry, spawner)

        // Store singleton for DefaultPlugin to access via reflection
        instance = this

        logger.info("KERNEL mode initialized. IPC server at: {}", kernelAddress)
    }

    /**
     * Decide what to do about a crashed child, and do it.
     *
     * The orchestrator gets first say — it runs the analyzer, the escalation ladder and the
     * snapshot bookkeeping that this loop knows nothing about. What it returns is *advice*: the
     * kernel is still the only thing that spawns processes, so a wrong or missing answer costs a
     * strategy, never a recovery.
     *
     * Recovery therefore falls back to the plain respawn this loop has always done whenever the
     * advice can't be trusted: the orchestrator is the process that just died, it has never
     * registered, or the call fails or outruns [REPAIR_ADVICE_TIMEOUT_MS]. Trading a mechanism
     * that works for one that is merely cleverer is how self-healing turns into self-harm.
     */
    private suspend fun handleFailure(
        registry: ProcessRegistry,
        spawner: ProcessSpawner,
        failure: ProcessFailure,
    ) {
        val process = registry.getProcess(failure.processId)
        if (process == null || process.config.restartPolicy != RestartPolicy.ON_FAILURE) {
            logger.error(
                "Process failure detected: {} - {} (no auto-respawn: policy={})",
                failure.processId,
                failure.errorMessage,
                process?.config?.restartPolicy,
            )
            return
        }

        val action = requestRepairAdvice(registry, failure)
        if (action == null) {
            respawn(registry, spawner, failure.processId)
            return
        }

        logger.info(
            "Repair advice for {}: strategy={} approval={} — {}",
            failure.processId,
            action.strategy,
            action.requiresUserApproval,
            action.description,
        )

        when (val recovery = recoveryFor(action)) {
            is Recovery.Respawn -> {
                respawn(registry, spawner, failure.processId)
            }

            is Recovery.RespawnTuned -> {
                respawn(registry, spawner, failure.processId, jvmArgsOverride = recovery.jvmArgs)
            }

            is Recovery.NotifyAndRespawn -> {
                notifyOperator(failure.processId, recovery.reason)
                respawn(registry, spawner, failure.processId)
            }
        }
    }

    /**
     * Ask the orchestrator how to repair [failure], or null when it cannot be asked in time.
     */
    private suspend fun requestRepairAdvice(
        registry: ProcessRegistry,
        failure: ProcessFailure,
    ): RepairAction? {
        // Never ask the orchestrator to diagnose its own death, and never wait on one that has
        // not registered an address yet.
        val stub = if (failure.processId == ORCHESTRATOR_PROCESS_ID) null else orchestratorStub()
        if (stub == null) return null

        val report =
            ProcessFailureReport
                .newBuilder()
                .setProcessId(failure.processId)
                .setErrorType(failure.reason.name)
                .setErrorMessage(failure.errorMessage)
                .setStackTrace(failure.stackTrace)
                .setExitCode(failure.exitCode)
                .setTimestamp(failure.timestamp)
                .setConsecutiveFailures(registry.getRestartCount(failure.processId) + 1)
                .apply { registry.getManifest(failure.processId)?.let { setManifest(it) } }
                .build()

        return try {
            withTimeoutOrNull(REPAIR_ADVICE_TIMEOUT_MS) { stub.reportFailure(report) }
                ?: run {
                    logger.warn(
                        "Orchestrator did not answer within {}ms for {} — recovering without advice",
                        REPAIR_ADVICE_TIMEOUT_MS,
                        failure.processId,
                    )
                    null
                }
        } catch (e: Exception) {
            logger.warn(
                "Could not reach the orchestrator for {} ({}) — recovering without advice",
                failure.processId,
                e.message,
            )
            null
        }
    }

    /** A stub for the running orchestrator, or null while it has no registered address. */
    private fun orchestratorStub(): OrchestratorServiceGrpcKt.OrchestratorServiceCoroutineStub? {
        val address = serviceAddresses[ORCHESTRATOR_PROCESS_ID] ?: return null
        val cached = orchestratorClient
        // Re-dial when the orchestrator comes back at a new address; the old channel is dead.
        val client =
            if (cached != null && cached.first == address) {
                cached.second
            } else {
                cached?.second?.runCatching { shutdown() }
                BossIpcClient(address).also { orchestratorClient = address to it }
            }
        return OrchestratorServiceGrpcKt.OrchestratorServiceCoroutineStub(client.channel)
    }

    /** Bring a crashed process back, honouring its restart cap. */
    private fun respawn(
        registry: ProcessRegistry,
        spawner: ProcessSpawner,
        processId: String,
        jvmArgsOverride: List<String>? = null,
    ) {
        val process = registry.getProcess(processId) ?: return
        val restartCount = registry.getRestartCount(processId)
        if (restartCount >= process.config.maxRestarts) {
            logger.error(
                "Process {} exceeded max restarts ({}), not respawning",
                processId,
                process.config.maxRestarts,
            )
            return
        }

        val config =
            if (jvmArgsOverride != null) process.config.copy(jvmArgs = jvmArgsOverride) else process.config
        logger.info(
            "Respawning process {} (attempt {}/{}{})",
            processId,
            restartCount + 1,
            process.config.maxRestarts,
            if (jvmArgsOverride != null) ", tuned: $jvmArgsOverride" else "",
        )
        try {
            val newProcess = spawner.spawn(config)
            registry.register(processId, newProcess, registry.getManifest(processId))
            registry.incrementRestartCount(processId)
        } catch (e: Exception) {
            logger.error("Respawn failed for {}: {}", processId, e.message)
        }
    }

    /**
     * Spawn the standard set of microkernel service processes.
     *
     * Each service runs from a fat JAR built by its module's `fatJar` task. Packaged builds ship
     * them under the app's resources; in development, build them with:
     *   ./gradlew :boss-orchestrator:fatJar :boss-service-auth:fatJar
     */
    private fun spawnServices(
        registry: ProcessRegistry,
        spawner: ProcessSpawner,
    ) {
        val bossDataDir =
            System.getenv("BOSS_DATA_DIR")
                ?: try {
                    ai.rever.boss.plugin.pathutils.BossDirectories.rootDir.absolutePath
                } catch (_: Exception) {
                    "${System.getProperty("user.home")}/.boss"
                }

        val orchestratorJar = resolveServiceJar(bossDataDir, "boss-orchestrator-all.jar")
        val authJar = resolveServiceJar(bossDataDir, "boss-service-auth-all.jar")

        // Children inherit nothing useful about where BOSS keeps its data, and the orchestrator
        // writes snapshots there — say it explicitly rather than letting the child re-derive a
        // default that may not match this process's.
        val serviceEnvironment = mapOf("BOSS_DATA_DIR" to bossDataDir)

        // The model choice — and the key the operator entered for it — goes to the orchestrator
        // alone; the other eight have no use for a credential. (A key exported into BOSS's own
        // environment is still inherited by every child via ProcessSpawner — see
        // SelfHealingSettingsManager.orchestratorEnvironment.)
        val repairEnvironment = SelfHealingSettingsManager.orchestratorEnvironment()
        logger.info(
            "AI repair for the orchestrator is {}",
            if (repairEnvironment.isEmpty()) "off" else "on (${repairEnvironment["AI_REPAIR_MODEL"]})",
        )

        spawnIfJarExists(
            spawner,
            registry,
            ProcessConfig(
                processId = ORCHESTRATOR_PROCESS_ID,
                processType = ProcessType.ORCHESTRATOR,
                displayName = "BOSS Orchestrator",
                mainClass = "ai.rever.boss.orchestrator.OrchestratorMainKt",
                classpath = orchestratorJar,
                restartPolicy = RestartPolicy.ON_FAILURE,
                maxRestarts = 5,
                environment = serviceEnvironment + repairEnvironment,
            ),
            orchestratorJar,
        )

        spawnIfJarExists(
            spawner,
            registry,
            ProcessConfig(
                processId = "boss-service-auth",
                processType = ProcessType.SERVICE,
                displayName = "BOSS Auth Service",
                mainClass = "ai.rever.boss.service.auth.AuthServiceMainKt",
                classpath = authJar,
                restartPolicy = RestartPolicy.ON_FAILURE,
                maxRestarts = 3,
                environment = serviceEnvironment,
            ),
            authJar,
        )

        val masteryOrchestratorJar = resolveServiceJar(bossDataDir, "boss-mastery-orchestrator-all.jar")
        spawnIfJarExists(
            spawner,
            registry,
            ProcessConfig(
                processId = "boss-mastery-orchestrator",
                processType = ProcessType.SERVICE,
                displayName = "BOSS Mastery Orchestrator",
                mainClass = "ai.rever.boss.mastery.orchestrator.MasteryOrchestratorMainKt",
                classpath = masteryOrchestratorJar,
                restartPolicy = RestartPolicy.ON_FAILURE,
                maxRestarts = 3,
                environment = serviceEnvironment,
            ),
            masteryOrchestratorJar,
        )

        val workspaceJar = resolveServiceJar(bossDataDir, "boss-service-workspace-all.jar")
        spawnIfJarExists(
            spawner,
            registry,
            ProcessConfig(
                processId = "boss-service-workspace",
                processType = ProcessType.SERVICE,
                displayName = "BOSS Workspace Service",
                mainClass = "ai.rever.boss.service.workspace.WorkspaceServiceMainKt",
                classpath = workspaceJar,
                restartPolicy = RestartPolicy.ON_FAILURE,
                maxRestarts = 3,
                environment = serviceEnvironment,
            ),
            workspaceJar,
        )

        val settingsJar = resolveServiceJar(bossDataDir, "boss-service-settings-all.jar")
        spawnIfJarExists(
            spawner,
            registry,
            ProcessConfig(
                processId = "boss-service-settings",
                processType = ProcessType.SERVICE,
                displayName = "BOSS Settings Service",
                mainClass = "ai.rever.boss.service.settings.SettingsServiceMainKt",
                classpath = settingsJar,
                restartPolicy = RestartPolicy.ON_FAILURE,
                maxRestarts = 3,
                environment = serviceEnvironment,
            ),
            settingsJar,
        )

        val filesystemJar = resolveServiceJar(bossDataDir, "boss-service-filesystem-all.jar")
        spawnIfJarExists(
            spawner,
            registry,
            ProcessConfig(
                processId = "boss-service-filesystem",
                processType = ProcessType.SERVICE,
                displayName = "BOSS FileSystem Service",
                mainClass = "ai.rever.boss.service.filesystem.FileSystemServiceMainKt",
                classpath = filesystemJar,
                restartPolicy = RestartPolicy.ON_FAILURE,
                maxRestarts = 3,
                environment = serviceEnvironment,
            ),
            filesystemJar,
        )

        val terminalJar = resolveServiceJar(bossDataDir, "boss-app-terminal-all.jar")
        spawnIfJarExists(
            spawner,
            registry,
            ProcessConfig(
                processId = "boss-app-terminal",
                processType = ProcessType.APP,
                displayName = "BOSS Terminal App",
                mainClass = "ai.rever.boss.app.terminal.TerminalServiceMainKt",
                classpath = terminalJar,
                restartPolicy = RestartPolicy.ON_FAILURE,
                maxRestarts = 5,
                environment = serviceEnvironment,
            ),
            terminalJar,
        )

        val editorJar = resolveServiceJar(bossDataDir, "boss-app-editor-all.jar")
        spawnIfJarExists(
            spawner,
            registry,
            ProcessConfig(
                processId = "boss-app-editor",
                processType = ProcessType.APP,
                displayName = "BOSS Editor App",
                mainClass = "ai.rever.boss.app.editor.EditorServiceMainKt",
                classpath = editorJar,
                restartPolicy = RestartPolicy.ON_FAILURE,
                maxRestarts = 5,
                environment = serviceEnvironment,
            ),
            editorJar,
        )

        val browserJar = resolveServiceJar(bossDataDir, "boss-app-browser-all.jar")
        spawnIfJarExists(
            spawner,
            registry,
            ProcessConfig(
                processId = "boss-app-browser",
                processType = ProcessType.APP,
                displayName = "BOSS Browser App",
                mainClass = "ai.rever.boss.app.browser.BrowserServiceMainKt",
                classpath = browserJar,
                restartPolicy = RestartPolicy.ON_FAILURE,
                maxRestarts = 5,
                environment = serviceEnvironment,
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
                config.processId,
                jarPath,
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

        // 6b. Close remote UI surfaces. The registry is process-wide and outlives this bootstrap, so a
        // restart would otherwise come up still holding claims from processes that are now dead.
        RemoteUiSurfaceRegistry.shared.clear()

        // 6c. Close the orchestrator channel. Nothing else owns it, so a mode switch or in-process
        // restart would otherwise leak the channel and its threads.
        orchestratorClient?.second?.shutdown()
        orchestratorClient = null
        serviceAddresses.clear()

        // 7. Cancel scope
        scope.cancel()

        logger.info("KERNEL mode shut down complete")
    }

    val isKernelMode: Boolean get() = mode == ProcessMode.KERNEL

    /**
     * Register the 15 kernel-side gRPC services that expose in-process providers
     * to out-of-process plugin child processes.
     *
     * Called from DefaultPlugin (reflectively — DefaultPlugin is commonMain) after all
     * providers are initialized, since the service bridges wrap the same provider
     * instances used by in-process plugins. **Changing this signature means updating
     * the reflective lookup in `DefaultPlugin.registerKernelPluginServices`.**
     *
     * 14 of the 15 services are provider-backed and are skipped when their provider is
     * absent. [ContextMenuServiceBridge] takes no provider — it is a passive stub, see
     * its KDoc — so it is registered unconditionally.
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
        // Provider-less by design: the bridge acknowledges and drops plugin
        // registrations, so gating it on the host ContextMenuProvider would only
        // suggest a delegation that cannot exist (issue #30).
        services += ContextMenuServiceBridge()
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
