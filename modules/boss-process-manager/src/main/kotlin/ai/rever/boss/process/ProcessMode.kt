package ai.rever.boss.process

/**
 * Controls whether the application runs as a single monolithic process
 * or as a microkernel with child processes.
 *
 * Every architectural change is gated by this enum — MONOLITH mode
 * preserves current behavior with zero changes.
 */
enum class ProcessMode {
    /** Everything runs in a single JVM process (default, backward compatible) */
    MONOLITH,

    /** Kernel spawns child processes for services, apps, and plugins */
    KERNEL,
}
