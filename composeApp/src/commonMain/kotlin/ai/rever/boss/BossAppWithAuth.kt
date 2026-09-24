package ai.rever.boss

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

    // Deep link handling for auth has been moved to DeepLinkHandler centrally.

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
