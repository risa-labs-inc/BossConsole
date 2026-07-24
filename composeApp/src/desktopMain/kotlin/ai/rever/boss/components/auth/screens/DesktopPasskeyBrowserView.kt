package ai.rever.boss.components.auth.screens

import ai.rever.boss.plugin.browser.FluckEngine
import ai.rever.boss.plugin.browser.LocalAwtWindow
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.navigation.event.LoadFinished
import com.teamdev.jxbrowser.navigation.event.LoadStarted
import com.teamdev.jxbrowser.view.compose.BrowserView
import com.teamdev.jxbrowser.view.compose.BrowserViewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.awt.Frame
import java.awt.Window

private val logger = BossLogger.forComponent("DesktopPasskeyBrowserView")

/**
 * Desktop implementation of PasskeyBrowserView using JxBrowser
 * Embeds a Chromium browser instance for WebAuthn operations
 */
@Composable
actual fun PasskeyBrowserView(
    url: String,
    onLoadComplete: () -> Unit,
    onError: (String) -> Unit,
) {
    var browser by remember { mutableStateOf<Browser?>(null) }
    var initError by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    // Initialize browser when composable enters composition
    DisposableEffect(url) {
        try {
            logger.debug(LogCategory.BROWSER, "Initializing JxBrowser for WebAuthn")

            // Get browser instance from FluckEngine (throws exception if initialization fails)
            val engine = FluckEngine.engine

            // Create new browser instance for WebAuthn
            val newBrowser = engine.newBrowser()
            browser = newBrowser

            logger.debug(LogCategory.BROWSER, "JxBrowser initialized successfully")

            // Register load event handlers
            newBrowser.navigation().on(LoadStarted::class.java) {
                logger.debug(LogCategory.BROWSER, "Page loading started")
            }

            newBrowser.navigation().on(LoadFinished::class.java) {
                logger.debug(LogCategory.BROWSER, "Page loaded successfully", mapOf("url" to newBrowser.url()))
                coroutineScope.launch(Dispatchers.Main) {
                    onLoadComplete()
                }
            }

            // Load the WebAuthn URL
            logger.debug(LogCategory.BROWSER, "Loading WebAuthn URL")
            newBrowser.navigation().loadUrl(url)
        } catch (e: Exception) {
            val errorMessage = "Failed to initialize browser: ${e.message}"
            logger.warn(LogCategory.BROWSER, errorMessage, error = e)
            initError = errorMessage
            onError(errorMessage)
        }

        // Cleanup on disposal
        onDispose {
            logger.debug(LogCategory.BROWSER, "Disposing browser instance")
            try {
                browser?.close()
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "Error closing browser", error = e)
            }
        }
    }

    // Display browser view or error message
    if (initError != null) {
        // Show error message if initialization failed
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(BossTheme.colors.ink),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initError ?: "Failed to initialize browser",
                color = BossTheme.colors.textPrimary,
            )
        }
    } else if (browser != null) {
        // Embed JxBrowser view in Compose using native BrowserView
        // Create BrowserViewState for this specific browser instance
        // Use LocalAwtWindow for multi-window support, fallback to first available window
        val localWindow = LocalAwtWindow.current
        val window =
            remember(localWindow) {
                localWindow ?: Window.getWindows().firstOrNull() ?: Frame()
            }
        val browserViewState =
            remember(browser, window) {
                BrowserViewState(browser!!, MainScope(), window)
            }

        BrowserView(
            state = browserViewState,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        // Show placeholder while initializing
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(BossTheme.colors.ink),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Initializing browser...",
                color = BossTheme.colors.textPrimary,
            )
        }
    }
}
