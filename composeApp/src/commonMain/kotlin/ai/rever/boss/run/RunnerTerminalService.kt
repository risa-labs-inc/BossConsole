package ai.rever.boss.run

import kotlinx.coroutines.flow.StateFlow

/** Prefix for runner terminal IDs */
const val RUNNER_TERMINAL_PREFIX = "runner-"

/**
 * Expect declaration for RunnerTerminalService.
 * Manages runner terminals with configuration tracking.
 *
 * Features:
 * - One terminal tab per run configuration (reused on re-run)
 * - Track running state per configuration
 * - Support for stop (Ctrl+C) and re-run operations
 *
 * Issue #347: Runner should open in terminal sidebar panel with run/stop state management
 */
expect object RunnerTerminalService {
    /**
     * Map of configId to terminalTabId for tracking which terminal belongs to which config.
     */
    val configToTerminal: StateFlow<Map<String, String>>

    /**
     * Set of currently running configuration IDs.
     */
    val runningConfigs: StateFlow<Set<String>>

    /**
     * Map of configId to set of windowIds where the config is running.
     * Used for per-window button state (Issue #498).
     */
    val configToWindows: StateFlow<Map<String, Set<String>>>

    /**
     * Check if a specific configuration is currently running.
     */
    fun isConfigRunning(configId: String): Boolean

    /**
     * Check if a specific configuration is currently running in a specific window.
     * Used for per-window button state isolation.
     *
     * @param windowId The window ID to check
     * @param configId The configuration ID to check
     * @return True if the config is running in the specified window
     */
    fun isConfigRunningInWindow(windowId: String, configId: String): Boolean

    /**
     * Open or reuse a runner terminal for the given configuration.
     * If a terminal already exists for this config, it will be reused.
     *
     * @param config The run configuration to execute
     * @param windowId The window ID that initiated the run (Issue #498)
     * @param onTerminalCreated Callback when terminal tab is created, receives terminal ID
     * @return The terminal tab ID
     */
    suspend fun openRunnerTerminal(
        config: RunConfiguration,
        windowId: String,
        onTerminalCreated: (String) -> Unit = {}
    ): String

    /**
     * Stop the runner for a configuration by sending Ctrl+C.
     *
     * @param windowId The window ID that initiated the stop (Issue #498)
     * @param configId The configuration ID to stop
     * @return True if stop signal was sent, false if config not running
     */
    suspend fun stopRunner(windowId: String, configId: String): Boolean

    /**
     * Re-run a configuration: stop current process (if running) and run again.
     *
     * @param config The run configuration to re-run
     * @param windowId The window ID that initiated the run (Issue #498)
     * @param onTerminalCreated Callback when terminal tab is created
     * @return The terminal tab ID
     */
    suspend fun rerunRunner(
        config: RunConfiguration,
        windowId: String,
        onTerminalCreated: (String) -> Unit = {}
    ): String

    /**
     * Mark a runner terminal as stopped (process exited).
     * Called when the terminal process exits.
     *
     * @param terminalId The terminal tab ID
     */
    fun markTerminalStopped(terminalId: String)

    /**
     * Remove tracking for a terminal tab (when tab is closed).
     *
     * @param windowId The window ID
     * @param terminalId The terminal tab ID
     */
    fun removeTerminal(windowId: String, terminalId: String)

    /**
     * Get the configuration ID associated with a terminal tab.
     *
     * @param terminalId The terminal tab ID
     * @return The configuration ID, or null if not a runner terminal
     */
    fun getConfigForTerminal(terminalId: String): String?

    /**
     * Remove a specific config from tracking (when its tab is closed in sidebar).
     * Unlike removeTerminal which removes all configs for a terminal,
     * this only removes one specific config.
     *
     * @param windowId The window ID
     * @param configId The configuration ID to remove
     */
    fun removeConfig(windowId: String, configId: String)

    /**
     * Open a runner command in the sidebar terminal panel.
     * Creates a new tab in the sidebar terminal with the given command.
     * Also updates the configId → terminalId mapping to use SIDEBAR_TERMINAL_ID.
     *
     * @param windowId The window ID for window-scoped terminal state
     * @param configId The configuration ID for tracking
     * @param command The command to run
     * @param workingDirectory Optional working directory
     * @param tabTitle The title for the terminal tab
     * @param isRerun If true, sends Ctrl+C first to stop any running process
     * @return True if the sidebar terminal exists and tab was created
     */
    fun openInSidebarTerminal(
        windowId: String,
        configId: String,
        command: String,
        workingDirectory: String?,
        tabTitle: String,
        isRerun: Boolean = false
    ): Boolean

    /**
     * Register an externally-opened sidebar run (e.g. the MCP `run_in_sidebar` tool
     * driven from a Claude session) with the runner so the top-bar runner reflects it:
     * adds the config to run history, selects it in the given window's dropdown, and
     * marks it running (keyed to SIDEBAR_TERMINAL_ID, like [openInSidebarTerminal]).
     *
     * Unlike [openInSidebarTerminal], this does NOT open the terminal tab — the caller
     * already opened it. The running state is cleared by the existing removeTerminal /
     * removeConfig close-callbacks when the sidebar tab closes.
     *
     * @param windowId The window the run belongs to
     * @param configId Stable id for the run (reused across re-runs)
     * @param command The command being run (stored on the synthesized configuration)
     * @param workingDirectory Optional working directory
     * @param name Display name for the top-bar dropdown entry
     */
    fun registerSidebarRun(
        windowId: String,
        configId: String,
        command: String,
        workingDirectory: String?,
        name: String
    )
}
