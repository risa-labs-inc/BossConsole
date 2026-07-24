package ai.rever.boss.services.passkey.desktop

import ai.rever.boss.plugin.browser.FluckEngine
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.engine.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages cross-device authentication flows and browser integration
 * Handles JxBrowser WebAuthn operations and fallback browser launching
 */
class CrossDeviceBrowserManager {
    private val logger = BossLogger.forComponent("CrossDeviceBrowserManager")

    private var webAuthnEngine: Engine? = null
    private var webAuthnBrowser: Browser? = null

    init {
        initializeWebAuthnEngine()
    }

    /**
     * Initialize WebAuthn engine using FluckEngine
     */
    private fun initializeWebAuthnEngine() {
        try {
            logger.debug(LogCategory.BROWSER, "Initializing WebAuthn using shared FluckEngine")

            // Use the existing FluckEngine singleton which has proper licensing and configuration
            webAuthnEngine = FluckEngine.engine
            webAuthnBrowser = webAuthnEngine?.newBrowser()

            logger.info(LogCategory.BROWSER, "WebAuthn engine initialized successfully using FluckEngine")
        } catch (e: Exception) {
            when {
                e.javaClass.name.contains("NoLicenseException") -> {
                    logger.warn(LogCategory.BROWSER, "JxBrowser license not available - falling back to system browser")
                }

                else -> {
                    logger.warn(LogCategory.BROWSER, "Failed to initialize WebAuthn - using system browser fallback", error = e)
                }
            }
        }
    }

    /**
     * Open URL in system browser with fallback methods
     */
    suspend fun openInSystemBrowser(url: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                logger.debug(LogCategory.BROWSER, "Opening URL in system browser", mapOf("url" to url))

                // Try Desktop API first (works well on most platforms)
                if (java.awt.Desktop.isDesktopSupported()) {
                    val desktop = java.awt.Desktop.getDesktop()
                    if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                        desktop.browse(java.net.URI.create(url))
                        logger.debug(LogCategory.BROWSER, "Successfully opened browser using Desktop API")
                        Result.success(Unit)
                    } else {
                        // Fallback to ProcessBuilder
                        logger.debug(LogCategory.BROWSER, "Desktop.browse not supported, using ProcessBuilder")
                        openBrowserWithProcessBuilder(url)
                    }
                } else {
                    // Fallback to ProcessBuilder
                    logger.debug(LogCategory.BROWSER, "Desktop not supported, using ProcessBuilder")
                    openBrowserWithProcessBuilder(url)
                }
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "Failed to open browser with Desktop API, trying fallback", error = e)
                try {
                    openBrowserWithProcessBuilder(url)
                } catch (fallbackException: Exception) {
                    logger.error(LogCategory.BROWSER, "All browser opening methods failed", error = fallbackException)
                    Result.failure(fallbackException)
                }
            }
        }

    /**
     * Prepare URL for embedded Fluck browser display
     * Returns the URL to be displayed in an embedded JxBrowser instance
     */
    suspend fun openInFluckBrowser(
        url: String,
        sessionId: String,
    ): Result<String> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                logger.debug(LogCategory.BROWSER, "Preparing URL for embedded Fluck browser", mapOf("url" to url))

                // Validate that we have a WebAuthn engine available
                if (webAuthnEngine == null || webAuthnBrowser == null) {
                    logger.warn(LogCategory.BROWSER, "WebAuthn engine not available, falling back to system browser")
                    return@withContext Result.failure(Exception("JxBrowser not available for embedded display"))
                }

                logger.debug(LogCategory.BROWSER, "Ready to display WebAuthn in embedded browser", mapOf("sessionId" to sessionId))
                Result.success(url)
            } catch (e: Exception) {
                logger.error(LogCategory.BROWSER, "Failed to prepare Fluck browser", error = e)
                Result.failure(e)
            }
        }

    /**
     * Fallback method to open browser using ProcessBuilder when Desktop API is not available
     */
    private fun openBrowserWithProcessBuilder(url: String): Result<Unit> =
        try {
            val os = System.getProperty("os.name").lowercase()
            val processBuilder =
                when {
                    os.contains("mac") -> ProcessBuilder("open", url)
                    os.contains("windows") -> ProcessBuilder("cmd", "/c", "start", "", url)
                    os.contains("linux") -> ProcessBuilder("xdg-open", url)
                    else -> throw Exception("Unsupported platform for opening browser: $os")
                }
            processBuilder.start()
            logger.debug(LogCategory.BROWSER, "Successfully opened browser using ProcessBuilder")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error(LogCategory.BROWSER, "Failed to open browser with ProcessBuilder", error = e)
            Result.failure(e)
        }

    /**
     * Check for enhanced external authenticator capabilities
     */
    fun isExternalAuthenticatorAvailable(): Boolean {
        return try {
            val browser = webAuthnBrowser
            if (browser == null) {
                logger.debug(LogCategory.BROWSER, "No JxBrowser instance - using basic OS detection")
                return fallbackExternalAuthenticatorCheck()
            }

            logger.debug(LogCategory.BROWSER, "Using enhanced external authenticator detection via JxBrowser")

            // With properly licensed JxBrowser via FluckEngine, we have enhanced capabilities
            val os = System.getProperty("os.name").lowercase()
            when {
                os.contains("mac") -> true

                // macOS with enhanced WebAuthn support
                os.contains("windows") -> true

                // Windows with enhanced WebAuthn support
                os.contains("linux") -> true

                // Linux with JxBrowser WebAuthn support
                else -> false
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Error in enhanced detection", error = e)
            fallbackExternalAuthenticatorCheck()
        }
    }

    /**
     * Fallback external authenticator check when JxBrowser is not available
     */
    private fun fallbackExternalAuthenticatorCheck(): Boolean {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("mac") -> true

            // macOS has platform authenticator support
            os.contains("windows") -> true

            // Windows Hello support
            os.contains("linux") -> false

            // Limited Linux support without WebAuthn
            else -> false
        }
    }

    /**
     * Show enhanced capabilities information
     */
    suspend fun showEnhancedCapabilities() =
        withContext(Dispatchers.IO) {
            try {
                val hasWebAuthnEngine = webAuthnEngine != null && webAuthnBrowser != null
                val os = System.getProperty("os.name").lowercase()
                val externalAuthAvailable = if (hasWebAuthnEngine) isExternalAuthenticatorAvailable() else false

                logger.info(
                    LogCategory.BROWSER,
                    "WebAuthn capabilities",
                    mapOf(
                        "jxBrowserAvailable" to hasWebAuthnEngine,
                        "enhancedWebAuthn" to hasWebAuthnEngine,
                        "externalAuthMode" to if (externalAuthAvailable) "enhanced" else "basic",
                        "platform" to os,
                        "transports" to
                            if (hasWebAuthnEngine) {
                                listOfNotNull(
                                    "internal",
                                    "usb",
                                    "nfc",
                                    if (os.contains("mac")) "hybrid" else null,
                                ).joinToString()
                            } else {
                                "system-browser-fallback"
                            },
                    ),
                )
            } catch (e: Exception) {
                logger.error(LogCategory.BROWSER, "Error showing enhanced capabilities", error = e)
            }
        }
}
