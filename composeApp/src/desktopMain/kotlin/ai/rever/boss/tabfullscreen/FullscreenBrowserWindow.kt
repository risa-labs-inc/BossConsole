package ai.rever.boss.tabfullscreen

import ai.rever.boss.utils.MacOSFullscreenTracker
import ai.rever.boss.utils.WindowFocusManager
import ai.rever.boss.utils.hasFullscreenSignal
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.ComponentLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.BossWindowIcon
import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.view.swing.BrowserView
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.ActionEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.lang.ref.WeakReference
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer

internal fun fillsScreen(
    windowBounds: Rectangle,
    screenBounds: Rectangle,
): Boolean =
    windowBounds.x <= screenBounds.x &&
        windowBounds.y <= screenBounds.y &&
        windowBounds.maxX >= screenBounds.maxX &&
        windowBounds.maxY >= screenBounds.maxY

internal fun shouldUseComposeFullscreenOverlay(
    composeSignalActive: Boolean,
    isShowing: Boolean,
    isMaximized: Boolean,
    windowBounds: Rectangle,
    screenBounds: Rectangle,
): Boolean =
    composeSignalActive &&
        isShowing &&
        !isMaximized &&
        fillsScreen(windowBounds, screenBounds)

internal fun isCurrentFullscreenLifecycle(
    expectedEpoch: Long,
    currentEpoch: Long,
    isInFullscreenMode: Boolean,
): Boolean = expectedEpoch == currentEpoch && isInFullscreenMode

internal fun shouldRestoreFullscreenTabState(
    cleanupEpoch: Long,
    currentEpoch: Long,
    isInFullscreenMode: Boolean,
): Boolean = cleanupEpoch == currentEpoch && !isInFullscreenMode

private fun displayBounds(frame: JFrame): Rectangle =
    frame.graphicsConfiguration?.bounds
        ?: GraphicsEnvironment
            .getLocalGraphicsEnvironment()
            .defaultScreenDevice.defaultConfiguration.bounds

private fun isWindowInFullscreen(frame: JFrame): Boolean = fillsScreen(frame.bounds, displayBounds(frame))

private fun isRegisteredWindowFullscreen(ownerWindowId: String): Boolean {
    val state = WindowFocusManager.getWindowFullscreenState(ownerWindowId) ?: return false
    return hasFullscreenSignal(
        nativeStateAvailable = state.nativeStateAvailable,
        nativeFullscreen = state.nativeFullscreen,
        composeFullscreen = state.composeFullscreen,
    )
}

internal enum class FullscreenRequestDecision {
    IGNORE_DUPLICATE,
    REJECT_CLOSED,
    REJECT_COMPETING,
    BEGIN,
    ;

    companion object {
        fun shouldResumeAfterDuplicateRequest(
            decision: FullscreenRequestDecision,
            isExiting: Boolean,
        ): Boolean = decision == IGNORE_DUPLICATE && isExiting
    }
}

internal fun fullscreenRequestDecision(
    frameActive: Boolean,
    isInFullscreenMode: Boolean,
    isSameBrowser: Boolean,
    isBrowserClosed: Boolean,
): FullscreenRequestDecision {
    val fullscreenActive = frameActive || isInFullscreenMode
    return when {
        fullscreenActive && isSameBrowser -> FullscreenRequestDecision.IGNORE_DUPLICATE
        isBrowserClosed -> FullscreenRequestDecision.REJECT_CLOSED
        fullscreenActive -> FullscreenRequestDecision.REJECT_COMPETING
        else -> FullscreenRequestDecision.BEGIN
    }
}

/** EDT-confined once installed in [FullscreenBrowserWindow]. */
internal class FullscreenExitCallbackGate<T : Any> {
    private data class Entry<T : Any>(
        val owner: WeakReference<T>,
        var attempt: Long,
        var notified: Boolean,
    )

    private val entries = mutableListOf<Entry<T>>()
    private var nextAttempt = 0L

    fun begin(owner: T): Long {
        val attempt = ++nextAttempt
        val entry = findEntry(owner)
        if (entry == null) {
            entries += Entry(WeakReference(owner), attempt, notified = false)
        } else {
            entry.attempt = attempt
            entry.notified = false
        }
        return attempt
    }

    fun notifyOnce(
        owner: T,
        callback: () -> Unit,
    ): Boolean = notifyOnceInternal(owner, expectedAttempt = null, callback)

    fun notifyOnce(
        owner: T,
        expectedAttempt: Long,
        callback: () -> Unit,
    ): Boolean = notifyOnceInternal(owner, expectedAttempt, callback)

    private fun notifyOnceInternal(
        owner: T,
        expectedAttempt: Long?,
        callback: () -> Unit,
    ): Boolean {
        val entry =
            findEntry(owner)
                ?: run {
                    val attempt = expectedAttempt ?: ++nextAttempt
                    nextAttempt = maxOf(nextAttempt, attempt)
                    Entry(WeakReference(owner), attempt, notified = false)
                        .also(entries::add)
                }
        val shouldNotify =
            (expectedAttempt == null || entry.attempt == expectedAttempt) &&
                !entry.notified
        if (shouldNotify) {
            entry.notified = true
            callback()
        }
        return shouldNotify
    }

    private fun findEntry(owner: T): Entry<T>? {
        entries.removeAll { it.owner.get() == null }
        return entries.firstOrNull { it.owner.get() === owner }
    }
}

private fun runOnEventDispatchThreadAndWait(
    logger: ComponentLogger,
    timeoutMs: Long,
    action: () -> Unit,
) {
    if (SwingUtilities.isEventDispatchThread()) {
        action()
        return
    }

    val task =
        FutureTask<Unit> {
            action()
        }
    SwingUtilities.invokeLater(task)
    try {
        task.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        logger.warn(LogCategory.BROWSER, "Interrupted while closing fullscreen browser window", error = e)
    } catch (e: TimeoutException) {
        // Keep the task queued: the caller may stop waiting, but the fullscreen
        // frame still must be disposed once the EDT becomes responsive.
        logger.warn(
            LogCategory.BROWSER,
            "Timed out waiting for fullscreen browser cleanup on the EDT; cleanup remains queued",
            error = e,
        )
    } catch (e: ExecutionException) {
        logger.warn(LogCategory.BROWSER, "Could not close fullscreen browser window", error = e.cause ?: e)
    }
}

internal enum class VideoFullscreenConfirmationDecision {
    CONFIRMED,
    EXITED_EARLY,
    USE_OVERLAY,
    ;

    companion object {
        fun confirmationDelayMs(trackingAvailable: Boolean): Int =
            if (trackingAvailable) {
                NATIVE_FULLSCREEN_ACCEPTANCE_TIMEOUT_MS
            } else {
                FULLSCREEN_ANIMATION_DELAY_MS
            }

        fun decide(
            trackingAvailable: Boolean,
            stateAvailable: Boolean,
            isFullscreen: Boolean,
            entryObserved: Boolean,
            geometryFullscreen: Boolean,
        ): VideoFullscreenConfirmationDecision {
            val fullscreenConfirmed =
                if (trackingAvailable) {
                    stateAvailable && isFullscreen
                } else {
                    geometryFullscreen
                }
            return when {
                fullscreenConfirmed -> CONFIRMED
                entryObserved -> EXITED_EARLY
                else -> USE_OVERLAY
            }
        }

        private const val FULLSCREEN_ANIMATION_DELAY_MS = 600
        private const val NATIVE_FULLSCREEN_ACCEPTANCE_TIMEOUT_MS = 1_500
    }
}

private class VideoFullscreenTracker(
    private val onEntryObserved: () -> Unit,
    private val onExitObserved: () -> Unit,
) {
    private val nativeTracker =
        MacOSFullscreenTracker(
            onFullscreenChanged = ::handleFullscreenChanged,
            onFullscreenExitStarted = ::handleFullscreenExitStarted,
        )
    private var trackingId: String? = null

    var entryObserved = false
        private set
    var trackingAvailable = false
        private set
    var stateAvailable = false
        private set
    var isFullscreen = false
        private set

    fun register(
        frame: JFrame,
        expectedEpoch: Long,
    ) {
        clear()
        val newTrackingId = "fullscreen-video-$expectedEpoch"
        trackingId = newTrackingId
        trackingAvailable = nativeTracker.register(newTrackingId, frame)
    }

    fun clear() {
        val previousTrackingId = trackingId
        trackingId = null
        trackingAvailable = false
        stateAvailable = false
        isFullscreen = false
        entryObserved = false
        previousTrackingId?.let(nativeTracker::unregister)
    }

    private fun handleFullscreenChanged(
        eventTrackingId: String,
        fullscreen: Boolean,
    ) {
        if (eventTrackingId != trackingId) return
        stateAvailable = true
        isFullscreen = fullscreen
        if (fullscreen) {
            entryObserved = true
            onEntryObserved()
        } else if (entryObserved) {
            onExitObserved()
        }
    }

    private fun handleFullscreenExitStarted(eventTrackingId: String) {
        if (eventTrackingId != trackingId || !entryObserved) return
        onExitObserved()
    }
}

private class VideoFullscreenConfirmationHandler(
    private val onConfirmed: () -> Unit,
    private val onExitedEarly: () -> Unit,
) {
    private val logger = BossLogger.forComponent("VideoFullscreenConfirmationHandler")

    fun handle(
        tracker: VideoFullscreenTracker,
        geometryFullscreen: () -> Boolean,
        useOverlay: () -> Unit,
    ): VideoFullscreenConfirmationDecision {
        val trackingAvailable = tracker.trackingAvailable
        val decision =
            VideoFullscreenConfirmationDecision.decide(
                trackingAvailable = trackingAvailable,
                stateAvailable = tracker.stateAvailable,
                isFullscreen = tracker.isFullscreen,
                entryObserved = tracker.entryObserved,
                geometryFullscreen = !trackingAvailable && geometryFullscreen(),
            )
        when (decision) {
            VideoFullscreenConfirmationDecision.CONFIRMED -> {
                onConfirmed()
                logger.info(
                    LogCategory.BROWSER,
                    "Fullscreen animation completed, exit detection enabled",
                    mapOf("nativeSignal" to trackingAvailable),
                )
            }

            VideoFullscreenConfirmationDecision.EXITED_EARLY -> {
                logger.info(LogCategory.BROWSER, "Native fullscreen exited before confirmation completed")
                onExitedEarly()
            }

            VideoFullscreenConfirmationDecision.USE_OVERLAY -> {
                logger.warn(LogCategory.BROWSER, "macOS ignored native fullscreen toggle; using borderless overlay")
                useOverlay()
            }
        }
        return decision
    }
}

private data class FullscreenFrameView(
    val frame: JFrame,
    val browserView: BrowserView,
)

private fun prepareBorderlessOverlayFrame(
    sourceFrame: JFrame,
    sourceView: BrowserView?,
    browser: Browser,
    installExitShortcut: (JFrame) -> Unit,
    installFrameListeners: (JFrame) -> Unit,
): FullscreenFrameView {
    if (!sourceFrame.isDisplayable) {
        sourceFrame.isUndecorated = true
        // createFullscreenWindow assigns the view before selecting this path.
        return FullscreenFrameView(sourceFrame, checkNotNull(sourceView))
    }

    val graphicsConfiguration = sourceFrame.graphicsConfiguration
    // BrowserView has no close/dispose API. Hide and detach it before disposing
    // the peer; the Browser remains alive and is mounted into a fresh view below.
    sourceView?.let { view ->
        view.isVisible = false
        sourceFrame.contentPane.remove(view)
    }
    sourceFrame.contentPane.revalidate()
    sourceFrame.contentPane.repaint()
    sourceFrame.isVisible = false
    sourceFrame.dispose()

    val overlayFrame = graphicsConfiguration?.let(::JFrame) ?: JFrame()
    overlayFrame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
    overlayFrame.iconImages = BossWindowIcon.images
    overlayFrame.background = Color.BLACK
    overlayFrame.contentPane.background = Color.BLACK
    overlayFrame.contentPane.layout = BorderLayout()
    overlayFrame.isUndecorated = true
    installExitShortcut(overlayFrame)

    val overlayView = BrowserView.newInstance(browser)
    overlayView.background = Color.BLACK
    overlayFrame.contentPane.add(overlayView, BorderLayout.CENTER)
    installFrameListeners(overlayFrame)
    return FullscreenFrameView(overlayFrame, overlayView)
}

private class FullscreenOverlayCoordinator(
    private val isCurrentFrameOverlay: (JFrame) -> Boolean,
    private val isCurrentOwnerOverlay: (String, Long) -> Boolean,
    private val requestPageExit: () -> Unit,
) {
    private val logger = BossLogger.forComponent("FullscreenOverlayCoordinator")
    private var ownerFullscreenExitListener: ((String) -> Unit)? = null
    private var ownerWindow: Window? = null
    private var ownerFocusListener: WindowAdapter? = null

    fun installFocusBehavior(
        frame: JFrame,
        ownerWindowId: String?,
    ) {
        clearOwnerFocusListener()
        frame.addWindowFocusListener(
            object : WindowAdapter() {
                override fun windowLostFocus(event: WindowEvent?) {
                    if (event?.oppositeWindow === ownerWindow) return
                    if (isCurrentFrameOverlay(frame)) {
                        // Do not cover other applications when the user switches away from Boss.
                        frame.isAlwaysOnTop = false
                    }
                }

                override fun windowGainedFocus(event: WindowEvent?) {
                    restoreOverlayLevel(frame)
                }
            },
        )

        val hostWindow = ownerWindowId?.let(WindowFocusManager::getWindow) ?: return
        val listener =
            object : WindowAdapter() {
                override fun windowLostFocus(event: WindowEvent?) {
                    if (event?.oppositeWindow === frame) return
                    if (isCurrentFrameOverlay(frame)) {
                        // If the overlay never acquired focus, its own listener cannot
                        // observe app deactivation. The owner still can, so unpin here too.
                        frame.isAlwaysOnTop = false
                    }
                }

                override fun windowGainedFocus(event: WindowEvent?) {
                    restoreOverlayLevel(frame)
                }
            }
        ownerWindow = hostWindow
        ownerFocusListener = listener
        hostWindow.addWindowFocusListener(listener)
    }

    fun watchOwnerExit(
        ownerWindowId: String?,
        expectedEpoch: Long,
    ) {
        if (ownerWindowId == null) return
        ownerFullscreenExitListener?.let(WindowFocusManager.fullscreenExitNotifier::remove)
        val listener: (String) -> Unit = { exitedWindowId ->
            if (exitedWindowId == ownerWindowId) {
                SwingUtilities.invokeLater {
                    if (isCurrentOwnerOverlay(ownerWindowId, expectedEpoch)) {
                        logger.info(
                            LogCategory.BROWSER,
                            "Closing fullscreen video overlay because its host exited fullscreen",
                            mapOf("ownerWindowId" to ownerWindowId),
                        )
                        requestPageExit()
                    }
                }
            }
        }
        ownerFullscreenExitListener = listener
        WindowFocusManager.fullscreenExitNotifier.add(listener)

        // The owner may have completed its exit between selecting overlay mode
        // and registering this listener, so re-check after registration.
        if (!isRegisteredWindowFullscreen(ownerWindowId)) {
            SwingUtilities.invokeLater {
                if (isCurrentOwnerOverlay(ownerWindowId, expectedEpoch)) {
                    requestPageExit()
                }
            }
        }
    }

    fun clear() {
        ownerFullscreenExitListener?.let(WindowFocusManager.fullscreenExitNotifier::remove)
        ownerFullscreenExitListener = null
        clearOwnerFocusListener()
    }

    private fun restoreOverlayLevel(frame: JFrame) {
        if (isCurrentFrameOverlay(frame)) {
            frame.isAlwaysOnTop = true
            frame.toFront()
        }
    }

    private fun clearOwnerFocusListener() {
        val listener = ownerFocusListener
        if (ownerWindow != null && listener != null) {
            ownerWindow?.removeWindowFocusListener(listener)
        }
        ownerWindow = null
        ownerFocusListener = null
    }
}

/**
 * Creates and manages a fullscreen Swing JFrame for browser content.
 * Uses the existing browser instance with a new BrowserView.
 * Uses native macOS fullscreen mode (creates new Space) for proper fullscreen experience.
 * Exactly one browser fullscreen session is supported process-wide; window and browser IDs
 * scope ownership and rejection decisions for that single session.
 */
@Suppress("LargeClass")
object FullscreenBrowserWindow {
    private val logger = BossLogger.forComponent("FullscreenBrowserWindow")
    private val fullscreenFocusCoordinator = FullscreenFocusCoordinator()

    // Fullscreen lifecycle state is EDT-confined. Public entry/exit methods
    // marshal to the EDT before reading or mutating these fields.
    private var fullscreenFrame: JFrame? = null
    private var currentBrowserView: BrowserView? = null

    @Volatile // Read off-EDT only for the disposal fast path; mutated on the EDT.
    private var currentBrowser: Browser? = null
    private var currentOwnerWindowId: String? = null
    private var onExitCallback: (() -> Unit)? = null
    private var isInFullscreenMode = false
    private var hasReachedFullscreen = false // True only after fullscreen animation completes
    private var usesNativeMacOSFullscreen = false
    private var isExiting = false // Prevent multiple exit calls
    private var pageExitFallbackTimer: Timer? = null
    private var lifecycleEpoch = 0L
    private val exitCallbackGate = FullscreenExitCallbackGate<Browser>()
    private val videoFullscreenTracker =
        VideoFullscreenTracker(
            onEntryObserved = {
                logger.info(LogCategory.BROWSER, "Native video fullscreen entry observed")
            },
            onExitObserved = {
                // VideoFullscreenTracker only publishes this callback after entryObserved.
                if (usesNativeMacOSFullscreen && !isExiting) {
                    logger.info(LogCategory.BROWSER, "Native video fullscreen exit observed")
                    requestPageExit()
                }
            },
        )
    private val videoFullscreenConfirmationHandler =
        VideoFullscreenConfirmationHandler(
            onConfirmed = { hasReachedFullscreen = true },
            onExitedEarly = ::requestPageExit,
        )
    private val overlayCoordinator =
        FullscreenOverlayCoordinator(
            isCurrentFrameOverlay = { frame ->
                fullscreenFrame === frame && !usesNativeMacOSFullscreen
            },
            isCurrentOwnerOverlay = { ownerWindowId, expectedEpoch ->
                if (currentOwnerWindowId != ownerWindowId) {
                    false
                } else {
                    lifecycleEpoch == expectedEpoch && isInFullscreenMode && !usesNativeMacOSFullscreen
                }
            },
            requestPageExit = ::requestPageExit,
        )

    private val isMacOS = System.getProperty("os.name").lowercase().contains("mac")

    private const val COMPETING_REQUEST_EXIT_TIMEOUT_MS = 600
    private const val PAGE_EXIT_EVENT_TIMEOUT_MS = 1_000

    // Delay to allow Compose BrowserView to detach before creating Swing BrowserView
    // This prevents both views from competing for rendering (which causes video freeze)
    private const val COMPOSE_DETACH_DELAY_MS = 100

    // Delay to allow Swing BrowserView to release rendering before Compose BrowserView activates
    // Exit needs more time because we need to ensure the Swing view fully releases the surface
    private const val SWING_RELEASE_DELAY_MS = 200
    private const val EDT_CLEANUP_TIMEOUT_MS = 2_000L
    private const val EXIT_FULLSCREEN_ACTION = "exit-fullscreen"

    fun showFullscreen(
        browser: Browser,
        tabId: String,
        ownerWindowId: String,
        onEnter: () -> Unit,
        onExit: () -> Unit,
    ) {
        if (SwingUtilities.isEventDispatchThread()) {
            handleFullscreenRequest(browser, tabId, ownerWindowId, onEnter, onExit)
        } else {
            SwingUtilities.invokeLater {
                handleFullscreenRequest(browser, tabId, ownerWindowId, onEnter, onExit)
            }
        }
    }

    private fun handleFullscreenRequest(
        browser: Browser,
        tabId: String,
        ownerWindowId: String,
        onEnter: () -> Unit,
        onExit: () -> Unit,
    ) {
        val decision =
            fullscreenRequestDecision(
                frameActive = fullscreenFrame != null,
                isInFullscreenMode = isInFullscreenMode,
                isSameBrowser = currentBrowser === browser,
                isBrowserClosed = browser.isClosed,
            )
        val exitAttempt =
            if (decision != FullscreenRequestDecision.IGNORE_DUPLICATE) {
                // Deduplicate within one request/exit attempt, not forever for a
                // browser that may lose several competing requests over its life.
                exitCallbackGate.begin(browser)
            } else {
                null
            }
        when (decision) {
            FullscreenRequestDecision.IGNORE_DUPLICATE -> {
                if (FullscreenRequestDecision.shouldResumeAfterDuplicateRequest(decision, isExiting)) {
                    pageExitFallbackTimer?.stop()
                    pageExitFallbackTimer = null
                    isExiting = false
                    logger.info(
                        LogCategory.BROWSER,
                        "Keeping fullscreen active after browser re-entered during pending exit",
                    )
                } else {
                    logger.debug(LogCategory.BROWSER, "Ignoring duplicate fullscreen request from active browser")
                }
            }

            FullscreenRequestDecision.REJECT_CLOSED -> {
                logger.warn(LogCategory.BROWSER, "Ignoring fullscreen request from closed browser")
                exitCallbackGate.notifyOnce(browser, checkNotNull(exitAttempt), onExit)
            }

            FullscreenRequestDecision.REJECT_COMPETING -> {
                rejectFullscreenRequest(browser, checkNotNull(exitAttempt), onExit)
            }

            FullscreenRequestDecision.BEGIN -> {
                beginFullscreenSession(browser, tabId, ownerWindowId, onEnter, onExit)
            }
        }
    }

    /** Rejects a losing browser without publishing a false enter state. */
    private fun rejectFullscreenRequest(
        browser: Browser,
        exitAttempt: Long,
        onExit: () -> Unit,
    ) {
        logger.warn(LogCategory.BROWSER, "Rejecting fullscreen request while another browser is active")
        val exitRequested =
            runCatching { browser.fullScreen().exit() }
                .onFailure { error ->
                    logger.warn(LogCategory.BROWSER, "Could not reject competing browser fullscreen", error = error)
                }.isSuccess
        // A successful request emits FullScreenExited, whose normal observer
        // publishes the callback. Bound the wait in case that event is lost.
        if (!exitRequested) {
            exitCallbackGate.notifyOnce(browser, exitAttempt, onExit)
        } else {
            Timer(COMPETING_REQUEST_EXIT_TIMEOUT_MS) {
                exitCallbackGate.notifyOnce(browser, exitAttempt, onExit)
            }.apply {
                isRepeats = false
                start()
            }
        }
    }

    private fun beginFullscreenSession(
        browser: Browser,
        tabId: String,
        ownerWindowId: String,
        onEnter: () -> Unit,
        onExit: () -> Unit,
    ) {
        // Mark fullscreen state FIRST so Compose BrowserView hides immediately
        // This triggers recomposition in JxBrowserCompose.kt, replacing BrowserView with placeholder
        isInFullscreenMode = true
        currentBrowser = browser
        currentOwnerWindowId = ownerWindowId
        onExitCallback = onExit
        isExiting = false
        hasReachedFullscreen = false
        lifecycleEpoch++
        val expectedEpoch = lifecycleEpoch
        TabFullscreenStateManager.enterFullscreen(tabId)
        onEnter()

        logger.info(LogCategory.BROWSER, "Fullscreen state set, waiting for Compose detach", mapOf("tabId" to tabId))

        // Delay window creation to allow Compose BrowserView to detach from rendering
        // This gives JxBrowser time to release the Compose rendering surface
        SwingUtilities.invokeLater {
            Timer(COMPOSE_DETACH_DELAY_MS) {
                SwingUtilities.invokeLater {
                    if (isCurrentSession(expectedEpoch, browser)) {
                        createFullscreenWindow(browser, tabId, expectedEpoch)
                    }
                }
            }.apply {
                isRepeats = false
                start()
            }
        }
    }

    private fun isCurrentSession(
        expectedEpoch: Long,
        browser: Browser,
    ): Boolean =
        isCurrentFullscreenLifecycle(expectedEpoch, lifecycleEpoch, isInFullscreenMode) &&
            currentBrowser === browser

    private fun isCurrentFrameSession(
        expectedEpoch: Long,
        browser: Browser,
        frame: JFrame,
    ): Boolean = isCurrentSession(expectedEpoch, browser) && fullscreenFrame === frame

    @Suppress("TooGenericExceptionCaught")
    private fun requestFullscreenBrowserFocus(
        frame: JFrame,
        browserView: BrowserView,
        browser: Browser,
        expectedEpoch: Long,
        reason: String,
    ) {
        fullscreenFocusCoordinator.requestFocus(
            isCurrent = {
                hasReachedFullscreen &&
                    !isExiting &&
                    isCurrentFrameSession(expectedEpoch, browser, frame) &&
                    currentBrowserView === browserView &&
                    frame.isShowing
            },
            attemptFocus = {
                try {
                    frame.toFront()
                    frame.requestFocus()
                    browserView.requestFocusInWindow()
                } catch (e: Exception) {
                    logger.warn(
                        LogCategory.BROWSER,
                        "Fullscreen browser focus attempt failed",
                        mapOf("reason" to reason),
                        error = e,
                    )
                    false
                }
            },
            onExhausted = {
                logger.warn(
                    LogCategory.BROWSER,
                    "Fullscreen browser focus retries exhausted",
                    mapOf("reason" to reason),
                )
            },
        )
    }

    private fun runFullscreenTransition(
        frame: JFrame,
        browser: Browser,
        expectedEpoch: Long,
        action: () -> Unit,
    ) {
        if (!isCurrentFrameSession(expectedEpoch, browser, frame)) return
        try {
            action()
        } catch (e: Exception) {
            logger.error(LogCategory.BROWSER, "Fullscreen transition failed", error = e)
            if (isCurrentSession(expectedEpoch, browser)) {
                performExitDirect()
            }
        }
    }

    /**
     * Creates and displays the fullscreen window with a Swing BrowserView.
     * Called after Compose BrowserView has had time to detach from rendering.
     */
    private fun createFullscreenWindow(
        browser: Browser,
        tabId: String,
        expectedEpoch: Long,
    ) {
        var createdFrame: JFrame? = null
        try {
            // Check if we've been cancelled during the delay
            if (!isCurrentSession(expectedEpoch, browser)) {
                logger.warn(LogCategory.BROWSER, "Fullscreen cancelled during delay")
                return
            }

            // Double-check for race conditions
            if (fullscreenFrame != null) {
                logger.warn(LogCategory.BROWSER, "Fullscreen already active (race condition prevented)")
                return
            }

            val ownerWindow = currentOwnerWindowId?.let(WindowFocusManager::getWindow)
            val frame = ownerWindow?.graphicsConfiguration?.let(::JFrame) ?: JFrame()
            createdFrame = frame
            frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
            frame.iconImages = BossWindowIcon.images
            frame.background = Color.BLACK
            frame.contentPane.background = Color.BLACK
            frame.contentPane.layout = BorderLayout()
            installExitShortcut(frame)

            // Create BrowserView for existing browser instance
            // At this point, the Compose BrowserView should be detached from rendering
            val browserView = BrowserView.newInstance(browser)
            browserView.background = Color.BLACK
            frame.contentPane.add(browserView, BorderLayout.CENTER)

            logger.info(LogCategory.BROWSER, "Swing BrowserView created after Compose detach delay")

            installFullscreenFrameListeners(frame, browser, expectedEpoch)

            fullscreenFrame = frame
            currentBrowserView = browserView

            if (isMacOS) {
                enterMacOSFullscreen(frame, browser, ownerWindow, expectedEpoch)
            } else {
                // Windows/Linux: use maximized undecorated window
                frame.isUndecorated = true
                val screenBounds = displayBounds(frame)
                frame.setBounds(screenBounds.x, screenBounds.y, screenBounds.width, screenBounds.height)
                frame.isVisible = true
                hasReachedFullscreen = true
                requestFullscreenBrowserFocus(
                    frame = frame,
                    browserView = browserView,
                    browser = browser,
                    expectedEpoch = expectedEpoch,
                    reason = "Windows/Linux fullscreen entry",
                )
            }

            logger.info(LogCategory.BROWSER, "Fullscreen window opened", mapOf("tabId" to tabId, "isMacOS" to isMacOS))
        } catch (e: Exception) {
            logger.error(LogCategory.BROWSER, "Failed to create fullscreen window", error = e)
            runCatching { createdFrame?.dispose() }
                .onFailure { error ->
                    logger.warn(LogCategory.BROWSER, "Could not dispose failed fullscreen window", error = error)
                }
            if (isCurrentSession(expectedEpoch, browser)) {
                val callback = onExitCallback
                resetState()
                TabFullscreenStateManager.exitFullscreen()
                callback?.let { exitCallbackGate.notifyOnce(browser, it) }
            }
        }
    }

    private fun installFullscreenFrameListeners(
        frame: JFrame,
        browser: Browser,
        expectedEpoch: Long,
    ) {
        frame.addWindowListener(
            object : WindowAdapter() {
                override fun windowClosing(e: WindowEvent?) {
                    requestPageExit()
                }
            },
        )

        frame.addWindowFocusListener(
            object : WindowAdapter() {
                override fun windowGainedFocus(event: WindowEvent?) {
                    currentBrowserView?.let { browserView ->
                        requestFullscreenBrowserFocus(
                            frame = frame,
                            browserView = browserView,
                            browser = browser,
                            expectedEpoch = expectedEpoch,
                            reason = "fullscreen window regained focus",
                        )
                    }
                }
            },
        )

        // Detect when exiting native fullscreen (green button or ESC).
        // Overlay and Windows/Linux frames are fixed-size and exit directly.
        frame.addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent?) {
                    if (!usesNativeMacOSFullscreen || !hasReachedFullscreen) return
                    if (!isCurrentFrameSession(expectedEpoch, browser, frame) || isExiting) return

                    SwingUtilities.invokeLater {
                        if (isCurrentFrameSession(expectedEpoch, browser, frame) &&
                            !isWindowInFullscreen(frame) &&
                            !isExiting
                        ) {
                            logger.info(LogCategory.BROWSER, "Native fullscreen exited via resize detection")
                            requestPageExit()
                        }
                    }
                }
            },
        )
    }

    private fun enterMacOSFullscreen(
        frame: JFrame,
        browser: Browser,
        ownerWindow: Window?,
        expectedEpoch: Long,
    ) {
        val fullscreenHostBounds = currentOwnerWindowId?.let(::existingFullscreenWindowBounds)
        if (fullscreenHostBounds != null) {
            // macOS can ignore a second native fullscreen-Space request while another
            // window from this app already owns a fullscreen Space. Keep the video in
            // that Space and cover it with an undecorated screen-sized window instead.
            showBorderlessOverlay(
                frame = frame,
                browser = browser,
                bounds = fullscreenHostBounds,
                watchOwnerExit = true,
                expectedEpoch = expectedEpoch,
            )
            logger.info(
                LogCategory.BROWSER,
                "Opened fullscreen video as borderless overlay in existing fullscreen Space",
            )
            return
        }

        usesNativeMacOSFullscreen = true
        frame.rootPane.putClientProperty("apple.awt.fullscreenable", true)
        frame.setSize(800, 600) // Initial size before fullscreen
        if (ownerWindow != null) {
            frame.setLocationRelativeTo(ownerWindow)
        } else {
            val bounds = displayBounds(frame)
            frame.setLocation(
                bounds.x + (bounds.width - frame.width) / 2,
                bounds.y + (bounds.height - frame.height) / 2,
            )
        }
        frame.isVisible = true
        videoFullscreenTracker.register(frame, expectedEpoch)
        requestNativeMacOSFullscreen(frame, browser, expectedEpoch)
    }

    private fun requestNativeMacOSFullscreen(
        frame: JFrame,
        browser: Browser,
        expectedEpoch: Long,
    ) {
        SwingUtilities.invokeLater {
            runFullscreenTransition(frame, browser, expectedEpoch) {
                if (!toggleMacOSFullscreen(frame)) {
                    showBorderlessOverlay(
                        frame = frame,
                        browser = browser,
                        bounds = displayBounds(frame),
                        watchOwnerExit =
                            currentOwnerWindowId
                                ?.let(::isRegisteredWindowFullscreen) == true,
                        expectedEpoch = expectedEpoch,
                    )
                    return@runFullscreenTransition
                }

                // Geometry needs one animation interval; a tracked native toggle gets
                // longer to publish its authoritative entry event before overlay fallback.
                val confirmationDelayMs =
                    VideoFullscreenConfirmationDecision.confirmationDelayMs(
                        videoFullscreenTracker.trackingAvailable,
                    )
                Timer(confirmationDelayMs) {
                    runFullscreenTransition(frame, browser, expectedEpoch) {
                        val decision =
                            videoFullscreenConfirmationHandler.handle(
                                tracker = videoFullscreenTracker,
                                geometryFullscreen = { isWindowInFullscreen(frame) },
                                useOverlay = {
                                    showBorderlessOverlay(
                                        frame = frame,
                                        browser = browser,
                                        bounds = displayBounds(frame),
                                        watchOwnerExit =
                                            currentOwnerWindowId
                                                ?.let(::isRegisteredWindowFullscreen) == true,
                                        expectedEpoch = expectedEpoch,
                                    )
                                },
                            )

                        if (decision == VideoFullscreenConfirmationDecision.CONFIRMED) {
                            currentBrowserView?.let { browserView ->
                                requestFullscreenBrowserFocus(
                                    frame = frame,
                                    browserView = browserView,
                                    browser = browser,
                                    expectedEpoch = expectedEpoch,
                                    reason = "macOS fullscreen transition completed",
                                )
                            }
                        }
                    }
                }.apply {
                    isRepeats = false
                    start()
                }
            }
        }
    }

    private fun showBorderlessOverlay(
        frame: JFrame,
        browser: Browser,
        bounds: Rectangle,
        watchOwnerExit: Boolean,
        expectedEpoch: Long,
    ) {
        if (!isCurrentFrameSession(expectedEpoch, browser, frame)) return
        videoFullscreenTracker.clear()

        val overlay =
            prepareBorderlessOverlayFrame(
                sourceFrame = frame,
                sourceView = currentBrowserView,
                browser = browser,
                installExitShortcut = ::installExitShortcut,
                installFrameListeners = { replacement ->
                    installFullscreenFrameListeners(replacement, browser, expectedEpoch)
                },
            )
        fullscreenFrame = overlay.frame
        currentBrowserView = overlay.browserView
        usesNativeMacOSFullscreen = false
        hasReachedFullscreen = true
        overlay.frame.isAlwaysOnTop = true
        overlay.frame.extendedState = JFrame.NORMAL
        overlay.frame.setBounds(bounds)
        overlay.frame.isVisible = true
        requestFullscreenBrowserFocus(
            frame = overlay.frame,
            browserView = overlay.browserView,
            browser = browser,
            expectedEpoch = expectedEpoch,
            reason = "borderless fullscreen overlay entry",
        )
        overlayCoordinator.installFocusBehavior(overlay.frame, currentOwnerWindowId)
        if (watchOwnerExit) {
            overlayCoordinator.watchOwnerExit(currentOwnerWindowId, expectedEpoch)
        }
    }

    /**
     * Returns the display bounds of an already-fullscreen window in this process.
     * A new undecorated video window can cover that same fullscreen Space without
     * asking macOS to create a second native fullscreen Space.
     */
    private fun existingFullscreenWindowBounds(ownerWindowId: String): Rectangle? {
        val state = WindowFocusManager.getWindowFullscreenState(ownerWindowId)
        if (state == null ||
            !hasFullscreenSignal(
                nativeStateAvailable = state.nativeStateAvailable,
                nativeFullscreen = state.nativeFullscreen,
                composeFullscreen = state.composeFullscreen,
            )
        ) {
            return null
        }
        return if (state.nativeStateAvailable) {
            state.window.graphicsConfiguration?.bounds
        } else {
            ownerWindowBoundsFallback(
                ownerWindowId = ownerWindowId,
                ownerWindow = state.window,
                composeSignalActive = state.composeFullscreen,
            )
        }
    }

    private fun ownerWindowBoundsFallback(
        ownerWindowId: String,
        ownerWindow: Window,
        composeSignalActive: Boolean,
    ): Rectangle? {
        // Best-effort fallback only for the browser's owning window. Never scan
        // another Boss window or treat an ordinary maximized frame as fullscreen.
        val screenBounds = ownerWindow.graphicsConfiguration?.bounds
        val isMaximized =
            ownerWindow is Frame &&
                ownerWindow.extendedState and Frame.MAXIMIZED_BOTH != 0
        val fallbackBounds =
            screenBounds?.takeIf {
                shouldUseComposeFullscreenOverlay(
                    composeSignalActive = composeSignalActive,
                    isShowing = ownerWindow.isShowing,
                    isMaximized = isMaximized,
                    windowBounds = ownerWindow.bounds,
                    screenBounds = screenBounds,
                )
            }

        if (fallbackBounds != null) {
            logger.info(
                LogCategory.BROWSER,
                "Using owner-window bounds fallback for fullscreen video overlay",
                mapOf("ownerWindowId" to ownerWindowId),
            )
        }
        return fallbackBounds
    }

    /**
     * Request native macOS fullscreen using reflection.
     * Uses com.apple.eawt.Application.requestToggleFullScreen() which creates
     * a proper macOS fullscreen Space (like Chrome/Safari behavior).
     */
    private fun toggleMacOSFullscreen(window: Window): Boolean =
        try {
            val appClass = Class.forName("com.apple.eawt.Application")
            val getAppMethod = appClass.getDeclaredMethod("getApplication")
            getAppMethod.isAccessible = true
            val app = getAppMethod.invoke(null)
            val requestToggleMethod = appClass.getDeclaredMethod("requestToggleFullScreen", Window::class.java)
            requestToggleMethod.isAccessible = true
            requestToggleMethod.invoke(app, window)
            logger.info(LogCategory.BROWSER, "Requested macOS native fullscreen")
            true
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Could not toggle macOS fullscreen", error = e)
            false
        }

    private fun installExitShortcut(frame: JFrame) {
        frame.rootPane
            .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), EXIT_FULLSCREEN_ACTION)
        frame.rootPane.actionMap.put(
            EXIT_FULLSCREEN_ACTION,
            object : AbstractAction() {
                override fun actionPerformed(event: ActionEvent?) {
                    logger.info(LogCategory.BROWSER, "Fullscreen exit requested from host UI")
                    requestPageExit()
                }
            },
        )
    }

    /**
     * Reset all state variables.
     */
    private fun resetState(): Long {
        fullscreenFocusCoordinator.cancel()
        lifecycleEpoch++
        overlayCoordinator.clear()
        videoFullscreenTracker.clear()
        fullscreenFrame = null
        currentBrowserView = null
        currentBrowser = null
        currentOwnerWindowId = null
        onExitCallback = null
        isInFullscreenMode = false
        hasReachedFullscreen = false
        usesNativeMacOSFullscreen = false
        isExiting = false
        pageExitFallbackTimer?.stop()
        pageExitFallbackTimer = null
        return lifecycleEpoch
    }

    /**
     * Shared cleanup logic for exiting fullscreen mode.
     * Hides and detaches the Swing BrowserView, disposes the frame,
     * and signals TabFullscreenStateManager after a delay.
     *
     * @param frame The JFrame to dispose
     * @param browserView The BrowserView to detach (nullable)
     * @param callback Optional callback to invoke after cleanup completes
     * @param exitingBrowser Browser that owned the cleanup session
     * @param cleanupEpoch Reset epoch that must still be current before Compose is restored
     */
    private fun cleanupAndExit(
        frame: JFrame,
        browserView: BrowserView?,
        callback: (() -> Unit)?,
        exitingBrowser: Browser?,
        cleanupEpoch: Long,
    ) {
        val cleanup =
            Runnable {
                try {
                    // Hide and detach the Swing BrowserView to release rendering surface
                    browserView?.let { view ->
                        view.isVisible = false
                        view.repaint()
                        frame.contentPane.remove(view)
                    }
                    frame.contentPane.revalidate()
                    frame.contentPane.repaint()
                } catch (e: Exception) {
                    logger.error(LogCategory.BROWSER, "Error detaching fullscreen browser view", error = e)
                } finally {
                    try {
                        frame.isAlwaysOnTop = false
                        frame.dispose()
                    } catch (e: Exception) {
                        logger.error(LogCategory.BROWSER, "Error disposing fullscreen window", error = e)
                    }
                }

                logger.info(LogCategory.BROWSER, "Fullscreen window disposed, waiting for rendering release")

                // Delay before telling Compose to show its BrowserView
                // This gives JxBrowser time to fully release the Swing rendering surface
                Timer(SWING_RELEASE_DELAY_MS) {
                    if (shouldRestoreFullscreenTabState(cleanupEpoch, lifecycleEpoch, isInFullscreenMode)) {
                        TabFullscreenStateManager.exitFullscreen()
                        logger.info(LogCategory.BROWSER, "Fullscreen exit complete, Compose BrowserView enabled")
                    }
                    // The old tab still needs its local exit notification when
                    // another browser starts a newer global session. A re-entry
                    // by the same browser supersedes this callback instead.
                    if (currentBrowser !== exitingBrowser && exitingBrowser != null) {
                        callback?.let { exitCallbackGate.notifyOnce(exitingBrowser, it) }
                    }
                }.apply {
                    isRepeats = false
                    start()
                }
            }

        if (SwingUtilities.isEventDispatchThread()) {
            cleanup.run()
        } else {
            SwingUtilities.invokeLater(cleanup)
        }
    }

    /**
     * Keep the browser page and host window in sync: ask Chromium to leave
     * HTML fullscreen first, then tear down directly only if its exit event is
     * lost. JxBrowser's FullScreen.exit() covers all frames in the browser.
     */
    private fun requestPageExit() {
        if (isExiting || !isInFullscreenMode) return
        isExiting = true
        val browser = currentBrowser
        val exitEpoch = lifecycleEpoch
        if (browser == null ||
            runCatching { browser.fullScreen().exit() }
                .onFailure { error ->
                    logger.warn(LogCategory.BROWSER, "Could not request browser fullscreen exit", error = error)
                }.isFailure
        ) {
            performExitDirect()
            return
        }

        pageExitFallbackTimer =
            Timer(PAGE_EXIT_EVENT_TIMEOUT_MS) {
                if (lifecycleEpoch == exitEpoch &&
                    currentBrowser === browser
                ) {
                    if (isInFullscreenMode && isExiting) {
                        logger.warn(LogCategory.BROWSER, "Browser fullscreen exit event timed out; closing host window")
                        performExitDirect()
                    }
                }
            }.apply {
                isRepeats = false
                start()
            }
    }

    /** Requests exit only if fullscreen belongs to this browser, without blocking its callback thread. */
    fun requestExit(browser: Browser) {
        SwingUtilities.invokeLater {
            if (currentBrowser === browser) {
                logger.info(LogCategory.BROWSER, "Fullscreen exit requested by owning browser")
                requestPageExit()
            }
        }
    }

    /**
     * Direct exit without relying on fullscreen toggle.
     */
    private fun performExitDirect() {
        val frame = fullscreenFrame
        val browserView = currentBrowserView
        val browser = currentBrowser
        val callback = onExitCallback

        val cleanupEpoch = resetState()
        if (frame == null) {
            TabFullscreenStateManager.exitFullscreen()
            if (browser != null) {
                callback?.let { exitCallbackGate.notifyOnce(browser, it) }
            }
        } else {
            cleanupAndExit(frame, browserView, callback, browser, cleanupEpoch)
        }
    }

    /**
     * Closes the fullscreen window.
     * Safe to call multiple times - will only close once.
     */
    private fun exitFullscreen() {
        val frame = fullscreenFrame
        if (frame == null) {
            if (isInFullscreenMode) {
                resetState()
                TabFullscreenStateManager.exitFullscreen()
            }
            return
        }
        val browserView = currentBrowserView
        val browser = currentBrowser

        val cleanupEpoch = resetState()
        cleanupAndExit(frame, browserView, null, browser, cleanupEpoch)
    }

    /** Closes the matching session and publishes each browser's exit callback at most once. */
    fun exitFullscreenAsync(
        browser: Browser,
        onExit: () -> Unit,
    ) {
        SwingUtilities.invokeLater {
            if (currentBrowser === browser) {
                exitFullscreen()
            }
            exitCallbackGate.notifyOnce(browser, onExit)
        }
    }

    /**
     * Closes fullscreen only when it belongs to the browser being disposed.
     * Blocks a background disposer until the Swing view has been detached, so
     * the browser cannot close underneath the fullscreen window's cleanup.
     * Callers must not hold a lock that the EDT could need; current disposal
     * paths either already run on the EDT or hold only owner-local lifecycle state.
     */
    fun exitFullscreen(browser: Browser) {
        if (currentBrowser !== browser) return
        runOnEventDispatchThreadAndWait(logger, EDT_CLEANUP_TIMEOUT_MS) {
            if (currentBrowser === browser) {
                exitFullscreen()
            }
        }
    }
}
