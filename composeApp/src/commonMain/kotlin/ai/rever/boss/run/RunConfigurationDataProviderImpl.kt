package ai.rever.boss.run

import ai.rever.boss.components.events.RunEventBus
import ai.rever.boss.plugin.api.LanguageData
import ai.rever.boss.plugin.api.RunConfigurationData
import ai.rever.boss.plugin.api.RunConfigurationDataProvider
import ai.rever.boss.plugin.api.RunConfigurationTypeData
import ai.rever.boss.plugin.run.Language
import ai.rever.boss.plugin.run.RunConfiguration
import ai.rever.boss.plugin.run.RunConfigurationType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Implementation of RunConfigurationDataProvider that wraps RunConfigurationManager and RunEventBus.
 *
 * This adapter bridges the plugin API with the actual implementation in composeApp.
 */
class RunConfigurationDataProviderImpl : RunConfigurationDataProvider {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Map internal types to plugin API types
    private val _detectedConfigurations = MutableStateFlow<List<RunConfigurationData>>(emptyList())
    override val detectedConfigurations: StateFlow<List<RunConfigurationData>> = _detectedConfigurations

    override val isScanning: StateFlow<Boolean> = RunConfigurationManager.isScanning

    override val lastError: StateFlow<String?> = RunConfigurationManager.lastError

    init {
        // Map detected configurations from internal types to plugin API types
        scope.launch {
            RunConfigurationManager.detectedConfigurations.collect { configs ->
                _detectedConfigurations.value = configs.map { it.toPluginData() }
            }
        }
    }

    override suspend fun scanProject(projectPath: String, windowId: String) {
        RunEventBus.scanProject(projectPath, sourceWindowId = windowId)
    }

    override suspend fun execute(config: RunConfigurationData, windowId: String) {
        // Convert back to internal type for execution
        val internalConfig = config.toInternal()
        RunEventBus.execute(internalConfig, sourceWindowId = windowId)
    }

    override suspend fun clearError() {
        RunConfigurationManager.clearError()
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// TYPE CONVERSION EXTENSIONS
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Convert internal RunConfiguration to plugin API RunConfigurationData.
 */
private fun RunConfiguration.toPluginData(): RunConfigurationData {
    return RunConfigurationData(
        id = id,
        name = name,
        type = type.toPluginData(),
        filePath = filePath,
        lineNumber = lineNumber,
        language = language.toPluginData(),
        command = command,
        workingDirectory = workingDirectory,
        environmentVariables = environmentVariables,
        arguments = arguments,
        isAutoDetected = isAutoDetected,
        timestamp = timestamp
    )
}

/**
 * Convert plugin API RunConfigurationData back to internal RunConfiguration.
 */
private fun RunConfigurationData.toInternal(): RunConfiguration {
    return RunConfiguration(
        id = id,
        name = name,
        type = type.toInternal(),
        filePath = filePath,
        lineNumber = lineNumber,
        language = language.toInternal(),
        command = command,
        workingDirectory = workingDirectory,
        environmentVariables = environmentVariables,
        arguments = arguments,
        isAutoDetected = isAutoDetected,
        timestamp = timestamp
    )
}

/**
 * Convert internal RunConfigurationType to plugin API type.
 */
private fun RunConfigurationType.toPluginData(): RunConfigurationTypeData {
    return when (this) {
        RunConfigurationType.MAIN_FUNCTION -> RunConfigurationTypeData.MAIN_FUNCTION
        RunConfigurationType.SCRIPT -> RunConfigurationTypeData.SCRIPT
        RunConfigurationType.TEST -> RunConfigurationTypeData.TEST
        RunConfigurationType.CUSTOM -> RunConfigurationTypeData.CUSTOM
    }
}

/**
 * Convert plugin API type back to internal RunConfigurationType.
 */
private fun RunConfigurationTypeData.toInternal(): RunConfigurationType {
    return when (this) {
        RunConfigurationTypeData.MAIN_FUNCTION -> RunConfigurationType.MAIN_FUNCTION
        RunConfigurationTypeData.SCRIPT -> RunConfigurationType.SCRIPT
        RunConfigurationTypeData.TEST -> RunConfigurationType.TEST
        RunConfigurationTypeData.CUSTOM -> RunConfigurationType.CUSTOM
    }
}

/**
 * Convert internal Language to plugin API LanguageData.
 */
private fun Language.toPluginData(): LanguageData {
    return when (this) {
        Language.KOTLIN -> LanguageData.KOTLIN
        Language.JAVA -> LanguageData.JAVA
        Language.PYTHON -> LanguageData.PYTHON
        Language.JAVASCRIPT -> LanguageData.JAVASCRIPT
        Language.TYPESCRIPT -> LanguageData.TYPESCRIPT
        Language.GO -> LanguageData.GO
        Language.RUST -> LanguageData.RUST
        Language.UNKNOWN -> LanguageData.UNKNOWN
    }
}

/**
 * Convert plugin API LanguageData back to internal Language.
 */
private fun LanguageData.toInternal(): Language {
    return when (this) {
        LanguageData.KOTLIN -> Language.KOTLIN
        LanguageData.JAVA -> Language.JAVA
        LanguageData.PYTHON -> Language.PYTHON
        LanguageData.JAVASCRIPT -> Language.JAVASCRIPT
        LanguageData.TYPESCRIPT -> Language.TYPESCRIPT
        LanguageData.GO -> Language.GO
        LanguageData.RUST -> Language.RUST
        LanguageData.UNKNOWN -> Language.UNKNOWN
    }
}
