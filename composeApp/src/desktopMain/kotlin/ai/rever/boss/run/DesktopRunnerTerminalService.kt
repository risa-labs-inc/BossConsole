package ai.rever.boss.run

import ai.rever.boss.components.events.RunnerTerminalEventBus
import ai.rever.boss.plugin.api.SIDEBAR_TERMINAL_ID
import ai.rever.boss.plugin.run.Language
import ai.rever.boss.services.terminal.TerminalAPIAccess
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.WindowRunnerStateRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Desktop implementation of RunnerTerminalService.
 * Manages runner terminals with configuration tracking.
 *
 * Features:
 * - Stop closes the terminal tab and clears tracking
 * - Re-run sends Ctrl+C, waits, then runs new command in same tab
 * - Multiple configurations can run in sidebar mode (each tracked separately)
 *
 * Issue #347: Runner should open in terminal sidebar panel with run/stop state management
 */
actual object RunnerTerminalService {
    private val logger = BossLogger.forComponent("RunnerTerminalService")

    // Single lock for ALL state updates - used by both suspend and non-suspend functions
    // Using ReentrantLock instead of Mutex + synchronized to prevent independent lock issues
    private val stateLock = ReentrantLock()

    // Map: configId → terminalTabId
    private val _configToTerminal = MutableStateFlow<Map<String, String>>(emptyMap())
    actual val configToTerminal: StateFlow<Map<String, String>> = _configToTerminal.asStateFlow()

    // Map: configId → Set<windowId> (a config can run in multiple windows simultaneously)
    // Issue #498: Made observable as StateFlow so UI can recompose when window-config mappings change
    private val _configToWindows = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    actual val configToWindows: StateFlow<Map<String, Set<String>>> = _configToWindows.asStateFlow()

    // Reverse map: terminalTabId → Set<configId> (supports multiple configs per terminal, e.g., sidebar)
    // Thread-safe: uses ConcurrentHashMap with concurrent sets
    private val terminalToConfigs = ConcurrentHashMap<String, ConcurrentHashMap.KeySetView<String, Boolean>>()

    // Set of currently running configuration IDs
    private val _runningConfigs = MutableStateFlow<Set<String>>(emptySet())
    actual val runningConfigs: StateFlow<Set<String>> = _runningConfigs.asStateFlow()

    /**
     * Add a config to a terminal's tracking set.
     */
    private fun addConfigToTerminal(
        terminalId: String,
        configId: String,
    ) {
        terminalToConfigs
            .computeIfAbsent(terminalId) {
                ConcurrentHashMap.newKeySet()
            }.add(configId)
    }

    /**
     * Remove a config from a terminal's tracking set.
     */
    private fun removeConfigFromTerminal(
        terminalId: String,
        configId: String,
    ) {
        terminalToConfigs[terminalId]?.remove(configId)
    }

    /**
     * Add a window to a config's running windows set. (StateFlow update)
     */
    private fun addWindowToConfig(
        configId: String,
        windowId: String,
    ) {
        _configToWindows.update { current ->
            val windows = current[configId]?.let { it + windowId } ?: setOf(windowId)
            current + (configId to windows)
        }
    }

    /**
     * Remove a window from a config's running windows set. (StateFlow update)
     */
    private fun removeWindowFromConfig(
        configId: String,
        windowId: String,
    ) {
        _configToWindows.update { current ->
            val windows = current[configId]?.minus(windowId)
            if (windows.isNullOrEmpty()) {
                current - configId
            } else {
                current + (configId to windows)
            }
        }
    }

    /**
     * Remove all windows from a config (when terminal stops/closes).
     */
    private fun removeAllWindowsFromConfig(configId: String) {
        _configToWindows.update { it - configId }
    }

    /**
     * Check if a specific configuration is currently running.
     */
    actual fun isConfigRunning(configId: String): Boolean = configId in _runningConfigs.value

    /**
     * Check if a specific configuration is currently running in a specific window.
     * Used for per-window button state isolation (Issue #498).
     *
     * @param windowId The window ID to check
     * @param configId The configuration ID to check
     * @return True if the config is running in the specified window
     */
    actual fun isConfigRunningInWindow(
        windowId: String,
        configId: String,
    ): Boolean = _configToWindows.value[configId]?.contains(windowId) == true

    /**
     * Open or reuse a runner terminal for the given configuration.
     * @param windowId The window ID that initiated the run (Issue #498)
     */
    actual suspend fun openRunnerTerminal(
        config: RunConfiguration,
        windowId: String,
        onTerminalCreated: (String) -> Unit,
    ): String {
        // Build the command outside lock (no state access needed)
        val command = buildFullCommand(config)

        // Atomic state update under lock
        val (terminalId, isRerun) =
            stateLock.withLock {
                val existingTerminalId = _configToTerminal.value[config.id]
                val newTerminalId = existingTerminalId ?: "$RUNNER_TERMINAL_PREFIX${config.id}-${System.currentTimeMillis()}"

                // Update all state atomically
                _configToTerminal.update { it + (config.id to newTerminalId) }
                addConfigToTerminal(newTerminalId, config.id)
                _runningConfigs.update { it + config.id }
                addWindowToConfig(config.id, windowId)

                newTerminalId to (existingTerminalId != null)
            }

        // Emit event outside lock (avoid holding lock during I/O)
        logger.debug(LogCategory.TERMINAL, "Opening terminal for config", mapOf("configName" to config.name, "command" to command))
        RunnerTerminalEventBus.openRunnerTerminal(
            terminalId = terminalId,
            command = command,
            configId = config.id,
            configName = config.name,
            workingDirectory = resolveWorkingDirectory(config).ifBlank { null },
            isRerun = isRerun,
            sourceWindowId = windowId,
        )

        onTerminalCreated(terminalId)
        return terminalId
    }

    /**
     * Stop the runner for a configuration by closing its terminal tab.
     *
     * This closes the active tab which terminates the running process.
     * @param windowId The window ID that initiated the stop (Issue #498)
     * @param configId The configuration ID to stop
     */
    actual suspend fun stopRunner(
        windowId: String,
        configId: String,
    ): Boolean {
        // Get terminal ID, validate under lock
        val terminalId =
            stateLock.withLock {
                if (!isConfigRunningInWindow(windowId, configId)) {
                    logger.debug(
                        LogCategory.TERMINAL,
                        "Config not running in window",
                        mapOf("configId" to configId, "windowId" to windowId),
                    )
                    return false
                }

                val id = _configToTerminal.value[configId]
                if (id == null) {
                    logger.debug(LogCategory.TERMINAL, "No terminal found for config", mapOf("configId" to configId))
                    return false
                }

                // Remove this window from the config's window set
                removeWindowFromConfig(configId, windowId)

                // Only clean up global state if no more windows are running this config
                if (_configToWindows.value[configId] == null) {
                    _configToTerminal.update { it - configId }
                    removeConfigFromTerminal(id, configId)
                    _runningConfigs.update { it - configId }
                }

                id
            }

        // Perform I/O operations outside lock (window-scoped)
        val closed = TerminalAPIAccess.closeActiveTab(windowId, terminalId)
        if (closed) {
            logger.debug(
                LogCategory.TERMINAL,
                "Closed terminal tab",
                mapOf(
                    "terminalId" to terminalId,
                    "configId" to configId,
                    "windowId" to windowId,
                ),
            )
        } else {
            logger.debug(
                LogCategory.TERMINAL,
                "Failed to close tab - terminal not found",
                mapOf(
                    "terminalId" to terminalId,
                    "windowId" to windowId,
                ),
            )
        }

        // Clear sidebar tab tracking if this was a sidebar config (window-scoped)
        if (terminalId == SIDEBAR_TERMINAL_ID) {
            TerminalAPIAccess.removeSidebarConfigTracking(windowId, configId)
        }

        // Emit stop event for any additional UI handling
        RunnerTerminalEventBus.stopRunnerTerminal(terminalId, configId, sourceWindowId = windowId)

        return closed
    }

    /**
     * Re-run a configuration: stop current process (if running) and run again.
     *
     * For main panel: Sends Ctrl+C to stop the current process, closes the old terminal,
     * and creates a new one with the same command.
     * For sidebar panel: Ctrl+C is handled by openInSidebarTerminal via the isRerun flag.
     *
     * @param windowId The window ID that initiated the run (Issue #498)
     */
    actual suspend fun rerunRunner(
        config: RunConfiguration,
        windowId: String,
        onTerminalCreated: (String) -> Unit,
    ): String {
        logger.debug(LogCategory.TERMINAL, "Re-running config", mapOf("configName" to config.name))

        // Check if using sidebar mode - Ctrl+C will be handled by openInSidebarTerminal
        val usesSidebar = RunnerSettingsManager.currentSettings.value.terminalTarget == RunnerTerminalTarget.SIDEBAR_PANEL

        // Build command outside lock
        val command = buildFullCommand(config)

        // Atomic state update under lock
        val (terminalId, existingTerminalId, existingWindowId) =
            stateLock.withLock {
                val existingId = _configToTerminal.value[config.id]
                val existingWinId = _configToWindows.value[config.id]?.firstOrNull()

                // Stop existing process and close terminal (only for main panel mode)
                if (existingId != null && !usesSidebar) {
                    removeConfigFromTerminal(existingId, config.id)
                }

                // Create new terminal with fresh ID
                val newTerminalId = "$RUNNER_TERMINAL_PREFIX${config.id}-${System.currentTimeMillis()}"

                // Update all state atomically
                _configToTerminal.update { it + (config.id to newTerminalId) }
                addConfigToTerminal(newTerminalId, config.id)
                _runningConfigs.update { it + config.id }
                addWindowToConfig(config.id, windowId)

                Triple(newTerminalId, existingId, existingWinId)
            }

        // Perform I/O operations outside lock with error handling
        if (existingTerminalId != null && existingWindowId != null && !usesSidebar) {
            try {
                // Send Ctrl+C to stop the running process (window-scoped)
                val sent = TerminalAPIAccess.sendInterrupt(existingWindowId, existingTerminalId)
                if (sent) {
                    logger.debug(LogCategory.TERMINAL, "Sent Ctrl+C to stop existing process", mapOf("windowId" to existingWindowId))
                }
                // Close the terminal tab (in the window where it exists)
                RunnerTerminalEventBus.closeRunnerTerminal(existingTerminalId, sourceWindowId = existingWindowId)
            } catch (e: Exception) {
                // Log error but continue - we've already updated state for new terminal
                logger.warn(LogCategory.TERMINAL, "Error stopping existing terminal", mapOf("error" to (e.message ?: "unknown")))
            }
        }

        // Emit event to create terminal
        RunnerTerminalEventBus.openRunnerTerminal(
            terminalId = terminalId,
            command = command,
            configId = config.id,
            configName = config.name,
            workingDirectory = resolveWorkingDirectory(config).ifBlank { null },
            isRerun = true,
            sourceWindowId = windowId,
        )

        onTerminalCreated(terminalId)
        return terminalId
    }

    /**
     * Mark a runner terminal as stopped (process exited).
     * For terminals with multiple configs (sidebar), marks all as stopped.
     * Also cleans up all mappings to prevent memory leaks.
     */
    actual fun markTerminalStopped(terminalId: String) {
        stateLock.withLock {
            val configIds = terminalToConfigs.remove(terminalId)?.toSet() ?: emptySet()
            if (configIds.isNotEmpty()) {
                // Clean up forward mapping
                _configToTerminal.update { current ->
                    current.filterKeys { it !in configIds }
                }
                _runningConfigs.update { it - configIds }
                // Clean up window mapping
                configIds.forEach { removeAllWindowsFromConfig(it) }
                logger.debug(LogCategory.TERMINAL, "Terminal stopped", mapOf("terminalId" to terminalId, "configs" to configIds.toString()))
            }
        }
    }

    /**
     * Remove tracking for a terminal tab (when tab is closed).
     * For terminals with multiple configs (sidebar), removes all.
     *
     * @param windowId The window ID
     * @param terminalId The terminal tab ID
     */
    actual fun removeTerminal(
        windowId: String,
        terminalId: String,
    ) {
        val isSidebar =
            stateLock.withLock {
                val configIds = terminalToConfigs.remove(terminalId)?.toSet() ?: emptySet()
                if (configIds.isNotEmpty()) {
                    _configToTerminal.update { current ->
                        current.filterKeys { it !in configIds }
                    }
                    _runningConfigs.update { it - configIds }
                    // Clean up window mapping
                    configIds.forEach { removeAllWindowsFromConfig(it) }
                    logger.debug(
                        LogCategory.TERMINAL,
                        "Terminal removed",
                        mapOf(
                            "terminalId" to terminalId,
                            "configs" to configIds.toString(),
                            "windowId" to windowId,
                        ),
                    )
                }
                terminalId == SIDEBAR_TERMINAL_ID
            }

        // Clear sidebar tab tracking for this window outside lock (I/O operation)
        if (isSidebar) {
            TerminalAPIAccess.clearSidebarConfigTrackingForWindow(windowId)
        }
    }

    /**
     * Get a configuration ID associated with a terminal tab.
     * For terminals with multiple configs (sidebar), returns any one of them.
     */
    actual fun getConfigForTerminal(terminalId: String): String? = terminalToConfigs[terminalId]?.firstOrNull()

    /**
     * Remove a specific config from tracking (when its tab is closed in sidebar).
     * Unlike removeTerminal which removes all configs for a terminal,
     * this only removes one specific config.
     *
     * @param windowId The window ID (used for sidebar config tracking)
     * @param configId The configuration ID to remove
     */
    actual fun removeConfig(
        windowId: String,
        configId: String,
    ) {
        stateLock.withLock {
            val terminalId = _configToTerminal.value[configId]
            if (terminalId != null) {
                _configToTerminal.update { it - configId }
                removeConfigFromTerminal(terminalId, configId)
                _runningConfigs.update { it - configId }
                removeWindowFromConfig(configId, windowId)
                logger.debug(
                    LogCategory.TERMINAL,
                    "Config removed",
                    mapOf(
                        "configId" to configId,
                        "terminalId" to terminalId,
                        "windowId" to windowId,
                    ),
                )
            }
        }
    }

    /**
     * Clean up all runner state associated with a window.
     * Called when a window is closed to prevent memory leaks.
     *
     * @param windowId The window ID being closed
     */
    fun cleanupWindow(windowId: String) {
        stateLock.withLock {
            val configsToRemove =
                _configToWindows.value.entries
                    .filter { windowId in it.value }
                    .map { it.key }

            configsToRemove.forEach { configId ->
                val terminalId = _configToTerminal.value[configId]
                if (terminalId != null) {
                    removeConfigFromTerminal(terminalId, configId)
                }
                _configToTerminal.update { it - configId }
                _runningConfigs.update { it - configId }
                removeWindowFromConfig(configId, windowId)
            }

            if (configsToRemove.isNotEmpty()) {
                logger.debug(
                    LogCategory.TERMINAL,
                    "Cleaned up configs for closed window",
                    mapOf(
                        "count" to configsToRemove.size,
                        "windowId" to windowId,
                    ),
                )
            }
        }
    }

    /**
     * Open a runner command in the sidebar terminal panel.
     * Creates a new tab in the sidebar terminal with the given command.
     * Updates tracking to map configId → SIDEBAR_TERMINAL_ID so stop works correctly.
     *
     * State is updated BEFORE terminal operation to prevent race conditions where
     * another thread sees inconsistent state. If the operation fails, state is rolled back.
     *
     * @param windowId The window ID for window-scoped terminal state
     * @param configId The configuration ID for tracking
     * @param command The command to run
     * @param workingDirectory Optional working directory
     * @param tabTitle The title for the terminal tab
     * @param isRerun If true, sends Ctrl+C first to stop any running process
     */
    actual fun openInSidebarTerminal(
        windowId: String,
        configId: String,
        command: String,
        workingDirectory: String?,
        tabTitle: String,
        isRerun: Boolean,
    ): Boolean {
        // Update state BEFORE terminal operation (with rollback on failure)
        // This ensures UI updates immediately when user clicks run
        stateLock.withLock {
            _configToTerminal.update { it + (configId to SIDEBAR_TERMINAL_ID) }
            addConfigToTerminal(SIDEBAR_TERMINAL_ID, configId)
            _runningConfigs.update { it + configId }
            addWindowToConfig(configId, windowId)
        }

        val success =
            TerminalAPIAccess.newSidebarTab(
                windowId = windowId,
                command = command,
                workingDirectory = workingDirectory,
                configId = configId,
                isRerun = isRerun,
            )

        if (!success) {
            // Roll back state on failure
            stateLock.withLock {
                _configToTerminal.update { it - configId }
                removeConfigFromTerminal(SIDEBAR_TERMINAL_ID, configId)
                _runningConfigs.update { it - configId }
                removeWindowFromConfig(configId, windowId)
            }
            logger.debug(LogCategory.TERMINAL, "Failed to open in sidebar terminal - panel may not be open", mapOf("windowId" to windowId))
        } else {
            logger.debug(
                LogCategory.TERMINAL,
                "Opened command in sidebar terminal",
                mapOf(
                    "tabTitle" to tabTitle,
                    "windowId" to windowId,
                    "mappedTo" to SIDEBAR_TERMINAL_ID,
                    "isRerun" to isRerun,
                ),
            )
        }
        return success
    }

    // Scope for fire-and-forget history persistence from registerSidebarRun (the
    // public entry is non-suspend so it's reflection-friendly for the MCP host tools).
    private val registrationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    actual fun registerSidebarRun(
        windowId: String,
        configId: String,
        command: String,
        workingDirectory: String?,
        name: String,
    ) {
        // Synthesize an ad-hoc run configuration. A stable filePath keyed by configId
        // makes addConfiguration() dedupe re-runs to a single history entry, and keeps
        // the dropdown selection and the running-state map keyed by the same id.
        val config =
            RunConfiguration(
                id = configId,
                name = name,
                type = RunConfigurationType.CUSTOM,
                filePath = "mcp-run:$configId",
                lineNumber = 0,
                language = Language.UNKNOWN,
                command = command,
                workingDirectory = workingDirectory ?: "",
                isAutoDetected = false,
            )

        // Add to run history (top-bar dropdown entries) — addConfiguration is suspend
        // and dedupes by filePath, so fire it on a scope.
        registrationScope.launch {
            try {
                RunConfigurationManager.addConfiguration(config)
            } catch (e: Exception) {
                logger.warn(LogCategory.TERMINAL, "registerSidebarRun: addConfiguration failed", error = e)
            }
        }

        // Select it in this window's top-bar dropdown.
        WindowRunnerStateRegistry.get(windowId)?.selectConfiguration(config)

        // Mark running — same state block as openInSidebarTerminal, minus opening the
        // tab (the caller already opened the sidebar terminal). Cleared on tab close
        // via the existing removeTerminal / removeConfig callbacks.
        stateLock.withLock {
            _configToTerminal.update { it + (configId to SIDEBAR_TERMINAL_ID) }
            addConfigToTerminal(SIDEBAR_TERMINAL_ID, configId)
            _runningConfigs.update { it + configId }
            addWindowToConfig(configId, windowId)
        }

        logger.debug(
            LogCategory.TERMINAL,
            "Registered sidebar run with runner",
            mapOf("windowId" to windowId, "configId" to configId, "name" to name),
        )
    }

    // Detector used to re-resolve a config's project root from its source file.
    private val projectRootDetector by lazy { createMainFunctionDetector() }

    /**
     * Resolve the directory the runner should execute in. Re-derives the script's own
     * project root from [RunConfiguration.filePath] at run time (e.g. the inner repo
     * holding ./gradlew), so the runner always runs at the script location rather than
     * a stale or workspace-root path stored on a saved config. Run configs are
     * auto-detected (there is no user-set working dir to preserve), so deriving from the
     * file is safe and also corrects configs saved before the detection fix. Falls back
     * to the stored working directory when the file isn't available.
     */
    private fun resolveWorkingDirectory(config: RunConfiguration): String {
        val filePath = config.filePath
        if (filePath.isNotBlank() && File(filePath).exists()) {
            val root = projectRootDetector.findProjectRoot(filePath)
            if (root.isNotBlank()) return root
        }
        return config.workingDirectory
    }

    /**
     * Build the full command including cd to working directory.
     * Working directory is quoted and escaped to handle paths with spaces,
     * quotes, and other special characters safely.
     */
    private fun buildFullCommand(config: RunConfiguration): String =
        ShellUtils.buildCommandWithWorkingDirectory(config.command, resolveWorkingDirectory(config))
}
