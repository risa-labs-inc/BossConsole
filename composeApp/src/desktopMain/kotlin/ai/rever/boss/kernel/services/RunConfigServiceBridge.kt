package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.LanguageData
import ai.rever.boss.plugin.api.RunConfigurationData
import ai.rever.boss.plugin.api.RunConfigurationDataProvider
import ai.rever.boss.plugin.api.RunConfigurationTypeData
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Kernel-side bridge for `RunConfigurationService`.
 *
 * **Every call requires a verified caller identity (BossConsole#53)**, the same requirement and
 * helper shape introduced for the Secret Service in PR #505. Stream revocation is checked
 * before each emission; an idle revoked stream is not proactively disconnected.
 *
 * [execute] gets a second, narrower guard on top, and it is the sharper one: unlike the other
 * bridges this pattern has closed so far, [ExecuteConfigRequest] does not name something to read
 * or open - it carries a full `command`, `arguments`, `working_directory` and
 * `environment_variables` that [RunConfigurationDataProvider.execute] hands straight to
 * [ai.rever.boss.run.RunExecutionService], whose own KDoc says what that does: "Opens a terminal
 * and runs the command." Before this bridge checked identity at all, `execute` was an
 * unauthenticated arbitrary-command-execution primitive - not "leaks a file" or "confuses a
 * dialog," but a caller naming any binary and argument vector it likes and having BOSS run it.
 * Requiring identity alone is not enough to close that, because an authenticated call still
 * carries the full command in its own request rather than a reference to one BOSS already knows
 * about. So [execute] does not run what the caller sent: it takes only the requested id and looks
 * up the matching entry in [RunConfigurationDataProvider.detectedConfigurations] - the
 * auto-detected configurations the project scan produced (operator-saved configurations
 * are not exposed by this provider) - and executes that provider-held copy. A request naming an id outside that set is
 * refused before [RunExecutionService] ever sees it, and every other field in the request
 * (command, arguments, working directory, environment) is discarded rather than trusted, so
 * tampering with them while reusing a real id buys nothing.
 *
 * This is process authentication, not per-window or per-project authorization:
 * authenticated plugins may still choose the scan path and target window, as the
 * in-process provider API permits. No caller-supplied command is executed.
 */
// One method per RPC the generated service base class declares, plus two small private helpers.
@Suppress("TooManyFunctions")
class RunConfigServiceBridge(
    private val provider: RunConfigurationDataProvider,
) : RunConfigurationServiceGrpcKt.RunConfigurationServiceCoroutineImplBase() {
    override fun watchDetectedConfigurations(request: Empty): Flow<RunConfigListResponse> {
        val currentIdentity = ProcessIdentityInterceptor.CURRENT_IDENTITY.get()
        return flow {
            val caller =
                currentIdentity?.invoke()
                    ?: refuseIdentity("watchDetectedConfigurations")
            provider.detectedConfigurations.collect { configs ->
                if (currentIdentity.invoke() != caller) {
                    refuseIdentity("watchDetectedConfigurations")
                }
                emit(
                    RunConfigListResponse
                        .newBuilder()
                        .addAllConfigurations(configs.map { it.toProto() })
                        .build(),
                )
            }
        }
    }

    override fun watchIsScanning(request: Empty): Flow<RunConfigBoolResponse> {
        val currentIdentity = ProcessIdentityInterceptor.CURRENT_IDENTITY.get()
        return flow {
            val caller =
                currentIdentity?.invoke()
                    ?: refuseIdentity("watchIsScanning")
            provider.isScanning.collect { scanning ->
                if (currentIdentity.invoke() != caller) {
                    refuseIdentity("watchIsScanning")
                }
                emit(RunConfigBoolResponse.newBuilder().setValue(scanning).build())
            }
        }
    }

    override fun watchLastError(request: Empty): Flow<RunConfigStringResponse> {
        val currentIdentity = ProcessIdentityInterceptor.CURRENT_IDENTITY.get()
        return flow {
            val caller =
                currentIdentity?.invoke()
                    ?: refuseIdentity("watchLastError")
            provider.lastError.collect { error ->
                if (currentIdentity.invoke() != caller) {
                    refuseIdentity("watchLastError")
                }
                emit(RunConfigStringResponse.newBuilder().setValue(error ?: "").build())
            }
        }
    }

    override suspend fun scanProject(request: ScanProjectRequest): Empty {
        val caller = authenticatedCallerOrRefuse("scanProject")
        logger.info(LogCategory.AUTH, "Run configuration scan requested", mapOf("caller" to caller))
        provider.scanProject(request.projectPath, request.windowId)
        return Empty.getDefaultInstance()
    }

    override suspend fun execute(request: ExecuteConfigRequest): Empty {
        val caller = authenticatedCallerOrRefuse("execute")
        val config = knownConfigurationOrRefuse(caller, request.configuration.id)
        logger.info(
            LogCategory.AUTH,
            "Run configuration execution requested",
            mapOf("caller" to caller, "configurationId" to config.id),
        )
        provider.execute(config, request.windowId)
        return Empty.getDefaultInstance()
    }

    override suspend fun clearError(request: Empty): Empty {
        authenticatedCallerOrRefuse("clearError")
        provider.clearError()
        return Empty.getDefaultInstance()
    }

    /**
     * Resolves [requestedId] against [RunConfigurationDataProvider.detectedConfigurations] and
     * returns the provider's own copy - never the caller's. See the class KDoc for why [execute]
     * cannot trust anything in the request beyond this id. Rescans clear the list and mint new
     * ids; callers must use ids from watchDetectedConfigurations after the scan. An old id is
     * refused, using PERMISSION_DENIED consistently with the Download bridge allowlist.
     */
    private fun knownConfigurationOrRefuse(
        caller: String,
        requestedId: String,
    ): RunConfigurationData {
        val known =
            requestedId.takeIf { it.isNotBlank() }?.let { id ->
                provider.detectedConfigurations.value.find { it.id == id }
            }
        if (known == null) {
            logger.warn(
                LogCategory.AUTH,
                "Refused execute: id is not a configuration this provider currently knows about",
                mapOf("caller" to caller, "isScanning" to provider.isScanning.value),
            )
            throw StatusException(Status.PERMISSION_DENIED.withDescription(NOT_A_KNOWN_CONFIGURATION))
        }
        return known
    }

    /**
     * Unary RPCs only: the verified per-call identity, or `PERMISSION_DENIED`.
     * Streams must capture CURRENT_IDENTITY synchronously and recheck it before each emission.
     *
     * Mirrors the helper introduced by PR #505 (BossConsole#53) - fails closed rather than let a
     * request with no credential fall through to [provider] with nothing to attribute it to.
     */
    private fun authenticatedCallerOrRefuse(rpc: String): String =
        ProcessIdentityInterceptor.AUTHENTICATED_PROCESS_ID.get() ?: refuseIdentity(rpc)

    private fun refuseIdentity(rpc: String): Nothing {
        logger.warn(
            LogCategory.AUTH,
            "Refused $rpc: no current verified process identity on this call",
            mapOf("rpc" to rpc),
        )
        throw StatusException(Status.PERMISSION_DENIED.withDescription(NO_IDENTITY))
    }

    private fun RunConfigurationData.toProto(): RunConfigurationProto =
        RunConfigurationProto
            .newBuilder()
            .setId(id)
            .setName(name)
            .setType(
                when (type) {
                    RunConfigurationTypeData.MAIN_FUNCTION -> RunConfigType.RUN_CONFIG_TYPE_MAIN_FUNCTION
                    RunConfigurationTypeData.SCRIPT -> RunConfigType.RUN_CONFIG_TYPE_SCRIPT
                    RunConfigurationTypeData.TEST -> RunConfigType.RUN_CONFIG_TYPE_TEST
                    RunConfigurationTypeData.CUSTOM -> RunConfigType.RUN_CONFIG_TYPE_CUSTOM
                },
            ).setFilePath(filePath)
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
                },
            ).setCommand(command)
            .setWorkingDirectory(workingDirectory)
            .putAllEnvironmentVariables(environmentVariables)
            .setArguments(arguments)
            .setIsAutoDetected(isAutoDetected)
            .setTimestamp(timestamp)
            .build()

    private companion object {
        val logger = BossLogger.forComponent("RunConfigServiceBridge")

        const val NO_IDENTITY = "This call presented no verified process identity"
        const val NOT_A_KNOWN_CONFIGURATION =
            "This id is not a run configuration this provider currently knows about"
    }
}
