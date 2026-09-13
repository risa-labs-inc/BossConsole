package ai.rever.boss.services.terminal

/**
 * Current operational state of an in-app "Call Boss" voice session.
 */
enum class VoiceCallState {
    /** No call active. */
    DISCONNECTED,

    /** Establishing connection, ICE candidate exchange, or WebSocket handshake. */
    CONNECTING,

    /** Connected and idle. */
    CONNECTED,

    /** Actively capturing user speech; model is listening. */
    LISTENING,

    /** Model is actively speaking audio response back to user. */
    SPEAKING,

    /** Model is processing or executing a tool (terminal command, MCP tool, etc.). */
    PROCESSING,

    /** Teardown and cleanup in progress. */
    DISCONNECTING,

    /** An error occurred during the call. */
    ERROR,
}

/**
 * Real-time telemetry metrics for an active Call Boss session.
 */
data class VoiceCallMetrics(
    val latencyMs: Long = 0L,
    val inputEnergyLevel: Float = 0f,
    val outputEnergyLevel: Float = 0f,
    val turnCount: Int = 0,
    val packetsSent: Long = 0L,
    val packetsReceived: Long = 0L,
    val isMuted: Boolean = false,
    val activeToolName: String? = null,
)

/**
 * Configuration options for Call Boss audio and session behavior.
 */
data class VoiceCallConfig(
    val sampleRate: Int = 24000,
    val chunkDurationMs: Int = 40,
    val energyThresholdVAD: Float = 0.015f,
    val autoInterruptOnSpeech: Boolean = true,
    val voiceName: String = "Aoede",
    val model: String = "gemini-2.0-flash-exp",
)

/**
 * Call lifecycle events emitted by the voice engine.
 */
sealed class VoiceCallEvent {
    data class StateChanged(
        val oldState: VoiceCallState,
        val newState: VoiceCallState,
    ) : VoiceCallEvent()

    data class ErrorOccurred(
        val message: String,
        val cause: Throwable? = null,
        val isRecoverable: Boolean = true,
    ) : VoiceCallEvent()

    data class UserInterrupted(
        val timestamp: Long = System.currentTimeMillis(),
    ) : VoiceCallEvent()

    data class ToolExecutionStarted(
        val toolName: String,
        val arguments: String,
    ) : VoiceCallEvent()

    data class ToolExecutionCompleted(
        val toolName: String,
        val resultSummary: String,
    ) : VoiceCallEvent()

    data class MetricsUpdated(
        val metrics: VoiceCallMetrics,
    ) : VoiceCallEvent()
}
