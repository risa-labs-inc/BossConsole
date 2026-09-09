package ai.rever.boss.components.plugin

import ai.rever.boss.ipc.proto.PluginIntentEnvelope
import ai.rever.boss.ipc.proto.PluginStateDelta
import ai.rever.boss.ipc.proto.PluginStateRequest
import ai.rever.boss.ipc.proto.PluginStateServiceGrpcKt
import ai.rever.boss.ipc.proto.PluginStateUpdate
import io.grpc.ManagedChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Bridges state between an out-of-process plugin and the kernel's UI renderer.
 *
 * Connects to the child process's [PluginStateService] gRPC endpoint, subscribes
 * to the state sync stream, and exposes the plugin's state as a [StateFlow] for
 * the host Compose UI to collect.
 *
 * User intents from the host UI are forwarded to the child process via the
 * bidirectional stream.
 *
 * Handles reconnection with exponential backoff when the child process
 * restarts or the connection drops.
 */
class PluginStateBridge(
    private val pluginId: String,
    private val instanceId: String,
    private val channel: ManagedChannel,
) {
    private val logger = LoggerFactory.getLogger(PluginStateBridge::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val stub = PluginStateServiceGrpcKt.PluginStateServiceCoroutineStub(channel)

    /** Current serialized state from the plugin process. */
    private val _state = MutableStateFlow<ByteArray>(ByteArray(0))
    val state: StateFlow<ByteArray> = _state.asStateFlow()

    /** Current state version for ordering guarantees. */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    /** Whether the bridge is connected to the child process. */
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** Intent flow: kernel UI -> child process. */
    private val intentFlow = MutableSharedFlow<PluginIntentEnvelope>(extraBufferCapacity = 32)

    /** Side effects emitted by the plugin for the kernel to handle. */
    private val _effects = MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 16)
    val effects: kotlinx.coroutines.flow.SharedFlow<Pair<String, ByteArray>> = _effects

    /**
     * Start the state sync connection. Call once after construction.
     * The bridge will automatically reconnect on failure.
     */
    fun start() {
        scope.launch { syncLoop() }
    }

    /**
     * Send an intent from the kernel UI to the plugin process.
     *
     * @param intentType Intent discriminator (e.g., "SetFilter", "Refresh")
     * @param payload Serialized intent payload bytes
     */
    suspend fun sendIntent(
        intentType: String,
        payload: ByteArray = ByteArray(0),
    ) {
        val envelope =
            PluginIntentEnvelope
                .newBuilder()
                .setPluginId(pluginId)
                .setInstanceId(instanceId)
                .setIntentType(intentType)
                .setPayloadBytes(
                    com.google.protobuf.ByteString
                        .copyFrom(payload),
                ).setTimestamp(System.currentTimeMillis())
                .build()
        intentFlow.emit(envelope)
    }

    /**
     * Fetch the current state snapshot from the child process.
     * Used on initial connection or after reconnection.
     */
    private suspend fun fetchCurrentState() {
        try {
            val request =
                PluginStateRequest
                    .newBuilder()
                    .setPluginId(pluginId)
                    .setInstanceId(instanceId)
                    .build()
            val snapshot = stub.getCurrentState(request)
            applyState(snapshot.stateBytes.toByteArray(), snapshot.version)
            logger.info(
                "Fetched current state: plugin={}, version={}",
                pluginId,
                snapshot.version,
            )
        } catch (e: Exception) {
            logger.warn("Failed to fetch current state for plugin={}: {}", pluginId, e.message)
        }
    }

    /**
     * Main sync loop with exponential backoff reconnection.
     */
    private suspend fun syncLoop() {
        var backoffMs = 200L

        while (scope.isActive) {
            try {
                // Fetch initial state snapshot
                fetchCurrentState()
                _connected.value = true

                // Start bidirectional stream
                val intentSource =
                    kotlinx.coroutines.flow.channelFlow {
                        intentFlow.collect { intent ->
                            send(intent)
                        }
                    }

                stub.syncState(intentSource).collect { update ->
                    handleStateUpdate(update)
                }

                // Stream ended cleanly — reconnect immediately
                _connected.value = false
                backoffMs = 200L
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _connected.value = false
                logger.warn(
                    "State sync disconnected for plugin={}: {}. Reconnecting in {}ms",
                    pluginId,
                    e.message,
                    backoffMs,
                )
                // Jittered exponential backoff to prevent thundering herd
                val jitter = (Math.random() * backoffMs * 0.3).toLong()
                delay(backoffMs + jitter)
                backoffMs = (backoffMs * 2).coerceAtMost(15_000L)
            }
        }
    }

    private suspend fun handleStateUpdate(update: PluginStateUpdate) {
        when {
            update.hasFullState() -> {
                val fullState = update.fullState
                applyState(fullState.stateBytes.toByteArray(), fullState.version)
            }

            update.hasDeltaState() -> {
                try {
                    val delta = update.deltaState
                    val mergedBytes = mergePluginStateDelta(_state.value, _version.value, delta)
                    if (mergedBytes != null) {
                        applyState(mergedBytes, delta.newVersion)
                    } else {
                        logger.debug("Ignoring stale delta: plugin={}, version={}", pluginId, delta.newVersion)
                    }
                } catch (e: StatePatchException) {
                    resyncAfterDeltaFailure(e.message ?: "Invalid state patch")
                } catch (e: Exception) {
                    resyncAfterDeltaFailure(e.javaClass.simpleName)
                }
            }

            update.hasEffect() -> {
                val effect = update.effect
                _effects.emit(effect.effectType to effect.payloadBytes.toByteArray())
            }
        }
    }

    private suspend fun resyncAfterDeltaFailure(reason: String) {
        logger.warn("Failed to apply delta state for plugin={}, requesting full state instead: {}", pluginId, reason)
        fetchCurrentState()
    }

    internal fun applyState(
        stateBytes: ByteArray,
        version: Long,
    ) {
        if (version > _version.value || (_state.value.isEmpty() && version == _version.value)) {
            _state.value = stateBytes
            _version.value = version
        }
    }

    /**
     * Dispose this bridge and release resources.
     */
    fun dispose() {
        _connected.value = false
        scope.cancel()
        logger.info("PluginStateBridge disposed for plugin={}", pluginId)
    }
}

/**
 * Applies the plugin_state.proto JSON Merge Patch contract. Producers must send the exact
 * base_version of the snapshot they patched and a monotonically increasing new_version.
 * Returns null for a stale delta; an invalid/missing base requires a full snapshot.
 */
internal fun mergePluginStateDelta(
    currentState: ByteArray,
    currentVersion: Long,
    delta: PluginStateDelta,
): ByteArray? {
    if (delta.newVersion <= currentVersion) return null
    if (currentState.isEmpty()) throw StatePatchException("No state snapshot available")
    if (delta.baseVersion != currentVersion) throw StatePatchException("Delta base does not match current state")
    val target = Json.parseToJsonElement(currentState.decodeToString(throwOnInvalidSequence = true))
    val patch = Json.parseToJsonElement(delta.patchBytes.toByteArray().decodeToString(throwOnInvalidSequence = true))
    validatePatchJson(target)
    validatePatchJson(patch)
    return target.mergePatch(patch).toString().encodeToByteArray()
}

private fun JsonElement.mergePatch(patch: JsonElement): JsonElement {
    if (patch !is JsonObject) {
        return patch
    }

    val targetObj = if (this is JsonObject) this.toMutableMap() else mutableMapOf()

    for ((key, value) in patch) {
        if (value is JsonNull) {
            targetObj.remove(key)
        } else {
            val targetValue = targetObj[key] ?: JsonNull
            targetObj[key] = targetValue.mergePatch(value)
        }
    }

    return JsonObject(targetObj)
}

private const val MAX_PATCH_DEPTH = 128
private val jsonNumber = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

private class StatePatchException(
    message: String,
) : IllegalArgumentException(message)

/** The JSON tree parser accepts unquoted non-JSON literals; validate before serializing state. */
private fun validatePatchJson(
    element: JsonElement,
    depth: Int = 0,
) {
    if (depth > MAX_PATCH_DEPTH) throw StatePatchException("State or patch nesting exceeds limit")
    when (element) {
        is JsonObject -> {
            element.values.forEach { validatePatchJson(it, depth + 1) }
        }

        is JsonArray -> {
            element.forEach { validatePatchJson(it, depth + 1) }
        }

        is JsonPrimitive -> {
            if (!element.isString && element != JsonNull) {
                val literal = element.content
                val valid = literal == "true" || literal == "false" || jsonNumber.matches(literal)
                if (!valid) throw StatePatchException("Invalid JSON literal in state or patch")
            }
        }
    }
}
