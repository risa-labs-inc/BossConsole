package ai.rever.boss.ipc.services

import ai.rever.boss.ipc.proto.*
import com.google.protobuf.ByteString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * gRPC implementation of the StateService.
 *
 * Provides cross-process reactive state sharing, replacing in-process StateFlow singletons.
 * State is stored by hierarchical key (e.g., "auth.state", "workspace.current").
 *
 * Supports:
 * - Get: read current state value
 * - Watch: stream state changes (like StateFlow.collect across processes)
 * - Set: update state with optimistic concurrency control
 */
class StateServiceImpl : StateServiceGrpcKt.StateServiceCoroutineImplBase() {
    private val logger = LoggerFactory.getLogger(StateServiceImpl::class.java)

    private val stateStore = ConcurrentHashMap<String, StateEntry>()
    private val versionCounter = AtomicLong(0)

    // Shared flow for broadcasting state changes to watchers
    private val stateChanges = MutableSharedFlow<StateValue>(extraBufferCapacity = 128)
    private val stateMutex = Mutex()

    override suspend fun getState(request: StateKey): StateValue {
        val entry =
            stateStore[request.key]
                ?: return StateValue
                    .newBuilder()
                    .setKey(request.key)
                    .setVersion(0)
                    .build()

        return entry.toStateValue()
    }

    override fun watchState(request: StateKey): Flow<StateValue> =
        flow {
            // First emit current value
            stateStore[request.key]?.let { emit(it.toStateValue()) }

            // Then stream changes
            stateChanges
                .filter { it.key == request.key }
                .collect { emit(it) }
        }

    override suspend fun setState(request: StateUpdate): StateValue {
        val key = request.key

        val entry: StateEntry =
            stateMutex.withLock {
                // Optimistic concurrency check
                if (request.expectedVersion > 0) {
                    val current = stateStore[key]
                    if (current != null && current.version != request.expectedVersion) {
                        logger.warn(
                            "State update conflict for key={}: expected version {}, current {}",
                            key,
                            request.expectedVersion,
                            current.version,
                        )
                        // Return current value without updating (conflict)
                        return current.toStateValue()
                    }
                }

                val newVersion = versionCounter.incrementAndGet()
                StateEntry(
                    key = key,
                    value = request.value,
                    valueType = request.valueType,
                    version = newVersion,
                    timestamp = System.currentTimeMillis(),
                    ownerProcess = request.sourceProcess,
                ).also { stateStore[key] = it }
            }

        val stateValue = entry.toStateValue()
        stateChanges.emit(stateValue)

        logger.debug("State updated: key={}, version={}, owner={}", key, entry.version, request.sourceProcess)

        return stateValue
    }

    override suspend fun listStateKeys(request: Empty): StateKeyList {
        val keys =
            stateStore.map { (key, entry) ->
                StateKeyInfo
                    .newBuilder()
                    .setKey(key)
                    .setValueType(entry.valueType)
                    .setOwnerProcess(entry.ownerProcess)
                    .setVersion(entry.version)
                    .build()
            }

        return StateKeyList
            .newBuilder()
            .addAllKeys(keys)
            .build()
    }

    /**
     * Set state locally (from kernel code, not via gRPC).
     */
    suspend fun setLocal(
        key: String,
        value: ByteArray,
        valueType: String,
        ownerProcess: String = "kernel",
    ) {
        val request =
            StateUpdate
                .newBuilder()
                .setKey(key)
                .setValue(ByteString.copyFrom(value))
                .setValueType(valueType)
                .setSourceProcess(ownerProcess)
                .build()
        setState(request)
    }

    val stateCount: Int get() = stateStore.size
}

private data class StateEntry(
    val key: String,
    val value: ByteString,
    val valueType: String,
    val version: Long,
    val timestamp: Long,
    val ownerProcess: String,
) {
    fun toStateValue(): StateValue =
        StateValue
            .newBuilder()
            .setKey(key)
            .setValue(value)
            .setValueType(valueType)
            .setVersion(version)
            .setTimestamp(timestamp)
            .build()
}
