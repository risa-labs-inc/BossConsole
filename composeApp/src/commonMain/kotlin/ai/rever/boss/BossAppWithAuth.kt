package ai.rever.boss

import ai.rever.boss.components.auth.AuthDeepLink
import ai.rever.boss.components.auth.AuthDeepLinks
import ai.rever.boss.components.auth.LoginScreen
import ai.rever.boss.components.misc.LoadingScreen
import ai.rever.boss.components.misc.OfflineScreen
import ai.rever.boss.services.auth.CoreAuthService
import ai.rever.boss.services.auth.MagicLinkErrorService
import ai.rever.boss.services.auth.PasskeySessionEventHandler
import ai.rever.boss.services.supabase.AuthService
import ai.rever.boss.utils.DeepLinkHandler
import ai.rever.boss.utils.WindowFocusManager
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.LogSanitizer
import androidx.compose.runtime.*
import androidx.compose.runtime.key
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
    onToggleMaximize: (() -> Unit)? = null,
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
            logger.debug(LogCategory.AUTH, "Received deep link in app", mapOf("uri" to LogSanitizer.describeUri(uri)))

            // Bring window to front
            WindowFocusManager.bringToFront()

            when (val link = AuthDeepLinks.parse(uri)) {
                is AuthDeepLink.PasskeyRegistered -> {
                    logger.info(
                        LogCategory.AUTH,
                        "Passkey registration completed",
                        mapOf("sessionId" to LogSanitizer.maskSessionId(link.sessionId)),
                    )
                    PasskeySessionEventHandler.handleRegistrationCompleted(link.sessionId)
                    DeepLinkHandler.clearDeepLink()
                }

                is AuthDeepLink.PasskeyAuthenticated -> {
                    logger.info(
                        LogCategory.AUTH,
                        "Passkey authentication completed",
                        mapOf("sessionId" to LogSanitizer.maskSessionId(link.sessionId)),
                    )

                    // Trigger the polling check to complete authentication
                    coroutineScope.launch {
                        // The CrossDeviceAuthService is already polling, but we can trigger
                        // an immediate check when we receive the deep link
                        val metadata = PasskeySessionEventHandler.getSessionMetadata(link.sessionId)
                        metadata?.let { session ->
                            logger.debug(
                                LogCategory.AUTH,
                                "Checking authentication status",
                                mapOf("sessionId" to LogSanitizer.maskSessionId(link.sessionId)),
                            )

                            // Notify that authentication completed
                            PasskeySessionEventHandler.handleAuthenticationCompleted(link.sessionId)
                        } ?: run {
                            logger.warn(
                                LogCategory.AUTH,
                                "No metadata found for session",
                                mapOf("sessionId" to LogSanitizer.maskSessionId(link.sessionId)),
                            )
                        }
                    }
                    DeepLinkHandler.clearDeepLink()
                }

                is AuthDeepLink.MagicLinkVerify -> {
                    logger.debug(LogCategory.AUTH, "Extracted verification token", mapOf("type" to link.type))
                    coroutineScope.launch {
                        // Handle magic link authentication
                        logger.info(LogCategory.AUTH, "Starting magic link authentication process")

                        AuthService.verifyEmail(link.token, link.type).fold(
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
                                    error.message ?: "Magic link verification failed",
                                )
                            },
                        )
                    }

                    DeepLinkHandler.clearDeepLink()
                }

                else -> {
                    // Route non-auth deep links (boss://url, boss://file, boss://folder, boss://terminal, boss://workspace)
                    // back to DeepLinkHandler for processing. A link that only LOOKS like an
                    // auth ceremony reaches this branch too — parse refused it — so name that
                    // case before the generic routing: a sign-in email link the OS mangled
                    // must not disappear inside "non-auth" with no trace of the refusal.
                    if (AuthDeepLinks.isAuthShaped(uri)) {
                        logger.warn(
                            LogCategory.AUTH,
                            "Auth-shaped deep link refused; falling through to the generic deep-link router",
                            mapOf("uri" to LogSanitizer.describeUri(uri)),
                        )
                    } else {
                        logger.debug(LogCategory.AUTH, "Routing non-auth deep link to DeepLinkHandler")
                    }
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
                },
            )
        }

        is AuthService.AuthState.NotAuthenticated,
        is AuthService.AuthState.Error,
        -> {
            // Show login screen (it will handle 2FA verification internally)
            // Use key() to prevent recreation when switching between these states
            key("login_screen") {
                LoginScreen(
                    onLoginSuccess = {
                        // This will be called after successful login (and 2FA if required)
                    },
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
                onToggleMaximize = onToggleMaximize,
            )
        }
    }
}
