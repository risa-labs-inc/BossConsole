package ai.rever.boss.plugin.ipc

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.LanguageData
import ai.rever.boss.plugin.api.RunConfigurationData
import ai.rever.boss.plugin.api.RunConfigurationDataProvider
import ai.rever.boss.plugin.api.RunConfigurationTypeData
import io.grpc.ManagedChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * IPC proxy implementation of RunConfigurationDataProvider.
 */
class RunConfigDataProviderProxy(
    channel: ManagedChannel,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : RunConfigurationDataProvider {

    private val stub = RunConfigurationServiceGrpcKt.RunConfigurationServiceCoroutineStub(channel)

    private val _detectedConfigurations = MutableStateFlow<List<RunConfigurationData>>(emptyList())
    override val detectedConfigurations: StateFlow<List<RunConfigurationData>> = _detectedConfigurations.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    override val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    init {
        scope.launch { watchConfigurations() }
        scope.launch { watchIsScanning() }
        scope.launch { watchLastError() }
    }

    private suspend fun watchConfigurations() {
        var delayMs = 1_000L
        while (scope.isActive) {
            try {
                stub.watchDetectedConfigurations(Empty.getDefaultInstance()).collect { response ->
                    _detectedConfigurations.value = response.configurationsList.map { it.toData() }
                }
                delayMs = 1_000L
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { delay(delayMs); delayMs = (delayMs * 2).coerceAtMost(30_000L) }
        }
    }

    private suspend fun watchIsScanning() {
        var delayMs = 1_000L
        while (scope.isActive) {
            try {
                stub.watchIsScanning(Empty.getDefaultInstance()).collect { response ->
                    _isScanning.value = response.value
                }
                delayMs = 1_000L
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { delay(delayMs); delayMs = (delayMs * 2).coerceAtMost(30_000L) }
        }
    }

    private suspend fun watchLastError() {
        var delayMs = 1_000L
        while (scope.isActive) {
            try {
                stub.watchLastError(Empty.getDefaultInstance()).collect { response ->
                    _lastError.value = response.value.takeIf { it.isNotEmpty() }
                }
                delayMs = 1_000L
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { delay(delayMs); delayMs = (delayMs * 2).coerceAtMost(30_000L) }
        }
    }

    override suspend fun scanProject(projectPath: String, windowId: String) {
        try {
            stub.scanProject(
                ScanProjectRequest.newBuilder().setProjectPath(projectPath).setWindowId(windowId).build()
            )
        } catch (_: Exception) {}
    }

    override suspend fun execute(config: RunConfigurationData, windowId: String) {
        try {
            stub.execute(
                ExecuteConfigRequest.newBuilder()
                    .setConfiguration(config.toProto())
                    .setWindowId(windowId)
                    .build()
            )
        } catch (_: Exception) {}
    }

    override suspend fun clearError() {
        try { stub.clearError(Empty.getDefaultInstance()) } catch (_: Exception) {}
    }

    private fun RunConfigurationProto.toData() = RunConfigurationData(
        id = id, name = name,
        type = when (type) {
            RunConfigType.RUN_CONFIG_TYPE_MAIN_FUNCTION -> RunConfigurationTypeData.MAIN_FUNCTION
            RunConfigType.RUN_CONFIG_TYPE_SCRIPT -> RunConfigurationTypeData.SCRIPT
            RunConfigType.RUN_CONFIG_TYPE_TEST -> RunConfigurationTypeData.TEST
            else -> RunConfigurationTypeData.CUSTOM
        },
        filePath = filePath, lineNumber = lineNumber,
        language = when (language) {
            RunConfigLanguage.RUN_CONFIG_LANGUAGE_KOTLIN -> LanguageData.KOTLIN
            RunConfigLanguage.RUN_CONFIG_LANGUAGE_JAVA -> LanguageData.JAVA
            RunConfigLanguage.RUN_CONFIG_LANGUAGE_PYTHON -> LanguageData.PYTHON
            RunConfigLanguage.RUN_CONFIG_LANGUAGE_JAVASCRIPT -> LanguageData.JAVASCRIPT
            RunConfigLanguage.RUN_CONFIG_LANGUAGE_TYPESCRIPT -> LanguageData.TYPESCRIPT
            RunConfigLanguage.RUN_CONFIG_LANGUAGE_GO -> LanguageData.GO
            RunConfigLanguage.RUN_CONFIG_LANGUAGE_RUST -> LanguageData.RUST
            else -> LanguageData.UNKNOWN
        },
        command = command, workingDirectory = workingDirectory,
        environmentVariables = environmentVariablesMap,
        arguments = arguments, isAutoDetected = isAutoDetected, timestamp = timestamp,
    )

    private fun RunConfigurationData.toProto(): RunConfigurationProto =
        RunConfigurationProto.newBuilder()
            .setId(id).setName(name)
            .setType(when (type) {
                RunConfigurationTypeData.MAIN_FUNCTION -> RunConfigType.RUN_CONFIG_TYPE_MAIN_FUNCTION
                RunConfigurationTypeData.SCRIPT -> RunConfigType.RUN_CONFIG_TYPE_SCRIPT
                RunConfigurationTypeData.TEST -> RunConfigType.RUN_CONFIG_TYPE_TEST
                RunConfigurationTypeData.CUSTOM -> RunConfigType.RUN_CONFIG_TYPE_CUSTOM
            })
            .setFilePath(filePath).setLineNumber(lineNumber)
            .setLanguage(when (language) {
                LanguageData.KOTLIN -> RunConfigLanguage.RUN_CONFIG_LANGUAGE_KOTLIN
                LanguageData.JAVA -> RunConfigLanguage.RUN_CONFIG_LANGUAGE_JAVA
                LanguageData.PYTHON -> RunConfigLanguage.RUN_CONFIG_LANGUAGE_PYTHON
                LanguageData.JAVASCRIPT -> RunConfigLanguage.RUN_CONFIG_LANGUAGE_JAVASCRIPT
                LanguageData.TYPESCRIPT -> RunConfigLanguage.RUN_CONFIG_LANGUAGE_TYPESCRIPT
                LanguageData.GO -> RunConfigLanguage.RUN_CONFIG_LANGUAGE_GO
                LanguageData.RUST -> RunConfigLanguage.RUN_CONFIG_LANGUAGE_RUST
                LanguageData.UNKNOWN -> RunConfigLanguage.RUN_CONFIG_LANGUAGE_UNKNOWN
            })
            .setCommand(command).setWorkingDirectory(workingDirectory)
            .putAllEnvironmentVariables(environmentVariables)
            .setArguments(arguments).setIsAutoDetected(isAutoDetected).setTimestamp(timestamp)
            .build()
}
