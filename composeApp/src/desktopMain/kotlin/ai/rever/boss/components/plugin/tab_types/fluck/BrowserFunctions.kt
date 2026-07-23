package ai.rever.boss.components.plugin.tab_types.fluck

import ai.rever.boss.plugin.browser.BrowserSettings
import ai.rever.boss.plugin.browser.EngineInitError
import ai.rever.boss.plugin.browser.FluckEngine
import ai.rever.boss.plugin.browser.LocalAwtWindow
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.browser.event.BrowserClosed
import com.teamdev.jxbrowser.event.Subscription
import com.teamdev.jxbrowser.navigation.event.LoadStarted
import com.teamdev.jxbrowser.ui.Rect
import com.teamdev.jxbrowser.view.compose.BrowserViewState
import com.teamdev.jxbrowser.view.swing.BrowserView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Window
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFrame
import javax.swing.SwingUtilities
import com.teamdev.jxbrowser.browser.callback.AlertCallback
import com.teamdev.jxbrowser.browser.callback.ConfirmCallback
import com.teamdev.jxbrowser.browser.callback.PromptCallback

private val logger = BossLogger.forComponent("BrowserFunctions")

/**
 * Gets a valid AWT Window that is safe to use with JxBrowser.
 * Returns null if no valid window is available.
 *
 * A valid window must be:
 * - Displayable (has native peer created)
 * - Showing (visible on screen)
 */
private fun getValidComposeWindow(): Window? {
    return Window.getWindows()
        .firstOrNull { window ->
            try {
                // Check if window is properly initialized
                window.isDisplayable && window.isShowing
            } catch (e: Exception) {
                // Window might be in invalid state during disposal
                logger.debug(LogCategory.BROWSER, "Window state probe failed during disposal - skipping window", mapOf("error" to e.toString()))
                false
            }
        }
}

/**
 * Configures JavaScript dialog handlers to prevent UI freeze (Issue #369).
 *
 * JxBrowser's JS dialogs (alert, confirm, prompt) use callbacks that block the Chromium
 * process until a response is provided. In Compose Desktop, showing a modal Swing dialog
 * from within these callbacks causes a deadlock between Swing's EDT and JxBrowser's IPC.
 *
 * Solution: Call tell.ok() immediately to unblock JxBrowser, then show a non-blocking
 * informational dialog on the Swing EDT.
 *
 * @param browser The browser instance to configure
 */
private fun setupBrowserDialogHandlers(browser: Browser) {
    val browserId = System.identityHashCode(browser)  // Unique ID for this browser instance

    // Alert callback - unblock immediately, then notify for BOSS-styled dialog
    browser.set(AlertCallback::class.java, AlertCallback { params, tell ->
        val message = params.message()
        val title = params.title()

        // CRITICAL: Call tell.ok() FIRST to unblock JxBrowser
        tell.ok()

        // Emit event for Compose UI to show BOSS-styled dialog
        JsDialogNotifier.notifyAlert(
            browserId = browserId,
            title = title.ifEmpty { "Alert" },
            message = message
        )
    })

    // Confirm callback - behavior based on settings
    browser.set(ConfirmCallback::class.java, ConfirmCallback { params, tell ->
        val message = params.message()
        val title = params.title()
        val confirmed = BrowserSettings.jsConfirmBehavior == BrowserSettings.JsConfirmBehavior.AUTO_CONFIRM

        // CRITICAL: Call tell FIRST to unblock JxBrowser
        if (confirmed) {
            tell.ok()
        } else {
            tell.cancel()
        }

        // Emit event for Compose UI to show BOSS-styled dialog
        JsDialogNotifier.notifyConfirm(
            browserId = browserId,
            title = title.ifEmpty { "Confirm" },
            message = message,
            confirmed = confirmed
        )
    })

    // Prompt callback - value based on settings
    browser.set(PromptCallback::class.java, PromptCallback { params, tell ->
        val message = params.message()
        val pageDefault = params.text()  // text() returns the page's default prompt value
        val title = params.title()

        // Determine value to use based on settings
        val valueToUse = if (BrowserSettings.jsPromptUsePageDefault) {
            pageDefault
        } else {
            BrowserSettings.jsPromptDefaultValue
        }

        // CRITICAL: Call tell.ok() FIRST to unblock JxBrowser
        tell.ok(valueToUse)

        // Emit event for Compose UI to show BOSS-styled dialog
        JsDialogNotifier.notifyPrompt(
            browserId = browserId,
            title = title.ifEmpty { "Prompt" },
            message = message,
            value = valueToUse
        )
    })
}

/**
 * Configures browser to handle popup requests intelligently based on window features.
 *
 * Complete solution using JxBrowser's two-phase popup handling (Issue #137, Issue #173):
 * 1. CreatePopupCallback - Allows all popup creation (returns create())
 * 2. OpenPopupCallback - Checks initialBounds() to decide:
 *    - Empty bounds (regular links) → Open as tab in BOSS (Issue #137)
 *    - Non-empty bounds (OAuth popups) → Create Swing window to display popup (Issue #173)
 *
 * @param browser The browser instance to configure
 * @param onOpenInNewTab Callback invoked with the target URL when popup should open in new tab
 */
private fun configureBrowserPopupHandler(
    browser: Browser,
    onOpenInNewTab: (String) -> Unit
) {
    // Phase 1: Allow all popup creation
    browser.set(
        com.teamdev.jxbrowser.browser.callback.CreatePopupCallback::class.java,
        com.teamdev.jxbrowser.browser.callback.CreatePopupCallback {
            com.teamdev.jxbrowser.browser.callback.CreatePopupCallback.Response.create()
        }
    )

    // Phase 2: Handle popup display based on bounds
    browser.set(
        com.teamdev.jxbrowser.browser.callback.OpenPopupCallback::class.java,
        com.teamdev.jxbrowser.browser.callback.OpenPopupCallback { params ->
            val popupBrowser = params.popupBrowser()
            val initialBounds = params.initialBounds()
            val targetUrl = popupBrowser.url()

            val isEmptyBounds = initialBounds == Rect.empty()

            if (isEmptyBounds) {
                if (targetUrl.isEmpty() || targetUrl == "about:blank") {
                    val cleanedUp = AtomicBoolean(false)
                    var subscription: Subscription? = null
                    val scope = CoroutineScope(Dispatchers.Default + Job())

                    // Use LoadStarted instead of NavigationFinished for immediate response
                    subscription = popupBrowser.navigation().on(LoadStarted::class.java) {
                        try {
                            // Issue #255: Protect popup browser URL access from "closed object" exception
                            val loadedUrl = popupBrowser.url()
                            if (loadedUrl.isNotEmpty() && loadedUrl != "about:blank") {
                                if (cleanedUp.compareAndSet(false, true)) {
                                    val isDownload = FluckEngine.isActiveDownload(loadedUrl)
                                    logger.debug(LogCategory.BROWSER, "Popup LoadStarted", mapOf("url" to loadedUrl, "isDownload" to isDownload))
                                    if (!isDownload) {
                                        FluckEngine.notifyTabOpened()
                                        onOpenInNewTab(loadedUrl)
                                    } else {
                                        logger.debug(LogCategory.BROWSER, "Skipping new tab for download URL")
                                    }
                                    subscription?.unsubscribe()
                                    scope.cancel()
                                    if (!popupBrowser.isClosed) {
                                        popupBrowser.close()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // Issue #255: Gracefully handle "closed object" exceptions
                            logger.debug(LogCategory.BROWSER, "Popup browser hit closed-object exception - cleaning up", mapOf("error" to e.toString()))
                            if (cleanedUp.compareAndSet(false, true)) {
                                subscription?.unsubscribe()
                                scope.cancel()
                            }
                        }
                    }

                    scope.launch {
                        delay(3_000)
                        if (cleanedUp.compareAndSet(false, true)) {
                            subscription?.unsubscribe()
                            if (!popupBrowser.isClosed) {
                                popupBrowser.close()
                            }
                            logger.warn(LogCategory.BROWSER, "Popup navigation timed out after 3s, closing browser")
                        }
                    }
                } else {
                    val isDownload = FluckEngine.isActiveDownload(targetUrl)
                    logger.debug(LogCategory.BROWSER, "Popup with immediate URL", mapOf("url" to targetUrl, "isDownload" to isDownload))
                    if (!isDownload) {
                        FluckEngine.notifyTabOpened()
                        onOpenInNewTab(targetUrl)
                    } else {
                        logger.debug(LogCategory.BROWSER, "Skipping new tab for download URL")
                    }
                    popupBrowser.close()
                }
            } else {
                SwingUtilities.invokeLater {
                    try {
                        val frame = JFrame()
                        val subscriptions = mutableListOf<Subscription>()

                        frame.title = "Popup"
                        frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                        frame.setLocation(initialBounds.origin().x(), initialBounds.origin().y())
                        frame.setSize(initialBounds.size().width(), initialBounds.size().height())

                        val browserView = BrowserView.newInstance(popupBrowser)
                        frame.contentPane.add(browserView)

                        subscriptions += popupBrowser.on(com.teamdev.jxbrowser.browser.event.TitleChanged::class.java) { event ->
                            SwingUtilities.invokeLater {
                                frame.title = event.title()
                            }
                        }

                        subscriptions += popupBrowser.on(BrowserClosed::class.java) {
                            SwingUtilities.invokeLater {
                                subscriptions.forEach { it.unsubscribe() }
                                frame.dispose()
                            }
                        }

                        frame.addWindowListener(object : java.awt.event.WindowAdapter() {
                            override fun windowClosing(e: java.awt.event.WindowEvent?) {
                                subscriptions.forEach {
                                    try {
                                        it.unsubscribe()
                                    } catch (_: Exception) {
                                        // Intentional: ignore errors during cleanup
                                    }
                                }
                                if (!popupBrowser.isClosed) {
                                    popupBrowser.close()
                                }
                            }
                        })

                        frame.isVisible = true
                    } catch (e: Exception) {
                        logger.error(LogCategory.BROWSER, "Error creating popup window", error = e)
                        if (!popupBrowser.isClosed) {
                            popupBrowser.close()
                        }
                    }
                }
            }

            com.teamdev.jxbrowser.browser.callback.OpenPopupCallback.Response.proceed()
        }
    )
}

actual fun createBrowser(): Any {
    val browser = FluckEngine.engine.newBrowser()
    browser.settings().enableOverscrollHistoryNavigation()
    FluckEngine.setupBrowserDownloadHandler(browser as com.teamdev.jxbrowser.browser.Browser)
    FluckEngine.setupCaptureSessionHandler(browser)
    FluckEngine.setupKeyboardInterceptor(browser)
    return browser
}

actual suspend fun resetBrowserProfile(): Boolean {
    return FluckEngine.resetBrowserProfile().success
}

actual fun disposeBrowser(browser: Any) {
    try {
        val jxBrowser = browser as? Browser
        if (jxBrowser != null) {
            FluckEngine.disposeBrowserFindBar(jxBrowser)
            if (!jxBrowser.isClosed) {
                jxBrowser.close()
            }
        }
    } catch (e: Exception) {
        logger.warn(LogCategory.BROWSER, "Exception during browser disposal", mapOf("error" to (e.message ?: "unknown")))
    }
}

actual fun createBrowserViewState(browser: Any, window: Any?): Any? {
    val jxBrowser = browser as Browser

    val awtWindow = (window as? Window)?.takeIf {
        try {
            it.isDisplayable && it.isShowing
        } catch (e: Exception) {
            // Window can be mid-disposal - fall back to scanning open windows
            logger.debug(LogCategory.BROWSER, "Provided window probe failed - falling back to window scan", mapOf("error" to e.toString()))
            false
        }
    } ?: getValidComposeWindow()

    if (awtWindow == null) {
        logger.warn(LogCategory.BROWSER, "No valid window available for BrowserViewState - window may not be ready yet")
        return null
    }

    return BrowserViewState(jxBrowser, MainScope(), awtWindow)
}

actual fun recreateBrowserViewState(browser: Any, window: Any?): Any? {
    val jxBrowser = browser as? Browser
    if (jxBrowser == null || jxBrowser.isClosed) {
        logger.warn(LogCategory.BROWSER, "Cannot recreate BrowserViewState - browser is null or closed")
        return null
    }

    val awtWindow = (window as? Window)?.takeIf {
        try {
            it.isDisplayable && it.isShowing
        } catch (e: Exception) {
            // Window can be mid-disposal - fall back to scanning open windows
            logger.debug(LogCategory.BROWSER, "Provided window probe failed - falling back to window scan", mapOf("error" to e.toString()))
            false
        }
    } ?: getValidComposeWindow()

    if (awtWindow == null) {
        logger.warn(LogCategory.BROWSER, "No valid window for BrowserViewState recreation")
        return null
    }

    logger.info(LogCategory.BROWSER, "Recreating BrowserViewState after fullscreen exit")
    return BrowserViewState(jxBrowser, MainScope(), awtWindow)
}

actual fun closeBrowserViewState(viewState: Any) {
    try {
        val state = viewState as? BrowserViewState
        if (state != null) {
            logger.info(LogCategory.BROWSER, "Closing old BrowserViewState before recreation")
            state.close()
        }
    } catch (e: Exception) {
        logger.warn(LogCategory.BROWSER, "Error closing BrowserViewState", error = e)
    }
}

actual fun disposeBrowserViewState(browserViewState: Any) {
    // BrowserViewState doesn't have explicit disposal on JVM
}

actual fun getBrowserState(
    url: String,
    onOpenInNewTab: ((String) -> Unit)?,
    onBrowserClosed: (() -> Unit)?,
    window: Any?
): Pair<Any, Any>? {
    return try {
        val engine = FluckEngine.engine
        if (engine.isClosed) {
            logger.warn(LogCategory.BROWSER, "getBrowserState: Engine is closed, cannot create browser")
            return null
        }

        val browser = createBrowser() as Browser

        if (browser.isClosed) {
            logger.warn(LogCategory.BROWSER, "getBrowserState: Browser was closed immediately after creation")
            return null
        }

        // Subscribe to BrowserClosed event for event-driven recovery (Issue #351)
        if (onBrowserClosed != null) {
            browser.on(BrowserClosed::class.java) {
                logger.debug(LogCategory.BROWSER, "BrowserClosed event fired")
                onBrowserClosed()
            }
        }

        // Configure JS dialog handlers to prevent UI freeze (Issue #369)
        setupBrowserDialogHandlers(browser)

        // Configure popup handler: OAuth popups → real windows, regular links → tabs
        if (onOpenInNewTab != null) {
            configureBrowserPopupHandler(browser, onOpenInNewTab)
        }

        // Pass window from LocalAwtWindow for correct multi-window support
        val browserViewState = createBrowserViewState(browser, window)

        if (browserViewState == null) {
            logger.warn(LogCategory.BROWSER, "getBrowserState: Could not create BrowserViewState - no valid window available")
            if (!browser.isClosed) {
                browser.close()
            }
            return null
        }

        if (!browser.isClosed && url != "about:blank" && url.isNotEmpty()) {
            browser.navigation().loadUrl(url)
        }

        Pair(browser, browserViewState)
    } catch (e: Exception) {
        val errorType = when {
            e.message?.contains("closed object", ignoreCase = true) == true ->
                "JxBrowser closed object error (engine or browser was disposed)"
            e.message?.contains("SharedMemory", ignoreCase = true) == true ->
                "JxBrowser IPC error (Chromium process may have crashed)"
            else -> "Unknown error"
        }
        logger.error(LogCategory.BROWSER, "getBrowserState failed: $errorType", mapOf("details" to (e.message ?: "none")))
        null
    }
}

actual fun getEngineGeneration(): Long = FluckEngine.currentEngineGeneration

actual fun isBrowserValid(browser: Any?): Boolean {
    if (browser == null) return false
    val jxBrowser = browser as? Browser ?: return false
    return try {
        !jxBrowser.isClosed
    } catch (e: Exception) {
        logger.warn(LogCategory.BROWSER, "isBrowserValid: Exception checking browser state", mapOf("error" to (e.message ?: "unknown")))
        false
    }
}

actual fun getEngineInitError(): String? {
    return FluckEngine.initError?.let { error ->
        when (error) {
            is EngineInitError.LicenseValidation -> error.message
            is EngineInitError.NetworkError -> error.message
            is EngineInitError.Other -> error.message
        }
    }
}

actual fun resetEngineInitialization() {
    FluckEngine.resetInitializationState()
}

actual fun getMaxInitRetries(): Int = BrowserSettings.maxInitRetries

actual fun getMaxRecoveryAttempts(): Int = BrowserSettings.maxRecoveryAttempts

@Composable
actual fun collectEngineGeneration(): Long {
    val generation by FluckEngine.engineGenerationFlow.collectAsState()
    return generation
}

@Composable
actual fun getCurrentAwtWindow(): Any? = LocalAwtWindow.current
