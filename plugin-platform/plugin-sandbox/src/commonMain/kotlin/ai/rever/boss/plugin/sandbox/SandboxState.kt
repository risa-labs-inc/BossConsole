package ai.rever.boss.plugin.sandbox

/**
 * Represents the current state of a plugin sandbox.
 */
enum class SandboxState {
    /**
     * Plugin is running normally.
     */
    RUNNING,

    /**
     * Plugin has experienced errors but is still operational.
     * May trigger a restart if errors continue.
     */
    UNHEALTHY,

    /**
     * Plugin has crashed and is not operational.
     */
    CRASHED,

    /**
     * Plugin is in the process of restarting.
     */
    RESTARTING,

    /**
     * Plugin has been stopped (either manually or due to max restart attempts).
     */
    STOPPED,

    /**
     * Plugin has been disabled by the user or system.
     * Will not auto-restart. User must explicitly re-enable.
     */
    DISABLED,
}
