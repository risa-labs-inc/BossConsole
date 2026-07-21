package ai.rever.boss.process

import ai.rever.boss.ipc.BossIpcClient
import ai.rever.boss.ipc.proto.ProcessManifest
import ai.rever.boss.ipc.proto.ProcessState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
