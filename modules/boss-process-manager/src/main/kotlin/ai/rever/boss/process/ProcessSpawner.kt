package ai.rever.boss.process

import ai.rever.boss.ipc.BossIpcClient
import ai.rever.boss.ipc.IpcAddressResolver
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
 */
class ProcessSpawner(
    private val kernelIpcAddress: String,
    private val logDir: File =
        File(
            System.getenv("BOSS_DATA_DIR")
                ?: "${System.getProperty("user.home")}/.boss",
            "logs",
        ),
) {
    private val logger = LoggerFactory.getLogger(ProcessSpawner::class.java)

    /**
     * Spawn a new child process from the given configuration.
     *
     * If a native image path is specified and the binary exists, it runs natively.
     * Otherwise falls back to JVM mode.
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
