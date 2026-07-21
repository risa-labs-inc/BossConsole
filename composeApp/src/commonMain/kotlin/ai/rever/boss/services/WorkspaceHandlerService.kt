package ai.rever.boss.services

/**
 * Service for managing workspace handler lifecycle from CLI.
 *
 * This is an expect/actual service to allow commonMain code (BossApp.kt)
 * to notify desktop-specific CLICommandHandler when workspace loading is ready.
 */
expect object WorkspaceHandlerService {
    /**
     * Mark workspace handler as ready and process queued workspace load events.
     * Should be called from BossApp.kt after Last Session workspace is loaded.
     */
    fun markReady()
}
