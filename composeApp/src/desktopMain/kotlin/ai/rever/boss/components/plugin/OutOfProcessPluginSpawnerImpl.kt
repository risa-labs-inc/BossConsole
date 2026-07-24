package ai.rever.boss.components.plugin

import ai.rever.boss.ipc.BossIpcClient
import ai.rever.boss.ipc.IpcVersion
import ai.rever.boss.kernel.KernelBootstrap
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.loader.PluginManifestReader
import ai.rever.boss.process.ManagedProcess
import ai.rever.boss.process.ProcessConfig
import ai.rever.boss.process.ProcessSpawner
import ai.rever.boss.process.ProcessType
import ai.rever.boss.process.RestartPolicy
import io.grpc.ManagedChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.io.File
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
    ): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val pluginId = manifest.pluginId

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
                        processId = "plugin-$pluginId",
                        processType = ProcessType.PLUGIN,
                        displayName = manifest.displayName,
                        mainClass = "ai.rever.boss.plugin.runtime.PluginProcessMainKt",
                        classpath = classpath,
                        nativeImagePath = manifest.nativeImagePath?.takeIf { it.isNotEmpty() },
                        jvmArgs = buildJvmArgs(),
                        workDir = File(projectPath.ifEmpty { System.getProperty("user.dir") }),
                        restartPolicy = RestartPolicy.ON_FAILURE,
                        maxRestarts = manifest.sandbox.maxRestartAttempts,
                        environment = buildEnvironment(jarPath),
                        startupTimeoutMs = manifest.healthContract?.startupTimeoutMs ?: 30_000,
                        heartbeatIntervalMs = manifest.healthContract?.heartbeatIntervalMs ?: 5_000,
                    )

                logger.info(
                    "Spawning out-of-process plugin: id={}, jar={}, runtime={}",
                    pluginId,
                    jarPath,
                    runtimeClasspath,
                )

                val managedProcess = processSpawner.spawn(config)
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
                        instanceId = "plugin-$pluginId",
                        channel = channel,
                    )
                bridge.start()
                stateBridges[pluginId] = bridge

                logger.info(
                    "Out-of-process plugin ready: id={}, pid={}, ipc={}",
                    pluginId,
                    managedProcess.pid,
                    managedProcess.ipcAddress,
                )

                Result.success(Unit)
            } catch (e: Exception) {
                logger.error(
                    "Failed to spawn out-of-process plugin: manifest={}",
                    manifest.pluginId,
                    e,
                )
                Result.failure(e)
            }
        }
    }

    override suspend fun terminate(pluginId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                // Dispose state bridge
                stateBridges.remove(pluginId)?.dispose()

                // Shutdown gRPC channel with timeout
                pluginChannels.remove(pluginId)?.let { channel ->
                    channel.shutdown()
                    if (!channel.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)) {
                        logger.warn("gRPC channel shutdown timeout for {}, forcing", pluginId)
                        channel.shutdownNow()
                    }
                }

                // Destroy process
                val process = managedProcesses.remove(pluginId)
                if (process != null) {
                    logger.info("Terminating plugin process: id={}, pid={}", pluginId, process.pid)
                    process.destroy()

                    // Wait for graceful shutdown, then force kill
                    withTimeout(5_000) {
                        while (process.isAlive) {
                            delay(100)
                        }
                    }
                } else {
                    logger.warn("No managed process found for plugin: {}", pluginId)
                }

                Result.success(Unit)
            } catch (e: Exception) {
                // Force kill if graceful shutdown failed
                managedProcesses[pluginId]?.destroyForcibly()
                managedProcesses.remove(pluginId)
                logger.warn("Force-killed plugin process: id={}", pluginId, e)
                Result.success(Unit)
            }
        }

    /**
     * Get the gRPC channel to a plugin process.
     * Used by RemotePanelComponent/RemoteTabComponent for UI streaming.
     */
    fun getChannel(pluginId: String): ManagedChannel? = pluginChannels[pluginId]

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
            if (windowId.isNotEmpty()) put("BOSS_WINDOW_ID", windowId)
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
            val processId = "plugin-$pluginId"
            val registry = KernelBootstrap.instance?.processRegistry

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
