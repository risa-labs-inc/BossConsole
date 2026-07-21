package ai.rever.boss.tabfullscreen

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.view.swing.BrowserView
import java.awt.BorderLayout
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Creates and manages a fullscreen Swing JFrame for browser content.
 * Uses the existing browser instance with a new BrowserView.
 * Uses native macOS fullscreen mode (creates new Space) for proper fullscreen experience.
 */
object FullscreenBrowserWindow {
    private val logger = BossLogger.forComponent("FullscreenBrowserWindow")
    private var fullscreenFrame: JFrame? = null
    private var currentBrowserView: BrowserView? = null
    private var onExitCallback: (() -> Unit)? = null
    private var isInFullscreenMode = false
    private var hasReachedFullscreen = false  // True only after fullscreen animation completes
    private var isExiting = false  // Prevent multiple exit calls

    private val isMacOS = System.getProperty("os.name").lowercase().contains("mac")

    // macOS fullscreen animation takes ~500ms, wait before enabling exit detection
    private const val FULLSCREEN_ANIMATION_DELAY_MS = 600

    // Delay to allow Compose BrowserView to detach before creating Swing BrowserView
    // This prevents both views from competing for rendering (which causes video freeze)
    private const val COMPOSE_DETACH_DELAY_MS = 100

    // Delay to allow Swing BrowserView to release rendering before Compose BrowserView activates
    // Exit needs more time because we need to ensure the Swing view fully releases the surface
    private const val SWING_RELEASE_DELAY_MS = 200

    fun showFullscreen(browser: Browser, tabId: String, onExit: () -> Unit) {
        // Prevent duplicate calls
        if (fullscreenFrame != null || isInFullscreenMode) {
            logger.warn(LogCategory.BROWSER, "Fullscreen already active, ignoring duplicate request")
            return
        }

        // Mark fullscreen state FIRST so Compose BrowserView hides immediately
        // This triggers recomposition in JxBrowserCompose.kt, replacing BrowserView with placeholder
        isInFullscreenMode = true
        onExitCallback = onExit
        isExiting = false
        hasReachedFullscreen = false
        TabFullscreenStateManager.enterFullscreen(tabId)

        logger.info(LogCategory.BROWSER, "Fullscreen state set, waiting for Compose detach", mapOf("tabId" to tabId))

        // Delay window creation to allow Compose BrowserView to detach from rendering
        // This gives JxBrowser time to release the Compose rendering surface
        SwingUtilities.invokeLater {
            Timer(COMPOSE_DETACH_DELAY_MS) {
                SwingUtilities.invokeLater {
                    createFullscreenWindow(browser, tabId)
                }
            }.apply {
                isRepeats = false
                start()
            }
        }
    }

    /**
     * Creates and displays the fullscreen window with a Swing BrowserView.
     * Called after Compose BrowserView has had time to detach from rendering.
     */
    private fun createFullscreenWindow(browser: Browser, tabId: String) {
        try {
            // Check if we've been cancelled during the delay
            if (!isInFullscreenMode) {
                logger.warn(LogCategory.BROWSER, "Fullscreen cancelled during delay")
                return
            }

            // Double-check for race conditions
            if (fullscreenFrame != null) {
                logger.warn(LogCategory.BROWSER, "Fullscreen already active (race condition prevented)")
                return
            }

            val frame = JFrame()
            frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
            frame.background = Color.BLACK
            frame.contentPane.background = Color.BLACK
            frame.contentPane.layout = BorderLayout()

            // Create BrowserView for existing browser instance
            // At this point, the Compose BrowserView should be detached from rendering
            val browserView = BrowserView.newInstance(browser)
            browserView.background = Color.BLACK
            frame.contentPane.add(browserView, BorderLayout.CENTER)

            logger.info(LogCategory.BROWSER, "Swing BrowserView created after Compose detach delay")

            // Handle window close
            frame.addWindowListener(object : WindowAdapter() {
                override fun windowClosing(e: WindowEvent?) {
                    performExit()
                }
            })

            // Detect when exiting native fullscreen (green button or ESC)
            // Only active after fullscreen animation completes
            frame.addComponentListener(object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent?) {
                    // Only check for exit after we've confirmed fullscreen was reached
                    if (hasReachedFullscreen && isInFullscreenMode && !isExiting && fullscreenFrame != null) {
                        SwingUtilities.invokeLater {
                            if (!isWindowInFullscreen(frame) && !isExiting) {
                                logger.info(LogCategory.BROWSER, "Native fullscreen exited via resize detection")
                                performExit()
                            }
                        }
                    }
                }
            })

            fullscreenFrame = frame
            currentBrowserView = browserView

            if (isMacOS) {
                // Use native macOS fullscreen (creates new Space)
                frame.rootPane.putClientProperty("apple.awt.fullscreenable", true)
                frame.setSize(800, 600) // Initial size before fullscreen
                frame.setLocationRelativeTo(null)
                frame.isVisible = true

                // Request native fullscreen toggle after window is visible
                SwingUtilities.invokeLater {
                    requestMacOSFullscreen(frame)

                    // Wait for fullscreen animation to complete before enabling exit detection
                    Timer(FULLSCREEN_ANIMATION_DELAY_MS) {
                        if (fullscreenFrame != null && isInFullscreenMode) {
                            hasReachedFullscreen = true
                            logger.info(LogCategory.BROWSER, "Fullscreen animation completed, exit detection enabled")
                        }
                    }.apply {
                        isRepeats = false
                        start()
                    }
                }
            } else {
                // Windows/Linux: use maximized undecorated window
                frame.isUndecorated = true
                val gd = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
                val screenBounds = gd.defaultConfiguration.bounds
                frame.setBounds(screenBounds.x, screenBounds.y, screenBounds.width, screenBounds.height)
                frame.isVisible = true
                hasReachedFullscreen = true
            }

            frame.toFront()
            frame.requestFocus()
            browserView.requestFocusInWindow()

            logger.info(LogCategory.BROWSER, "Fullscreen window opened", mapOf("tabId" to tabId, "isMacOS" to isMacOS))
        } catch (e: Exception) {
            logger.error(LogCategory.BROWSER, "Failed to create fullscreen window", error = e)
            resetState()
            TabFullscreenStateManager.exitFullscreen()
            onExitCallback?.invoke()
        }
    }

    /**
     * Request native macOS fullscreen using reflection.
     * Uses com.apple.eawt.Application.requestToggleFullScreen() which creates
     * a proper macOS fullscreen Space (like Chrome/Safari behavior).
     */
    private fun requestMacOSFullscreen(window: Window) {
        try {
            val appClass = Class.forName("com.apple.eawt.Application")
            val getAppMethod = appClass.getDeclaredMethod("getApplication")
            getAppMethod.isAccessible = true
            val app = getAppMethod.invoke(null)
            val requestToggleMethod = appClass.getDeclaredMethod("requestToggleFullScreen", Window::class.java)
            requestToggleMethod.isAccessible = true
            requestToggleMethod.invoke(app, window)
            logger.info(LogCategory.BROWSER, "Requested macOS native fullscreen")
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Could not request macOS fullscreen, using fallback", error = e)
            // Fallback: maximize window
            (window as? JFrame)?.extendedState = JFrame.MAXIMIZED_BOTH
            hasReachedFullscreen = true
        }
    }

    /**
     * Check if window is currently in fullscreen state (macOS).
     * On macOS in fullscreen, the window bounds match the screen bounds exactly.
     */
    private fun isWindowInFullscreen(frame: JFrame): Boolean {
        val gd = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
        val screenBounds = gd.defaultConfiguration.bounds
        val frameBounds = frame.bounds
        return frameBounds.width >= screenBounds.width && frameBounds.height >= screenBounds.height
    }

    /**
     * Reset all state variables.
     */
    private fun resetState() {
        fullscreenFrame = null
        currentBrowserView = null
        onExitCallback = null
        isInFullscreenMode = false
        hasReachedFullscreen = false
        isExiting = false
    }

    /**
     * Shared cleanup logic for exiting fullscreen mode.
     * Hides and detaches the Swing BrowserView, disposes the frame,
     * and signals TabFullscreenStateManager after a delay.
     *
     * @param frame The JFrame to dispose
     * @param browserView The BrowserView to detach (nullable)
     * @param callback Optional callback to invoke after cleanup completes
     */
    private fun cleanupAndExit(frame: JFrame, browserView: BrowserView?, callback: (() -> Unit)?) {
        SwingUtilities.invokeLater {
            try {
                // Hide and detach the Swing BrowserView to release rendering surface
                browserView?.let { view ->
                    view.isVisible = false
                    view.repaint()
                    frame.contentPane.remove(view)
                }
                frame.contentPane.revalidate()
                frame.contentPane.repaint()
                frame.dispose()
                logger.info(LogCategory.BROWSER, "Fullscreen window disposed, waiting for rendering release")

                // Delay before telling Compose to show its BrowserView
                // This gives JxBrowser time to fully release the Swing rendering surface
                Timer(SWING_RELEASE_DELAY_MS) {
                    SwingUtilities.invokeLater {
                        TabFullscreenStateManager.exitFullscreen()
                        logger.info(LogCategory.BROWSER, "Fullscreen exit complete, Compose BrowserView enabled")
                        callback?.invoke()
                    }
                }.apply {
                    isRepeats = false
                    start()
                }
            } catch (e: Exception) {
                logger.error(LogCategory.BROWSER, "Error closing fullscreen window", error = e)
                TabFullscreenStateManager.exitFullscreen()
                callback?.invoke()
            }
        }
    }

    /**
     * Internal method to perform exit and invoke callback.
     */
    private fun performExit() {
        if (isExiting || !isInFullscreenMode) return
        isExiting = true

        val callback = onExitCallback
        exitFullscreen()
        callback?.invoke()
    }

    /**
     * Called when user manually triggers exit (placeholder click).
     * On macOS, toggles fullscreen off which triggers the exit flow.
     */
    fun requestExit() {
        if (isExiting) return
        logger.info(LogCategory.BROWSER, "Exit requested via placeholder click")

        val frame = fullscreenFrame ?: return

        if (isMacOS && hasReachedFullscreen) {
            // Toggle fullscreen off (will trigger componentResized -> performExit)
            isExiting = true
            requestMacOSFullscreen(frame)
            // Also schedule a direct exit in case the toggle doesn't work
            Timer(FULLSCREEN_ANIMATION_DELAY_MS) {
                if (fullscreenFrame != null) {
                    performExitDirect()
                }
            }.apply {
                isRepeats = false
                start()
            }
        } else {
            performExitDirect()
        }
    }

    /**
     * Direct exit without relying on fullscreen toggle.
     */
    private fun performExitDirect() {
        val frame = fullscreenFrame ?: return
        val browserView = currentBrowserView
        val callback = onExitCallback

        resetState()
        cleanupAndExit(frame, browserView, callback)
    }

    /**
     * Closes the fullscreen window.
     * Safe to call multiple times - will only close once.
     */
    fun exitFullscreen() {
        val frame = fullscreenFrame ?: return
        val browserView = currentBrowserView

        resetState()
        cleanupAndExit(frame, browserView, null)
    }

    fun isFullscreenActive(): Boolean = fullscreenFrame != null && isInFullscreenMode
}
