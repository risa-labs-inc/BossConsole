package ai.rever.boss.services.terminal

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private val logger = BossLogger.forComponent("VoiceCallSessionManager")

/**
 * Manages active Call Boss sessions across windows and terminal panes.
 *
 * Responsibilities:
 * - Coordinates state transitions (DISCONNECTED -> CONNECTING -> LISTENING/SPEAKING/PROCESSING -> DISCONNECTED).
 * - Routes audio, VAD events, barge-in interruptions, and tool calls.
 * - Tracks telemetry and metrics (latency, audio energy levels, packet counts).
 * - Exposes reactive StateFlows and SharedFlows for Compose UI components.
 */
@Suppress("TooManyFunctions")
class VoiceCallSessionManager(
    val windowId: String,
    val terminalId: String,
    private val config: VoiceCallConfig = VoiceCallConfig(),
) {
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(VoiceCallState.DISCONNECTED)
    val state: StateFlow<VoiceCallState> = _state.asStateFlow()

    private val _metrics = MutableStateFlow(VoiceCallMetrics())
    val metrics: StateFlow<VoiceCallMetrics> = _metrics.asStateFlow()

    private val _events = MutableSharedFlow<VoiceCallEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<VoiceCallEvent> = _events.asSharedFlow()

    private var connectJob: Job? = null
    private var isMuted = false

    // Callbacks provided by the platform audio engine or WebRTC bridge
    var onSendAudioChunk: ((ByteArray) -> Unit)? = null
    var onInterruptSignal: (() -> Unit)? = null
    var onFlushAudioOutput: (() -> Unit)? = null
    var onDisconnectRequested: (() -> Unit)? = null

    /**
     * Start a new voice session.
     */
    fun startCall() {
        if (_state.value != VoiceCallState.DISCONNECTED && _state.value != VoiceCallState.ERROR) {
            logger.warn(LogCategory.SYSTEM, "Call already in progress in state: ${_state.value}")
            return
        }

        transitionState(VoiceCallState.CONNECTING)
        logger.info(LogCategory.SYSTEM, "Initiating Call Boss session for window=$windowId, terminal=$terminalId")
        // Ready for speech input
        transitionState(VoiceCallState.LISTENING)
        _metrics.value = _metrics.value.copy(turnCount = 0, packetsSent = 0L, packetsReceived = 0L)
    }

    /**
     * Handle incoming user voice activity detection (VAD).
     * When user speaks while agent is speaking, trigger immediate barge-in.
     */
    fun onUserVoiceActivity(isSpeaking: Boolean) {
        if (isMuted) return

        if (isSpeaking) {
            if (_state.value == VoiceCallState.SPEAKING) {
                // Barge-in detected: immediately halt agent speech and flush output
                handleBargeIn()
            } else if (_state.value == VoiceCallState.CONNECTED) {
                transitionState(VoiceCallState.LISTENING)
            }
        } else {
            if (_state.value == VoiceCallState.LISTENING) {
                // User finished utterance; will transition to PROCESSING when server acknowledges
            }
        }
    }

    /**
     * Process an interruption (barge-in): immediately flush playback buffers and inform the server.
     */
    fun handleBargeIn() {
        logger.info(LogCategory.SYSTEM, "Barge-in triggered by user interruption")
        onFlushAudioOutput?.invoke()
        onInterruptSignal?.invoke()
        transitionState(VoiceCallState.LISTENING)
        sessionScope.launch {
            _events.emit(VoiceCallEvent.UserInterrupted())
        }
    }

    /**
     * Handle incoming audio synthesized by the model.
     */
    fun onModelAudioReceived(audioChunk: ByteArray) {
        if (audioChunk.isEmpty()) return
        if (_state.value != VoiceCallState.SPEAKING && _state.value != VoiceCallState.DISCONNECTED) {
            transitionState(VoiceCallState.SPEAKING)
        }
        _metrics.value =
            _metrics.value.copy(
                packetsReceived = _metrics.value.packetsReceived + 1,
            )
    }

    /**
     * Handle model initiating a tool call (e.g. terminal execution).
     */
    fun onToolExecutionStarted(
        toolName: String,
        arguments: String,
    ) {
        transitionState(VoiceCallState.PROCESSING)
        _metrics.value = _metrics.value.copy(activeToolName = toolName)
        sessionScope.launch {
            _events.emit(VoiceCallEvent.ToolExecutionStarted(toolName, arguments))
        }
    }

    /**
     * Handle model completing a tool call.
     */
    fun onToolExecutionCompleted(
        toolName: String,
        resultSummary: String,
    ) {
        _metrics.value = _metrics.value.copy(activeToolName = null)
        sessionScope.launch {
            _events.emit(VoiceCallEvent.ToolExecutionCompleted(toolName, resultSummary))
        }
    }

    /**
     * Toggle microphone mute.
     */
    fun toggleMute(): Boolean {
        isMuted = !isMuted
        _metrics.value = _metrics.value.copy(isMuted = isMuted)
        return isMuted
    }

    /**
     * Update real-time audio energy levels for UI waveforms.
     */
    fun updateLevels(
        inputEnergy: Float,
        outputEnergy: Float,
    ) {
        _metrics.value =
            _metrics.value.copy(
                inputEnergyLevel = if (isMuted) 0f else inputEnergy,
                outputEnergyLevel = outputEnergy,
            )
    }

    /**
     * Update network round-trip latency.
     */
    fun updateLatency(latencyMs: Long) {
        _metrics.value = _metrics.value.copy(latencyMs = latencyMs)
    }

    /**
     * End the current call cleanly.
     */
    fun endCall() {
        if (_state.value == VoiceCallState.DISCONNECTED) return
        transitionState(VoiceCallState.DISCONNECTING)
        connectJob?.cancel()
        onFlushAudioOutput?.invoke()
        onDisconnectRequested?.invoke()
        transitionState(VoiceCallState.DISCONNECTED)
        logger.info(LogCategory.SYSTEM, "Call Boss session ended for window=$windowId, terminal=$terminalId")
    }

    /**
     * Destroy the manager and release coroutine resources.
     */
    fun dispose() {
        endCall()
        sessionScope.cancel()
    }

    fun transitionState(newState: VoiceCallState) {
        val oldState = _state.value
        if (oldState == newState) return
        _state.value = newState
        sessionScope.launch {
            _events.emit(VoiceCallEvent.StateChanged(oldState, newState))
        }
        logger.debug(LogCategory.SYSTEM, "Call Boss state: $oldState -> $newState")
    }

    companion object {
        private val sessions = ConcurrentHashMap<String, VoiceCallSessionManager>()

        private fun key(
            windowId: String,
            terminalId: String,
        ): String = "$windowId::$terminalId"

        fun getOrCreate(
            windowId: String,
            terminalId: String,
            config: VoiceCallConfig = VoiceCallConfig(),
        ): VoiceCallSessionManager =
            sessions.computeIfAbsent(key(windowId, terminalId)) {
                VoiceCallSessionManager(windowId, terminalId, config)
            }

        fun get(
            windowId: String,
            terminalId: String,
        ): VoiceCallSessionManager? = sessions[key(windowId, terminalId)]

        fun remove(
            windowId: String,
            terminalId: String,
        ): VoiceCallSessionManager? =
            sessions.remove(key(windowId, terminalId))?.also {
                it.dispose()
            }

        fun removeAllForWindow(windowId: String): Int {
            val prefix = "$windowId::"
            val toRemove = sessions.keys.filter { it.startsWith(prefix) }
            toRemove.forEach { sessions.remove(it)?.dispose() }
            return toRemove.size
        }
    }
}
