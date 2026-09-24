package ai.rever.boss.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live, process-wide state for the operator's session action guard.
 *
 * The guard is deliberately session-scoped. It is an emergency brake, not another durable policy
 * layer: restarting BOSS returns to the policies and per-tool kill switches already persisted by
 * their own owners. Read-only calls remain available while the guard is paused so an agent can
 * still inspect the workspace and explain what it was doing.
 */
data class McpSessionGuardState(
    val mutatingActionsPaused: Boolean = false,
    val inFlightMutatingActions: Int = 0,
    val blockedMutatingActions: Long = 0L,
)

/**
 * Admission gate at the final seam before an MCP handler starts.
 *
 * A mutating call admitted before [setPaused] returns owns a permit and is reported as in-flight;
 * it is never misrepresented as cancelled. Any later admission is rejected. The permit boundary
 * gives pause a precise concurrency meaning without holding a monitor while untrusted plugin code
 * suspends. Read-only calls receive a no-op permit and never affect the counters.
 */
internal class McpSessionGuard {
    private val lock = Any()
    private val _state = MutableStateFlow(McpSessionGuardState())
    val state: StateFlow<McpSessionGuardState> = _state.asStateFlow()

    fun setPaused(paused: Boolean): Boolean =
        synchronized(lock) {
            val current = _state.value
            if (current.mutatingActionsPaused == paused) return@synchronized false
            _state.value = current.copy(mutatingActionsPaused = paused)
            true
        }

    /**
     * Fast refusal before policy authorization, so an already-paused session does not open an
     * approval dialog for a call the final admission seam must reject anyway.
     */
    fun blockIfPaused(
        toolName: String,
        declaredReadOnly: Boolean?,
    ): Boolean {
        if (!McpMutatingToolCatalog.isMutationClassified(toolName, declaredReadOnly)) return false
        return synchronized(lock) {
            if (!_state.value.mutatingActionsPaused) return@synchronized false
            recordBlocked()
            true
        }
    }

    /**
     * Claim the final execution seam. A null result means pause won the race with authorization.
     */
    fun tryAcquire(
        toolName: String,
        declaredReadOnly: Boolean?,
    ): McpSessionGuardPermit? {
        if (!McpMutatingToolCatalog.isMutationClassified(toolName, declaredReadOnly)) {
            return McpSessionGuardPermit()
        }
        return synchronized(lock) {
            if (_state.value.mutatingActionsPaused) {
                recordBlocked()
                null
            } else {
                _state.value =
                    _state.value.copy(
                        inFlightMutatingActions = _state.value.inFlightMutatingActions + 1,
                    )
                McpSessionGuardPermit(::releaseMutating)
            }
        }
    }

    private fun recordBlocked() {
        val current = _state.value
        _state.value =
            current.copy(
                blockedMutatingActions =
                    if (current.blockedMutatingActions == Long.MAX_VALUE) {
                        Long.MAX_VALUE
                    } else {
                        current.blockedMutatingActions + 1L
                    },
            )
    }

    private fun releaseMutating() {
        synchronized(lock) {
            val current = _state.value
            _state.value =
                current.copy(
                    inFlightMutatingActions = (current.inFlightMutatingActions - 1).coerceAtLeast(0),
                )
        }
    }
}

/** Idempotent so cancellation and exceptional cleanup cannot decrement the in-flight count twice. */
internal class McpSessionGuardPermit(
    private val release: (() -> Unit)? = null,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release?.invoke()
    }
}
