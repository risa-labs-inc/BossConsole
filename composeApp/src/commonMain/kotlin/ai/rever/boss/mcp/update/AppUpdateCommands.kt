package ai.rever.boss.mcp.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal interface AppUpdateBackend {
    fun snapshot(): AppUpdateSnapshot

    suspend fun perform(
        action: String,
        version: String?,
    )
}

internal data class AppUpdateSnapshot(
    val app: String,
    val currentVersion: String,
    val state: String,
    val latestVersion: String? = null,
    val progress: Float? = null,
    val error: String? = null,
    val installVersion: String? = null,
)

internal data class AppUpdateReply(
    val payload: JsonObject,
    val isError: Boolean = false,
)

/** Process-owned jobs survive MCP request timeouts. Poll status for completion. */
internal class AppUpdateCommands(
    private val backend: AppUpdateBackend,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val gate = Mutex()
    private val operation = MutableStateFlow<String?>(null)
    private val operationError = MutableStateFlow<String?>(null)

    fun status(): AppUpdateReply = reply()

    fun start(
        action: String,
        version: String? = null,
    ): AppUpdateReply {
        if (!gate.tryLock()) return reply("An update operation is already running")
        val refusal = refusal(backend.snapshot(), action, version)
        if (refusal == null) {
            launchOperation(action, version)
        } else {
            gate.unlock()
        }
        return reply(error = refusal, accepted = refusal == null)
    }

    @Suppress("TooGenericExceptionCaught") // Surface platform/network installer errors through polling.
    private fun launchOperation(action: String, version: String?) {
        operationError.value = null
        operation.value = action
        scope
            .launch {
                try {
                    backend.perform(action, version)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    operationError.value = e.message ?: "Update operation failed"
                }
            }.invokeOnCompletion {
                operation.value = null
                gate.unlock()
            }
    }

    private fun refusal(
        state: AppUpdateSnapshot,
        action: String,
        version: String?,
    ): String? =
        when (action) {
            "check" -> {
                if (state.state in BUSY_STATES) {
                    "Finish the current update before checking again"
                } else {
                    null
                }
            }

            "download" -> {
                if (state.state != "available") "Check for an available update first" else null
            }

            "install" -> {
                when {
                    state.state != "ready_to_install" -> {
                        "Download an update before installing"
                    }

                    version.isNullOrBlank() || version != state.installVersion -> {
                        "version must match the staged install_version"
                    }

                    else -> {
                        null
                    }
                }
            }

            else -> {
                "Unknown update action"
            }
        }

    private fun reply(
        error: String? = null,
        accepted: Boolean = false,
    ): AppUpdateReply {
        val state = backend.snapshot()
        return AppUpdateReply(
            buildJsonObject {
                put("app", state.app)
                put("current_version", state.currentVersion)
                put("state", state.state)
                put("accepted", accepted)
                state.latestVersion?.let { put("latest_version", it) }
                state.installVersion?.let { put("install_version", it) }
                state.progress?.let { put("progress", it) }
                operation.value?.let { put("operation", it) }
                put("restart_required", state.state == "restart_required")
                (error ?: operationError.value ?: state.error)?.let { put("error", it) }
            },
            isError = error != null,
        )
    }

    private companion object {
        val BUSY_STATES = setOf("checking", "downloading", "ready_to_install", "installing", "restart_required")
    }
}
