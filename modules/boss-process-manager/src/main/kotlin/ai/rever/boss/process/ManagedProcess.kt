package ai.rever.boss.process

import ai.rever.boss.ipc.BossIpcClient
import ai.rever.boss.ipc.proto.ProcessManifest
import ai.rever.boss.ipc.proto.ProcessState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Represents a running child process managed by the kernel.
 */
class ManagedProcess(
    val config: ProcessConfig,
    val process: Process,
    val ipcAddress: String,
) {
    private val _state = MutableStateFlow(ProcessState.PROCESS_STATE_STARTING)
    val state: StateFlow<ProcessState> = _state.asStateFlow()

    var manifest: ProcessManifest? = null
        internal set

    var ipcClient: BossIpcClient? = null
        internal set

    var startTime: Long = System.currentTimeMillis()
        internal set

    var restartCount: Int = 0
        internal set

    var lastError: String? = null
        internal set

    var lastErrorTimestamp: Long = 0
        internal set

    val isAlive: Boolean
        get() = process.isAlive

    val pid: Long
        get() = process.pid()

    fun updateState(newState: ProcessState) {
        _state.value = newState
    }

    // Set once this instance's death has been reported; see [claimFailureReport].
    private val failureReported = AtomicBoolean(false)

    /**
     * Claim the one report of this instance's death: true for the first caller, false for every
     * later one. A dead handle stays registered until its replacement is spawned, so the global
     * monitor can re-attach to it and see the same death again (#1612); only the claimant emits,
     * so one death is one ProcessFailure however many monitors observe it.
     *
     * The claim is taken before the emit, so a monitor cancelled between the two leaves the death
     * claimed but never reported, and the global monitor's CRASHED skip then keeps it that way.
     * Cancellation only happens on the supervision-stop path, where no respawn is wanted anyway.
     */
    fun claimFailureReport(): Boolean = failureReported.compareAndSet(false, true)

    fun recordError(error: String) {
        lastError = error
        lastErrorTimestamp = System.currentTimeMillis()
    }

    /**
     * Forcefully destroy the process.
     */
    fun destroy() {
        ipcClient?.shutdown()
        if (process.isAlive) {
            process.destroy()
        }
    }

    /**
     * Forcefully destroy the process immediately.
     */
    fun destroyForcibly() {
        ipcClient?.shutdown(1000)
        if (process.isAlive) {
            process.destroyForcibly()
        }
    }
}
