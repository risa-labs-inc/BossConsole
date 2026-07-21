package ai.rever.boss.window

import ai.rever.boss.run.RunConfiguration
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Window-scoped state for the selected run configuration.
 * Each window maintains its own selected configuration independently.
 *
 * This allows different windows to have different configurations selected
 * in their top run bar dropdowns without affecting each other.
 */
class WindowRunnerState(val windowId: String) {
    private val logger = BossLogger.forComponent("WindowRunnerState")
    private val _selectedConfiguration = MutableStateFlow<RunConfiguration?>(null)
    val selectedConfiguration: StateFlow<RunConfiguration?> = _selectedConfiguration.asStateFlow()

    /**
     * Select a configuration for this window.
     * This does not affect other windows' selections.
     */
    fun selectConfiguration(config: RunConfiguration?) {
        _selectedConfiguration.value = config
        logger.debug(LogCategory.UI, "Selected configuration", mapOf("windowId" to windowId, "name" to (config?.name ?: "none")))
    }

    /**
     * Get the currently selected configuration.
     */
    fun currentConfiguration(): RunConfiguration? = _selectedConfiguration.value
}
