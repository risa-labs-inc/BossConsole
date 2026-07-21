package ai.rever.boss.components.plugin.remote

import ai.rever.boss.components.plugin.PluginStateBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Generic bridge that deserializes plugin state from [PluginStateBridge] bytes
 * and exposes it as a typed [StateFlow] for in-kernel Compose UI rendering.
 *
 * In the split-brain model, the child JVM owns the state (via PluginStateHolder),
 * and this bridge provides a read-only view for the kernel's UI layer.
 * User intents are forwarded back to the child via the bridge.
 *
 * @param S The serializable state type (must match the child's PluginStateHolder state)
 * @param bridge The PluginStateBridge connected to the child process
 * @param initialState Default state before the first update arrives
 * @param deserialize Function to convert ByteArray → S
 * @param scope Coroutine scope for collection
 */
class BridgedPluginViewModel<S>(
    private val bridge: PluginStateBridge,
    initialState: S,
    deserialize: (ByteArray) -> S,
    scope: CoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(BridgedPluginViewModel::class.java)

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<S> = _state.asStateFlow()

    val connected: StateFlow<Boolean> = bridge.connected

    init {
        scope.launch {
            bridge.state.collect { bytes ->
                if (bytes.isNotEmpty()) {
                    try {
                        _state.value = deserialize(bytes)
                    } catch (e: Exception) {
                        logger.warn("Failed to deserialize plugin state: {}", e.message)
                    }
                }
            }
        }
    }

    /**
     * Send an intent to the child process via the state bridge.
     */
    suspend fun sendIntent(intentType: String, payload: ByteArray = ByteArray(0)) {
        bridge.sendIntent(intentType, payload)
    }
}
