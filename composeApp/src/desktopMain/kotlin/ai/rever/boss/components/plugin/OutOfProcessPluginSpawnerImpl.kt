package ai.rever.boss.components.plugin

import ai.rever.boss.components.plugin.remote.PluginProcessMonitor
import ai.rever.boss.ipc.BossIpcClient
import ai.rever.boss.ipc.IpcVersion
import ai.rever.boss.kernel.KernelBootstrap
import ai.rever.boss.kernel.ReapAdmissionException
import ai.rever.boss.kernel.discardReapedSpawn
import ai.rever.boss.kernel.isReaping
import ai.rever.boss.kernel.reapSpawnGate
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.loader.PluginManifestReader
import ai.rever.boss.process.ManagedProcess
import ai.rever.boss.process.ProcessConfig
import ai.rever.boss.process.ProcessRegistry
import ai.rever.boss.process.ProcessSpawner
import ai.rever.boss.process.ProcessType
import ai.rever.boss.process.RestartPolicy
import io.grpc.ManagedChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Implementation of [OutOfProcessPluginSpawner] that uses [ProcessSpawner]
 * to launch plugin child JVM processes.
 *
 * Each spawned plugin:
 * 1. Runs the generic `PluginProcessMain` entry point
 * 2. Reads its plugin JAR from `BOSS_PLUGIN_CLASSPATH`
 * 3. Connects back to the kernel via `BOSS_KERNEL_IPC_ADDR`
 * 4. Registers with the kernel's process registry
 * 5. Starts its gRPC server for UI streaming and state sync
 *
 * The spawner tracks all managed processes and provides connection info
 * (gRPC channel) for [PluginStateBridge] and remote UI components.
 */
@Suppress("TooManyFunctions")
class OutOfProcessPluginSpawnerImpl(
    private val processSpawner: ProcessSpawner,
    private val windowId: String = "",
    private val projectPath: String = "",
) : OutOfProcessPluginSpawner {
    private val logger = LoggerFactory.getLogger(OutOfProcessPluginSpawnerImpl::class.java)

    /** Active managed processes keyed by plugin ID. */
    private val managedProcesses = ConcurrentHashMap<String, ManagedProcess>()

    /** gRPC channels to plugin processes keyed by plugin ID. */
    private val pluginChannels = ConcurrentHashMap<String, ManagedChannel>()

    /** State bridges keyed by plugin ID. */
    private val stateBridges = ConcurrentHashMap<String, PluginStateBridge>()

    private val pluginLifecycleMutexes =
        java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    private val processMonitor =
        PluginProcessMonitor(this).also { it.start() }

    /**
     * Classpath for the plugin runtime fat JAR.
     * Resolved from BOSS_PLUGIN_RUNTIME_JAR env var or default location.
     */
    private val runtimeClasspath: String by lazy {
        System.getenv("BOSS_PLUGIN_RUNTIME_JAR")
            ?: findRuntimeJar()
            ?: throw IllegalStateException(
                "Cannot find ${MicrokernelRuntime.ARTIFACT_PREFIX} JAR. Set BOSS_PLUGIN_RUNTIME_JAR env var.",
            )
    }

    /**
     * IPC-version compatibility status of the runtime JAR. Computed once on
     * first spawn so we don't re-parse the manifest per plugin. `null` means
     * the manifest couldn't be read at all — treated as a fatal startup
     * error; the first spawn call will surface it.
     */
    private val runtimeCompat: IpcVersion.CompatResult? by lazy {
        runCatching {
            val manifest = PluginManifestReader.readFromJar(runtimeClasspath)
            IpcVersion.isCompatible(manifest.minIpcVersion)
        }.onFailure { e ->
            logger.error("Failed to read runtime JAR manifest for IPC compat check: {}", runtimeClasspath, e)
        }.getOrNull()
    }

    override suspend fun spawn(
        manifest: PluginManifest,
        jarPath: String,
    ): Result<Unit> =
        withPluginLifecycleLock(manifest.pluginId) {
            spawnUnderLifecycleLock(manifest, jarPath)
        }

    @Suppress("LongMethod")
    private suspend fun spawnUnderLifecycleLock(
        manifest: PluginManifest,
        jarPath: String,
    ): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val spawnGeneration = reapSpawnGate.generation()
                val pluginId = manifest.pluginId

                // Stand down if a reap is in progress. A reap means the host is exiting (or
                // switching to in-process): the reaper has already snapshotted the children it will
                // kill, so a child registered now survives past it, unreaped. respawnCandidate()
                // already refuses during a reap; this is the plugin LOAD path doing the same, which
                // isReaping() exists to gate. See KernelBootstrap.reapChildren.
                if (isReaping()) {
                    val msg = "Refusing to spawn plugin $pluginId: a reap is in progress"
                    logger.warn(msg)
                    return@withContext Result.failure<Unit>(IllegalStateException(msg))
                }

                // IPC-compat gate — if the runtime JAR on disk doesn't match
                // the host's current IPC version we refuse here rather than
                // hit a cryptic gRPC deserialization failure in the child.
                when (val compat = runtimeCompat) {
                    is IpcVersion.CompatResult.Incompatible -> {
                        val msg =
                            "Microkernel runtime is incompatible with this host. ${compat.reason} " +
                                "(host IPC=${IpcVersion.CURRENT}, runtime=$runtimeClasspath)"
                        logger.error(msg)
                        return@withContext Result.failure<Unit>(IllegalStateException(msg))
                    }

                    is IpcVersion.CompatResult.UnknownRuntime -> {
                        logger.warn(
                            "Microkernel runtime does not declare minIpcVersion (legacy pre-Phase-0 JAR). " +
                                "Proceeding but the next incompatible update will not be auto-detected. " +
                                "runtime={}, hostIpcVersion={}",
                            runtimeClasspath,
                            IpcVersion.CURRENT,
                        )
                    }

                    is IpcVersion.CompatResult.Compatible, null -> {
                        // null = manifest read failure; already logged. Continue —
                        // the child will fail on its own if the JAR is actually broken.
                    }
                }

                // Build classpath: runtime JAR + plugin JAR + (when resolved)
                // the runtime API layer jar. The api jar goes LAST so runtime
                // and plugin classes win and it only fills in types the
                // runtime predates — the flat-classpath analogue of the
                // in-process ApiClassLoader's parent-first position. Without
                // it, a plugin using an api-jar-only type (ConsoleLogsAPI
                // pattern) dies in the child with NoClassDefFoundError.
                val apiJar = System.getProperty("boss.api.jar")?.takeIf { it.isNotBlank() }
                val classpath =
                    listOfNotNull(runtimeClasspath, jarPath, apiJar)
                        .joinToString(File.pathSeparator)

                val config =
                    ProcessConfig(
                        processId = pluginProcessId(windowId, pluginId),
                        processType = ProcessType.PLUGIN,
                        displayName = manifest.displayName,
                        mainClass = "ai.rever.boss.plugin.runtime.PluginProcessMainKt",
                        classpath = classpath,
                        nativeImagePath = manifest.nativeImagePath?.takeIf { it.isNotEmpty() },
                        jvmArgs = buildJvmArgs(),
                        workDir = File(projectPath.ifEmpty { System.getProperty("user.dir") }),
                        restartPolicy = RestartPolicy.ON_FAILURE,
                        maxRestarts = manifest.sandbox.maxRestartAttempts,
                        environment = buildEnvironment(jarPath) + ("BOSS_PLUGIN_ID" to pluginId),
                        startupTimeoutMs = manifest.healthContract?.startupTimeoutMs ?: 30_000,
                        heartbeatIntervalMs = manifest.healthContract?.heartbeatIntervalMs ?: 5_000,
                    )

                logger.info(
                    "Spawning out-of-process plugin: id={}, processId={}, windowId={}, jar={}, runtime={}",
                    pluginId,
                    config.processId,
                    windowId,
                    jarPath,
                    runtimeClasspath,
                )

                // spawn() enters the child in the kernel registry, which is what the shutdown hook
                // reaps. See ProcessSpawner's KDoc for why registration lives there.
                val managedProcess =
                    reapSpawnGate.spawn(
                        spawnGeneration,
                        createChild = { processSpawner.spawn(config) },
                        discardChild = { discardReapedSpawn(it, kernelRegistry()) },
                    )
                managedProcesses[pluginId] = managedProcess

                // Wait for the child process to register with the kernel
                waitForReady(pluginId, managedProcess, config.startupTimeoutMs)

                // Create gRPC channel to the plugin process
                val channel = BossIpcClient(managedProcess.ipcAddress).channel
                pluginChannels[pluginId] = channel

                // Create and start state bridge
                val bridge =
                    PluginStateBridge(
                        pluginId = pluginId,
                        instanceId = config.processId,
                        channel = channel,
                    )
                bridge.start()
                stateBridges[pluginId] = bridge

                processMonitor.monitor(
                    pluginId = pluginId,
                    displayName = manifest.displayName,
                    maxRestarts = manifest.sandbox.maxRestartAttempts,
                    restartAction = {
                        restartAfterCrash(manifest, jarPath)
                    },
                    terminalFailureAction = {
                        cleanupAfterTerminalFailure(pluginId)
                    },
                )

                logger.info(
                    "Out-of-process plugin ready: id={}, pid={}, ipc={}",
                    pluginId,
                    managedProcess.pid,
                    managedProcess.ipcAddress,
                )

                Result.success(Unit)
            } catch (e: ReapAdmissionException) {
                logger.warn("Refusing plugin startup after a reap: {}", manifest.pluginId)
                Result.failure(e)
            } catch (e: Exception) {
                logger.error(
                    "Failed to spawn out-of-process plugin: manifest={}",
                    manifest.pluginId,
                    e,
                )
                // A waitForReady timeout leaves a child that started but never registered -
                // still alive, and no longer referenced by anything that would kill it. Reap
                // it here rather than let a failed spawn become another orphan.
                cleanupFailedSpawn(manifest.pluginId)
                Result.failure(e)
            }
        }
    }

    /**
     * Tear down everything [spawn] may have created for a plugin whose startup failed.
     */
    private fun cleanupFailedSpawn(pluginId: String) {
        runCatching { stateBridges.remove(pluginId)?.dispose() }
        runCatching { pluginChannels.remove(pluginId)?.shutdownNow() }
        // Kill first, drop the registry entry second: while the child is alive the registry entry
        // is the only thing that would let a host exit reap it.
        val process = managedProcesses.remove(pluginId)
        ai.rever.boss.kernel
            .killProcessDescendants(
                ai.rever.boss.kernel
                    .processDescendants(process?.process),
            )
        runCatching { process?.destroyForcibly() }
        awaitForcedExit(process)
        process?.takeUnless { it.isAlive }?.let { kernelRegistry()?.unregisterIfSame(it.config.processId, it) }
    }

    override suspend fun terminate(pluginId: String): Result<Unit> =
        withPluginLifecycleLock(pluginId) {
            processMonitor.unmonitor(pluginId)
            terminateManagedProcess(pluginId)
        }

    private suspend fun restartAfterCrash(
        manifest: PluginManifest,
        jarPath: String,
    ): Result<Unit> {
        val pluginId = manifest.pluginId

        return withPluginLifecycleLock(pluginId) {
            if (!processMonitor.isMonitored(pluginId)) {
                return@withPluginLifecycleLock Result.failure(
                    IllegalStateException("Plugin is no longer monitored: $pluginId"),
                )
            }

            val terminated = terminateManagedProcess(pluginId)
            if (terminated.isFailure) {
                return@withPluginLifecycleLock terminated
            }

            if (!processMonitor.isMonitored(pluginId)) {
                return@withPluginLifecycleLock Result.failure(
                    IllegalStateException("Plugin restart was cancelled: $pluginId"),
                )
            }

            spawnUnderLifecycleLock(manifest, jarPath)
        }
    }

    private suspend fun cleanupAfterTerminalFailure(pluginId: String) {
        withPluginLifecycleLock(pluginId) {
            if (processMonitor.isMonitored(pluginId)) {
                terminateManagedProcess(pluginId).getOrThrow()
            }
        }
    }

    private suspend fun terminateManagedProcess(pluginId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val process = managedProcesses.remove(pluginId)
            val descendants =
                ai.rever.boss.kernel
                    .processDescendants(process?.process)
            try {
                stateBridges.remove(pluginId)?.dispose()

                pluginChannels.remove(pluginId)?.let { channel ->
                    channel.shutdown()
                    if (!channel.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)) {
                        logger.warn("gRPC channel shutdown timeout for {}, forcing", pluginId)
                        channel.shutdownNow()
                    }
                }

                if (process != null) {
                    logger.info("Terminating plugin process: id={}, pid={}", pluginId, process.pid)
                    process.destroy()

                    // A child that outlives the grace window is the normal exit of this
                    // wait, not an error: force-kill it and succeed, matching dev's
                    // pre-cancellation behaviour. withTimeout would surface the same
                    // outcome as TimeoutCancellationException, a CancellationException
                    // that callers and the health loop treat as caller cancellation.
                    val exited =
                        withTimeoutOrNull(5_000) {
                            while (process.isAlive) {
                                delay(100)
                            }
                        }
                    if (exited == null) {
                        logger.warn("Plugin process outlived the 5s destroy grace, forcing: id={}", pluginId)
                        process.destroyForcibly()
                    }
                } else {
                    logger.warn("No managed process found for plugin: {}", pluginId)
                }

                Result.success(Unit)
            } catch (e: CancellationException) {
                // Termination already owns cleanup: cancellation must not orphan this subtree.
                runCatching { process?.destroyForcibly() }
                throw e
            } catch (e: Exception) {
                process?.destroyForcibly()
                logger.warn("Force-killed plugin process: id={}", pluginId, e)
                Result.success(Unit)
            } finally {
                ai.rever.boss.kernel
                    .killProcessDescendants(descendants)
                // Registry entry goes last, and only if it is still this process. "Registered
                // implies reapable" has to hold for as long as the child is alive, so a host exit
                // part-way through an unload still reaps it; and removing by id alone could evict
                // a replacement that a concurrent respawn had already registered.
                awaitForcedExit(process)
                process?.takeUnless { it.isAlive }?.let {
                    kernelRegistry()?.unregisterIfSame(it.config.processId, it)
                }
            }
        }

    private suspend fun <T> withPluginLifecycleLock(
        pluginId: String,
        action: suspend () -> T,
    ): T {
        val mutex = pluginLifecycleMutexes.computeIfAbsent(pluginId) { Mutex() }
        mutex.lock()

        return try {
            action()
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Get the state bridge for a plugin.
     */
    fun getStateBridge(pluginId: String): PluginStateBridge? = stateBridges[pluginId]

    /**
     * Get the managed process for a plugin.
     */
    fun getManagedProcess(pluginId: String): ManagedProcess? = managedProcesses[pluginId]

    /**
     * Check if a plugin process is alive.
     */
    fun isAlive(pluginId: String): Boolean = managedProcesses[pluginId]?.isAlive == true

    override fun dispose() {
        processMonitor.dispose()
    }

    private fun buildJvmArgs(): List<String> =
        buildList {
            val settings =
                try {
                    ai.rever.boss.performance.PerformanceSettingsManager.currentSettings.value
                } catch (_: Exception) {
                    null
                }
            val heapMax = settings?.pluginJvmHeapMb ?: 512
            val heapInit = settings?.pluginJvmInitialHeapMb ?: 64
            add("-Xmx${heapMax}m")
            add("-Xms${heapInit}m")
            // System properties are not inherited across processes: without this
            // the child's BossApiRuntime reads no boss.api.version, reports
            // "0.0.0" (which PARSES, so isAtLeast does not fail open) and the
            // plugin wrongly concludes the API layer is ancient.
            System.getProperty("boss.api.version")?.takeIf { it.isNotBlank() }?.let {
                add("-Dboss.api.version=$it")
            }
        }

    private fun buildEnvironment(jarPath: String): Map<String, String> =
        buildMap {
            put("BOSS_PLUGIN_CLASSPATH", jarPath)
            if (windowId.isNotBlank()) put("BOSS_WINDOW_ID", windowId)
            if (projectPath.isNotEmpty()) put("BOSS_PROJECT_PATH", projectPath)
        }

    /**
     * Wait for the child process to become ready (registered with kernel).
     */
    private suspend fun waitForReady(
        pluginId: String,
        process: ManagedProcess,
        timeoutMs: Long,
    ) {
        withTimeout(timeoutMs) {
            val processId = process.config.processId
            val registry = kernelRegistry()

            while (true) {
                if (!process.isAlive) {
                    throw IllegalStateException(
                        "Plugin process died during startup: $pluginId (exit=${process.process.exitValue()})",
                    )
                }
                // Check kernel's process registry (populated by gRPC registration from child)
                if (registry?.getManifest(processId) != null) {
                    break
                }
                delay(100)
            }
        }
    }

    /**
     * Find the plugin runtime fat JAR in standard locations.
     * Searches the plugins directory (where all plugins live) and dev build output.
     */
    private fun findRuntimeJar(): String? {
        val bossDataDir =
            try {
                ai.rever.boss.plugin.pathutils.BossDirectories.rootDir.absolutePath
            } catch (_: Exception) {
                System.getenv("BOSS_DATA_DIR") ?: "${System.getProperty("user.home")}/.boss"
            }
        val pluginDir = "$bossDataDir/plugins"

        val prefix = MicrokernelRuntime.ARTIFACT_PREFIX
        // The runtime is now distributed exclusively via the standalone
        // repo `risa-labs-inc/boss-microkernel-runtime` and lives in
        // `~/.boss/plugins/`. No more in-tree build output to look at.
        // For local development on the runtime itself, copy the fatJar
        // from `boss_plugin/boss-microkernel-runtime/build/libs/` into
        // `~/.boss/plugins/` (see that repo's dev-setup.sh).
        return File(pluginDir)
            .listFiles()
            ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".jar") }
            ?.maxByOrNull { it.lastModified() }
            ?.absolutePath
    }
}

/**
 * Kernel-side process ID for a plugin.
 *
 * The registry is process-wide, so window-owned spawners include [windowId]. This prevents the
 * same plugin in two windows from evicting the other live process. A blank window ID preserves
 * the legacy identity for non-window and test contexts.
 */
internal fun pluginProcessId(
    windowId: String,
    pluginId: String,
): String =
    if (windowId.isBlank()) {
        "plugin-$pluginId"
    } else {
        // IPC uses this as a socket filename. Full window UUIDs plus manifest IDs exceed
        // Unix socket path limits, so keep the identity bounded without ambiguous separators.
        val identity = "${windowId.length}:$windowId$pluginId"
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        "plugin-" + digest.take(16).joinToString("") { "%02x".format(it) }
    }

/**
 * The kernel's process registry, or null when the kernel is not up.
 *
 * Resolved per call rather than cached, for symmetry with the other lookups. In practice it cannot
 * be null here: `DefaultPlugin` obtains this spawner's [ProcessSpawner] *from*
 * `KernelBootstrap.instance`, so the instance and its registry both exist before this class does.
 */
private fun kernelRegistry(): ProcessRegistry? = KernelBootstrap.instance?.processRegistry

private fun awaitForcedExit(process: ai.rever.boss.process.ManagedProcess?) {
    // SIGKILL is asynchronous. Preserve a still-live handle after this bounded wait.
    runCatching { process?.process?.waitFor(100, java.util.concurrent.TimeUnit.MILLISECONDS) }
}
