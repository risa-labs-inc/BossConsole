package ai.rever.boss.services.passkey

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.services.supabase.AuthService

/**
 * Platform-specific initialization for passkey services on desktop
 * This initializes the DesktopPasskeyService and registers it with AuthService
 */
object PasskeyPlatformInit {
    private val logger = BossLogger.forComponent("PasskeyPlatformInit")

    private var isInitialized = false
    private var desktopPasskeyService: DesktopPasskeyService? = null
    
    /**
     * Initialize the desktop passkey service
     * Should be called early in the application lifecycle
     */
    fun initialize() {
        if (isInitialized) {
            logger.debug(LogCategory.PASSKEY, "Already initialized")
            return
        }

        try {
            logger.debug(LogCategory.PASSKEY, "Initializing desktop passkey service")
            
            // Create and initialize the desktop passkey service
            desktopPasskeyService = DesktopPasskeyService()
            
            // Register with AuthService
            AuthService.setPasskeyService(desktopPasskeyService!!)
            
            isInitialized = true
            logger.info(LogCategory.PASSKEY, "Desktop passkey service initialized successfully")

        } catch (e: Exception) {
            logger.error(LogCategory.PASSKEY, "Failed to initialize passkey service", error = e)
            
            // Continue without passkey support if initialization fails
            isInitialized = false
        }
    }

}
