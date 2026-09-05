package ai.rever.boss.process

import ai.rever.boss.ipc.BossIpcClient
import ai.rever.boss.ipc.IpcAddressResolver
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
 * Process stdout/stderr are redirected to log files under $BOSS_DATA_DIR/logs/{processId}/
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
        /**
         * When present, every spawned process is minted a fresh IPC credential and handed it as
         * `BOSS_PROCESS_TOKEN`, so it can prove its identity to the kernel independently of any
         * `process_id` it later puts in a request (BossConsole#53). Null (the default) spawns exactly
         * as before — no token, no behaviour change for a caller that has no use for one (every
         * existing test in this module, and any host not wired with a `ProcessTokenRegistry`).
         *
         * `@JvmOverloads` on this constructor is load-bearing, not style: Kotlin emits only the
         * full-arity constructor plus a synthetic defaults bridge for trailing default parameters, so
         * without it the 3-arg `(String, File, ProcessRegistry)` shape this class used to be would stop
         * existing reflectively the moment this parameter was added - exactly the failure
         * `KernelReflectionContractTest` exists to catch (see its KDoc).
         */
        private val tokenRegistry: ProcessTokenRegistry? = null,
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
            val processLogDir = File(logDir, config.processId).also { it.mkdirs() }
            val stdoutLog = File(processLogDir, "stdout.log")
            val stderrLog = File(processLogDir, "stderr.log")

            val ipcAddress =
                IpcAddressResolver.resolveAddress(
                    config.processType.name.lowercase(),
                    config.processId,
                )

            val command = buildCommand(config)

            logger.info(
                "Spawning process: id={}, type={}, command={}",
                config.processId,
                config.processType,
                command.joinToString(" "),
            )

            val processBuilder =
                ProcessBuilder(command)
                    .directory(config.workDir)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(stdoutLog))
                    .redirectError(ProcessBuilder.Redirect.appendTo(stderrLog))

            // Set environment variables
            processBuilder.environment().apply {
                put("BOSS_KERNEL_IPC_ADDR", kernelIpcAddress)
                put("BOSS_PROCESS_ID", config.processId)
                put("BOSS_PROCESS_TYPE", config.processType.name)
                put("BOSS_IPC_ADDR", ipcAddress)
                putAll(config.environment)
                // Minted after config.environment, so nothing a caller supplies can shadow the real
                // credential — only the kernel gets to say what a process's own token is. Never logged.
                tokenRegistry?.issue(config.processId)?.let { put("BOSS_PROCESS_TOKEN", it) }
            }

            val process = processBuilder.start()

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
                it.ipcClient = BossIpcClient(ipcAddress)
                registry?.register(config.processId, it)
            }
        }

        /**
         * Invalidate [processId]'s IPC credential, if this spawner was given a `tokenRegistry`. A no-op
         * otherwise. Call this once a process is actually gone for good — not on every crash, since a
         * respawn already gets a fresh credential from [spawn] itself; this is for the paths that mean
         * "not coming back" (a deliberate `terminate()`, or the kernel's own shutdown reap).
         */
        fun revokeToken(processId: String) {
            tokenRegistry?.revoke(processId)
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
