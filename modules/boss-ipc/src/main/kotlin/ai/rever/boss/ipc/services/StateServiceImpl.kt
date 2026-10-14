package ai.rever.boss.ipc.services

import ai.rever.boss.ipc.IpcLogText
import ai.rever.boss.ipc.auth.IpcCall
import ai.rever.boss.ipc.auth.ProcessAuthority
import ai.rever.boss.ipc.auth.ProcessIdentity
import ai.rever.boss.ipc.proto.*
import com.google.protobuf.ByteString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onSubscription
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
    private val stateChanges = MutableSharedFlow<StateEntry>(extraBufferCapacity = 128)
    private val stateMutex = Mutex()

    override suspend fun getState(request: StateKey): StateValue {
        val caller = IpcCall.current()
        val entry =
            stateStore[request.key]
                ?: return StateValue
                    .newBuilder()
                    .setKey(request.key)
                    .setVersion(0)
                    .build()

        authorizeRead(entry, caller)
        return entry.toStateValue()
    }

    /**
     * Streams the current value of [request.key] (when present), then every subsequent
     * value. The change flow is collected before the snapshot is read, so the subscription
     * slot buffers any update emitted while the snapshot is read or delivered and no
     * change can slip between the snapshot and the subscription (replay=0 drops an emit
     * with no subscriber for good). Consecutive equal versions mean the snapshot and a
     * buffered change are the same update, so they are collapsed and every update is
     * delivered exactly once.
     */
    override fun watchState(request: StateKey): Flow<StateValue> =
        stateChanges
            .onSubscription {
                // Subscribe before snapshotting: the slot this collector just registered
                // in the shared flow buffers updates emitted while the snapshot below is
                // read or delivered, so no change can slip between the snapshot and the
                // subscription (replay=0 drops an emit with no subscriber for good).
                val caller = IpcCall.current()
                stateStore[request.key]?.let {
                    authorizeRead(it, caller)
                    emit(it)
                }
            }.filter { it.key == request.key }
            .onEach { authorizeRead(it, IpcCall.current()) }
            .distinctUntilChanged { previous, next -> previous.version == next.version }
            .map { it.toStateValue() }

    override suspend fun setState(request: StateUpdate): StateValue {
        val caller = IpcCall.current()
        return update(request, caller.processId, caller.instanceId) {
            val currentCaller = IpcCall.current()
            stateStore[request.key]?.let { existing ->
                // The registry admits only one current incarnation per process ID. A replacement
                // may overwrite its old key, but cannot read the previous private value or
                // use a stale optimistic version to obtain it through the conflict response.
                val owner =
                    existing.ownerInstance == currentCaller.instanceId ||
                        (existing.ownerProcess == currentCaller.processId && request.expectedVersion == 0L)
                IpcCall.requirePermission(
                    owner || currentCaller.authority == ProcessAuthority.HOST,
                )
            }
        }
    }

    private suspend fun update(
        request: StateUpdate,
        ownerProcess: String,
        ownerInstance: String?,
        authorize: () -> Unit,
    ): StateValue {
        val key = request.key

        val entry: StateEntry =
            stateMutex.withLock {
                authorize()
                // Optimistic concurrency check
                if (request.expectedVersion > 0) {
                    val current = stateStore[key]
                    if (current != null && current.version != request.expectedVersion) {
                        // The key is free text from the writer: neutralized so a hostile key cannot
                        // forge kernel log records. The stored entry is untouched.
                        logger.warn(
                            "State update conflict for key={}: expected version {}, current {}",
                            IpcLogText.neutralize(key),
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
                    ownerProcess = ownerProcess,
                    ownerInstance = ownerInstance,
                ).also { stateStore[key] = it }
            }

        val stateValue = entry.toStateValue()
        stateChanges.emit(entry)

        // Same as the conflict warning above: the key is caller-supplied text.
        logger.debug(
            "State updated: key={}, version={}, owner={}",
            IpcLogText.neutralize(key),
            entry.version,
            ownerProcess,
        )

        return stateValue
    }

    override suspend fun listStateKeys(request: Empty): StateKeyList {
        val caller = IpcCall.current()
        val keys =
            stateStore.filterValues { canRead(it, caller) }.map { (key, entry) ->
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
        update(request, ownerProcess, null) { }
    }

    private fun canRead(
        entry: StateEntry,
        caller: ProcessIdentity,
    ): Boolean {
        val sharedOrOwned = entry.ownerInstance == null || entry.ownerInstance == caller.instanceId
        return sharedOrOwned || caller.authority == ProcessAuthority.HOST
    }

    private fun authorizeRead(
        entry: StateEntry,
        caller: ProcessIdentity,
    ) {
        IpcCall.requirePermission(canRead(entry, caller))
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
    val ownerInstance: String?,
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
