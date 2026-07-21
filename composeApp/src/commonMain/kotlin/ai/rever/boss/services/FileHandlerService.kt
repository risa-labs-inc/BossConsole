package ai.rever.boss.services

/**
 * Service for managing file handler lifecycle.
 *
 * This is an expect/actual service to allow commonMain code (BossApp.kt)
 * to notify desktop-specific CLICommandHandler when file event handling is ready.
 */
expect object FileHandlerService {
    /**
     * Mark file handler as ready and process queued file events.
     * Should be called from BossApp.kt after FileEventBus listener is set up.
     */
    fun markReady()

    /**
     * Check if file events are currently being processed.
     * Used by BossApp to prevent showing New Tab Dialog during file tab creation.
     */
    fun isProcessingFiles(): Boolean
}
