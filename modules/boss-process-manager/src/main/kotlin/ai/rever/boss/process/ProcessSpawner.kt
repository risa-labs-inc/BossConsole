package ai.rever.boss.process

import ai.rever.boss.ipc.IpcAddressResolver
import ai.rever.boss.ipc.auth.IpcEnvironment
import ai.rever.boss.ipc.auth.IpcTlsIdentity
import ai.rever.boss.ipc.auth.ProcessTokenRegistry
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Spawns child processes (either GraalVM native images or JVM subprocesses).
 *
 * Each child process receives:
 * - BOSS_KERNEL_IPC_ADDR: Address to connect back to the kernel
 * - BOSS_PROCESS_ID: Assigned process ID
 * - BOSS_PROCESS_TYPE: Process type (SERVICE, APP, PLUGIN)
 *
 * Process stdout/stderr are drained into bounded logs under $BOSS_DATA_DIR/logs/{processId}/.
 * Each stream retains at most five 10 MiB files, including the current file.
 *
 * Everything spawned here is entered into [registry], because the registry is what the kernel's
 * shutdown hook reaps on exit. Registration used to be each caller's job, and the caller that
 * forgot - the out-of-process plugin spawner - leaked a full cohort of child JVMs on every host
 * exit for months. Owning it here makes that class of bug impossible for every call site.
 */
class ProcessSpawner
    @JvmOverloads
    constructor(
        private val kernelIpcAddress: String,
        private val logDir: File =
            File(
                System.getenv("BOSS_DATA_DIR")
                    ?: "${System.getProperty("user.home")}/.boss",
                "logs",
            ),
        private val registry: ProcessRegistry? = null,
        /** The host registry is required for a managed IPC child. Plain subprocesses may omit it. */
        private val tokenRegistry: ProcessTokenRegistry? = null,
        private val kernelIdentity: IpcTlsIdentity? = null,
    ) {
        private val logger = LoggerFactory.getLogger(ProcessSpawner::class.java)

        /**
         * Spawn a new child process from the given configuration and register it.
         *
         * If a native image path is specified and the binary exists, it runs natively.
         * Otherwise falls back to JVM mode.
         *
         * The returned process is already in [registry], so callers must not register it again.
         * Removing it is still the caller's job: only the caller knows the difference between a
         * deliberate termination and a crash.
         */
        fun spawn(config: ProcessConfig): ManagedProcess {
            // Validate before socket or log creation, not after a caller-selected directory is made.
            IpcAddressResolver.validateProcessIdentifier(config.processId)
            val ipcAddress =
                IpcAddressResolver.resolveAddress(
                    config.processType.name.lowercase(),
                    config.processId,
                )

            val command = buildCommand(config)

            logger.info(
                "Spawning process: id={}, type={}",
                config.processId,
                config.processType,
            )

            val processBuilder =
                ProcessBuilder(command)
                    .directory(config.workDir)

            // Set environment variables
            processBuilder.environment().apply {
                putAll(config.environment)
                put("BOSS_KERNEL_IPC_ADDR", kernelIpcAddress)
                put("BOSS_PROCESS_ID", config.processId)
                put("BOSS_PROCESS_TYPE", config.processType.name)
                put("BOSS_IPC_ADDR", ipcAddress)
                IpcEnvironment.removeCredentials(this)
                // Minted after config.environment, so nothing a caller supplies can shadow the real
                // credential — only the kernel gets to say what a process's own token is. Never logged.
            }

            val logs = ProcessLogStreams.acquire(logDir.toPath(), config.processId)
            var security: SpawnIpcSecurity? = null
            val process =
                runCatching {
                    security = SpawnIpcSecurity.create(tokenRegistry, kernelIdentity, config, ipcAddress)
                    security?.install(processBuilder.environment())
                    startWithLogs(processBuilder, logs)
                }.onFailure {
                    logs.close()
                    security?.revoke()
                }.getOrThrow()
            process.onExit().thenRun { security?.revoke() }

            logger.info(
                "Process started: id={}, pid={}, ipc={}",
                config.processId,
                process.pid(),
                ipcAddress,
            )

            return ManagedProcess(
                config = config,
                process = process,
                ipcAddress = ipcAddress,
            ).also {
                it.ipcClient = security?.client
                registry?.register(config.processId, it)
            }
        }

        private fun startWithLogs(
            builder: ProcessBuilder,
            logs: ProcessLogStreams,
        ): Process {
            val child = builder.start()
            var attached = false
            try {
                logs.attach(child)
                attached = true
                return child
            } finally {
                if (!attached) {
                    child.destroyForcibly()
                    child.onExit().join()
                }
            }
        }

        private fun buildCommand(config: ProcessConfig): List<String> {
            val nativeBinary = config.nativeImagePath

            // Prefer native image if available
            if (nativeBinary != null && File(nativeBinary).let { it.exists() && it.canExecute() }) {
                logger.info("Using GraalVM native image for {}: {}", config.processId, nativeBinary)
                return listOf(nativeBinary)
            }

            // Fall back to JVM mode
            val javaExecutable = findJavaExecutable()
            logger.info("Using JVM mode for {}: {}", config.processId, javaExecutable)

            return buildList {
                add(javaExecutable)
                addAll(config.jvmArgs)
                if (config.classpath.isNotBlank()) {
                    add("-cp")
                    add(config.classpath)
                }
                add(config.mainClass)
            }
        }

        companion object {
            fun findJavaExecutable(): String {
                // Use the same Java that's running the kernel — but only if it IS java.
                // In packaged app bundles, the current command is the app launcher (e.g., "BOSS"),
                // not the java binary. In that case fall back to JAVA_HOME.
                val currentCommand =
                    ProcessHandle
                        .current()
                        .info()
                        .command()
                        .orElse(null)
                if (currentCommand != null &&
                    (
                        currentCommand.endsWith("/java") || currentCommand.endsWith("\\java.exe") ||
                            currentCommand.endsWith("/java.exe")
                    )
                ) {
                    return currentCommand
                }
                // Not a JVM launcher — fall back to JAVA_HOME or java.home system property
                System.getenv("JAVA_HOME")?.let { return "$it/bin/java" }
                return System.getProperty("java.home")?.let { "$it/bin/java" } ?: "java"
            }
        }
    }
