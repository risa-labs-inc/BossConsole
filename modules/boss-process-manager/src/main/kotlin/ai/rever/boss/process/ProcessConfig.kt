package ai.rever.boss.process

import java.io.File

/**
 * Configuration for spawning a child process.
 */
data class ProcessConfig(
    /** Unique process identifier */
    val processId: String,
    /** Type of process (SERVICE, APP, PLUGIN) */
    val processType: ProcessType,
    /** Human-readable name */
    val displayName: String,
    /** Main class for JVM mode */
    val mainClass: String,
    /** Classpath for JVM mode */
    val classpath: String = "",
    /** Path to GraalVM native image binary (null = use JVM mode) */
    val nativeImagePath: String? = null,
    /** JVM arguments (only used in JVM mode) */
    val jvmArgs: List<String> = listOf("-Xmx256m"),
    /** Working directory for the process */
    val workDir: File = File("."),
    /** Process IDs that must start before this one */
    val dependencies: List<String> = emptyList(),
    /** Restart policy */
    val restartPolicy: RestartPolicy = RestartPolicy.ON_FAILURE,
    /** Maximum restart attempts before disabling */
    val maxRestarts: Int = 3,
    /** Additional environment variables */
    val environment: Map<String, String> = emptyMap(),
    /** Startup timeout in milliseconds */
    val startupTimeoutMs: Long = 30_000,
    /** Heartbeat interval in milliseconds */
    val heartbeatIntervalMs: Long = 5_000,
)

enum class ProcessType {
    KERNEL,
    SERVICE,
    APP,
    PLUGIN,
    ORCHESTRATOR,
}

enum class RestartPolicy {
    /** Never restart, even on failure */
    NEVER,

    /** Restart only on non-zero exit code */
    ON_FAILURE,

    /** Always restart, even on clean exit */
    ALWAYS,
}
