package ai.rever.boss.services

/**
 * Service for managing terminal handler lifecycle.
 *
 * This is an expect/actual service to allow commonMain code (BossApp.kt)
 * to notify desktop-specific CLICommandHandler when terminal event handling is ready.
 */
expect object TerminalHandlerService {
    /**
     * Mark terminal handler as ready and process queued terminal events.
     * Should be called from BossApp.kt after TerminalEventBus listener is set up.
     */
    fun markReady()

    /**
     * Check if terminal events are currently being processed.
     * Used by BossApp to prevent showing New Tab Dialog during terminal creation.
     */
    fun isProcessingTerminals(): Boolean
}
