package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.LanguageData
import ai.rever.boss.plugin.api.RunConfigurationData
import ai.rever.boss.plugin.api.RunConfigurationDataProvider
import ai.rever.boss.plugin.api.RunConfigurationTypeData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class RunConfigServiceBridge(
    private val provider: RunConfigurationDataProvider,
) : RunConfigurationServiceGrpcKt.RunConfigurationServiceCoroutineImplBase() {

    override fun watchDetectedConfigurations(request: Empty): Flow<RunConfigListResponse> = flow {
        provider.detectedConfigurations.collect { configs ->
            emit(
                RunConfigListResponse.newBuilder()
                    .addAllConfigurations(configs.map { it.toProto() })
                    .build()
            )
        }
    }

    override fun watchIsScanning(request: Empty): Flow<RunConfigBoolResponse> = flow {
        provider.isScanning.collect { scanning ->
            emit(RunConfigBoolResponse.newBuilder().setValue(scanning).build())
        }
    }

    override fun watchLastError(request: Empty): Flow<RunConfigStringResponse> = flow {
        provider.lastError.collect { error ->
            emit(RunConfigStringResponse.newBuilder().setValue(error ?: "").build())
        }
    }

    override suspend fun scanProject(request: ScanProjectRequest): Empty {
        provider.scanProject(request.projectPath, request.windowId)
        return Empty.getDefaultInstance()
    }

    override suspend fun execute(request: ExecuteConfigRequest): Empty {
        val config = request.configuration.toData()
        provider.execute(config, request.windowId)
        return Empty.getDefaultInstance()
    }

    override suspend fun clearError(request: Empty): Empty {
        provider.clearError()
        return Empty.getDefaultInstance()
    }

    private fun RunConfigurationData.toProto(): RunConfigurationProto =
        RunConfigurationProto.newBuilder()
            .setId(id)
            .setName(name)
            .setType(
                when (type) {
                    RunConfigurationTypeData.MAIN_FUNCTION -> RunConfigType.RUN_CONFIG_TYPE_MAIN_FUNCTION
                    RunConfigurationTypeData.SCRIPT -> RunConfigType.RUN_CONFIG_TYPE_SCRIPT
                    RunConfigurationTypeData.TEST -> RunConfigType.RUN_CONFIG_TYPE_TEST
                    RunConfigurationTypeData.CUSTOM -> RunConfigType.RUN_CONFIG_TYPE_CUSTOM
                }
            )
            .setFilePath(filePath)
            .setLineNumber(lineNumber)
            .setLanguage(
                when (language) {
                    LanguageData.KOTLIN -> RunConfigLanguage.RUN_CONFIG_LANGUAGE_KOTLIN
                    LanguageData.JAVA -> RunConfigLanguage.RUN_CONFIG_LANGUAGE_JAVA
                    LanguageData.PYTHON -> RunConfigLanguage.RUN_CONFIG_LANGUAGE_PYTHON
                    LanguageData.JAVASCRIPT -> RunConfigLanguage.RUN_CONFIG_LANGUAGE_JAVASCRIPT
                    LanguageData.TYPESCRIPT -> RunConfigLanguage.RUN_CONFIG_LANGUAGE_TYPESCRIPT
                    LanguageData.GO -> RunConfigLanguage.RUN_CONFIG_LANGUAGE_GO
                    LanguageData.RUST -> RunConfigLanguage.RUN_CONFIG_LANGUAGE_RUST
                    LanguageData.UNKNOWN -> RunConfigLanguage.RUN_CONFIG_LANGUAGE_UNKNOWN
                }
            )
            .setCommand(command)
            .setWorkingDirectory(workingDirectory)
            .putAllEnvironmentVariables(environmentVariables)
            .setArguments(arguments)
            .setIsAutoDetected(isAutoDetected)
            .setTimestamp(timestamp)
            .build()

    private fun RunConfigurationProto.toData(): RunConfigurationData =
        RunConfigurationData(
            id = id,
            name = name,
            type = when (type) {
                RunConfigType.RUN_CONFIG_TYPE_MAIN_FUNCTION -> RunConfigurationTypeData.MAIN_FUNCTION
                RunConfigType.RUN_CONFIG_TYPE_SCRIPT -> RunConfigurationTypeData.SCRIPT
                RunConfigType.RUN_CONFIG_TYPE_TEST -> RunConfigurationTypeData.TEST
                else -> RunConfigurationTypeData.CUSTOM
            },
            filePath = filePath,
            lineNumber = lineNumber,
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
            command = command,
            workingDirectory = workingDirectory,
            environmentVariables = environmentVariablesMap,
            arguments = arguments,
            isAutoDetected = isAutoDetected,
            timestamp = timestamp,
        )
}
