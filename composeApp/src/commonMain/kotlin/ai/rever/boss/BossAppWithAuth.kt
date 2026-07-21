package ai.rever.boss

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.runtime.*
import androidx.compose.runtime.key
import ai.rever.boss.components.auth.LoginScreen
import ai.rever.boss.components.misc.LoadingScreen
import ai.rever.boss.components.misc.OfflineScreen
import ai.rever.boss.services.auth.CoreAuthService
import ai.rever.boss.services.auth.MagicLinkErrorService
import ai.rever.boss.services.auth.PasskeySessionEventHandler
import ai.rever.boss.services.supabase.AuthService
import ai.rever.boss.utils.DeepLinkHandler
import ai.rever.boss.utils.WindowFocusManager
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.launch

private val logger = BossLogger.forComponent("BossAppWithAuth")

/**
 * Main app entry point with authentication
 *
 * @param windowId The ID of the window this app instance belongs to
 * @param isFirstWindow Whether this is the first window (for workspace loading)
 * @param panelRegistry The panel registry instance for this window
 */
@Composable
fun ComponentContext.BossAppWithAuth(
    windowId: String,
    isFirstWindow: Boolean = false,
    panelRegistry: ai.rever.boss.components.registery.PanelRegistry,
    onToggleMaximize: (() -> Unit)? = null
) {
    val authState by AuthService.authState.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    
    // Initialize authentication service
    LaunchedEffect(Unit) {
        AuthService.initialize()
    }
    
    // Handle deep links for email verification
    val deepLink by DeepLinkHandler.deepLinkFlow.collectAsState()

    LaunchedEffect(deepLink) {
        // Todo: Why this can not be in DeepLinkHandler itself, may be we can just have
        //  LaunchedEffect here and rest of the code inside DeepLinkHandler
        deepLink?.let { uri ->
            logger.debug(LogCategory.AUTH, "Received deep link in app", mapOf("uri" to uri))

            // Bring window to front
            WindowFocusManager.bringToFront()

            val sessionId = try {
                val regex = Regex("sessionId=([^&]+)")
                regex.find(uri)?.groupValues?.get(1)
            } catch (_: Exception) {
                logger.warn(LogCategory.AUTH, "Failed to extract sessionId from deep link", mapOf("uri" to uri))
                null
            }

            when {
                uri.contains("passkey/registered") -> {
                    sessionId?.let { id ->
                        logger.info(LogCategory.AUTH, "Passkey registration completed", mapOf("sessionId" to id))
                        PasskeySessionEventHandler.handleRegistrationCompleted(id)
                    }
                    DeepLinkHandler.clearDeepLink()
                }
                uri.contains("passkey/authenticated") -> {
                    sessionId?.let { id ->
                        logger.info(LogCategory.AUTH, "Passkey authentication completed", mapOf("sessionId" to id))

                        // Trigger the polling check to complete authentication
                        coroutineScope.launch {
                            // The CrossDeviceAuthService is already polling, but we can trigger
                            // an immediate check when we receive the deep link
                            val metadata = PasskeySessionEventHandler.getSessionMetadata(id)
                            metadata?.let { session ->
                                logger.debug(LogCategory.AUTH, "Checking authentication status", mapOf("sessionId" to id))

                                // Notify that authentication completed
                                PasskeySessionEventHandler.handleAuthenticationCompleted(id)
                            } ?: run {
                                logger.warn(LogCategory.AUTH, "No metadata found for session", mapOf("sessionId" to id))
                            }
                        }
                    }
                    DeepLinkHandler.clearDeepLink()
                }
                uri.contains("auth/verify") -> {
                    val token = DeepLinkHandler.extractVerificationToken(uri)
                    val type = DeepLinkHandler.extractVerificationType(uri) ?: "magiclink"
                    token?.let { token ->
                        logger.debug(LogCategory.AUTH, "Extracted verification token", mapOf("type" to type))
                        coroutineScope.launch {
                            // Handle magic link authentication
                            logger.info(LogCategory.AUTH, "Starting magic link authentication process")

                            AuthService.verifyEmail(token, type).fold(
                                onSuccess = {
                                    logger.info(LogCategory.AUTH, "Magic link authentication successful")
                                    if (authState is AuthService.AuthState.NotAuthenticated) {
                                        // Trigger a refresh to check if user can now sign in
                                        AuthService.initialize()
                                    }

                                },
                                onFailure = { error ->
                                    logger.error(LogCategory.AUTH, "Magic link authentication failed", error = error)
                                    // Set error so UI can display it
                                    MagicLinkErrorService.setError(
                                        error.message ?: "Magic link verification failed"
                                    )
                                }
                            )
                        }
                    }

                    DeepLinkHandler.clearDeepLink()
                }
                else -> {
                    // Route non-auth deep links (boss://url, boss://file, boss://folder, boss://terminal, boss://workspace)
                    // back to DeepLinkHandler for processing
                    logger.debug(LogCategory.AUTH, "Routing non-auth deep link to DeepLinkHandler")
                    DeepLinkHandler.processDeepLink(uri)
                    DeepLinkHandler.clearDeepLink()
                }
            }
        }
    }
    
    // Debug auth state changes
    LaunchedEffect(authState) {
        logger.debug(LogCategory.AUTH, "AuthState changed", mapOf("state" to authState.toString()))
    }
    
    when (authState) {
        is AuthService.AuthState.Loading -> {
            // Show loading screen
            logger.debug(LogCategory.AUTH, "Showing loading screen")
            LoadingScreen()
        }

        is AuthService.AuthState.Offline -> {
            // Show offline screen with retry button
            logger.debug(LogCategory.AUTH, "Showing offline screen")
            OfflineScreen(
                onRetry = {
                    CoreAuthService.retryInitialization()
                }
            )
        }

        is AuthService.AuthState.NotAuthenticated,
        is AuthService.AuthState.Error -> {
            // Show login screen (it will handle 2FA verification internally)
            // Use key() to prevent recreation when switching between these states
            key("login_screen") {
                LoginScreen(
                    onLoginSuccess = {
                        // This will be called after successful login (and 2FA if required)
                    }
                )
            }
        }

        is AuthService.AuthState.Authenticated -> {
            // Show main BOSS app - all auth methods provide inherent 2FA
            // Plugin wizard is shown inside BossApp where DynamicPluginManager is accessible
            BossApp(
                windowId = windowId,
                isFirstWindow = isFirstWindow,
                panelRegistry = panelRegistry,
                onToggleMaximize = onToggleMaximize
            )
        }
    }
}
