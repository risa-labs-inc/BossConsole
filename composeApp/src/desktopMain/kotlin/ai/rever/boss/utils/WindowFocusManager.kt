package ai.rever.boss.utils

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.Frame
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.lang.reflect.InaccessibleObjectException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import javax.swing.SwingUtilities

internal fun nativeMacOSFullscreenStateForEvent(methodName: String): Boolean? =
    when (methodName) {
        // Treat "entering" as fullscreen immediately so a page request during
        // the Space animation does not issue a competing native toggle.
        "windowEnteringFullScreen", "windowEnteredFullScreen" -> true

        // Keep the state true through the exit animation. The separate
        // exit-start signal closes overlays immediately without allowing a
        // competing native toggle before the Space has actually gone away.
        "windowExitedFullScreen" -> false

        else -> null
    }

internal fun isNativeMacOSFullscreenExitStarting(methodName: String): Boolean = methodName == "windowExitingFullScreen"

/**
 * Resolution policy behind [WindowFocusManager.resolveActionableWindowId], kept
 * pure so the ordering can be asserted without live AWT windows.
 *
 * [lastFocusedWindowId] is set at registration and on every focus gain;
 * [focusFlowWindowId] is only ever set inside the focus-gained listener, so it
 * can lag or stay null when the caller (an MCP client or the CLI) holds OS focus
 * itself. A registered window is still a usable target in that case, which is
 * why [registeredWindowIds] is the last resort rather than "no window".
 */
internal fun resolveActionableWindowIdFrom(
    lastFocusedWindowId: String?,
    focusFlowWindowId: String?,
    registeredWindowIds: Collection<String>,
): String? = lastFocusedWindowId ?: focusFlowWindowId ?: registeredWindowIds.firstOrNull()

internal fun hasFullscreenSignal(
    nativeStateAvailable: Boolean,
    nativeFullscreen: Boolean,
    composeFullscreen: Boolean,
): Boolean =
    if (nativeStateAvailable) {
        nativeFullscreen
    } else {
        composeFullscreen
    }

internal fun shouldNotifyComposeFullscreenExit(
    wasComposeFullscreen: Boolean,
    isNativeFullscreen: Boolean,
): Boolean = wasComposeFullscreen && !isNativeFullscreen

internal fun shouldNotifyNativeFullscreenExit(
    hadNativeState: Boolean,
    wasNativeFullscreen: Boolean,
    composeFullscreen: Boolean,
    exitAlreadyNotified: Boolean = false,
): Boolean = !exitAlreadyNotified && (wasNativeFullscreen || (!hadNativeState && composeFullscreen))

internal class FullscreenExitNotifier {
    private val logger = BossLogger.forComponent("FullscreenExitNotifier")
    private val listeners = CopyOnWriteArraySet<(String) -> Unit>()

    fun add(listener: (String) -> Unit) {
        listeners += listener
    }

    fun remove(listener: (String) -> Unit) {
        listeners -= listener
    }

    fun notifyExit(windowId: String) {
        listeners.forEach { listener ->
            runCatching { listener(windowId) }
                .onFailure { error ->
                    logger.warn(
                        LogCategory.UI,
                        "Fullscreen exit listener failed",
                        mapOf("windowId" to windowId),
                        error,
                    )
                }
        }
    }
}

internal class MacOSFullscreenTracker(
    private val onFullscreenChanged: (windowId: String, isFullscreen: Boolean) -> Unit,
    private val onFullscreenExitStarted: (windowId: String) -> Unit,
) {
    private data class Registration(
        val window: Window,
        val listener: Any,
        val listenerClass: Class<*>,
        val removeMethod: Method,
    )

    private val logger = BossLogger.forComponent("MacOSFullscreenTracker")
    private val isMacOS = System.getProperty("os.name").lowercase().contains("mac")

    // EDT-confined through WindowFocusManager.registerWindow/unregisterWindow.
    private val registrations = mutableMapOf<String, Registration>()

    fun register(
        windowId: String,
        window: Window,
    ): Boolean {
        if (!isMacOS) return false

        unregister(windowId)
        return runReflection("register", windowId) {
            val utilitiesClass = Class.forName("com.apple.eawt.FullScreenUtilities")
            val listenerClass = Class.forName("com.apple.eawt.FullScreenListener")
            val listener =
                Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { proxy, method, arguments ->
                    when (method.name) {
                        "equals" -> {
                            proxy === arguments?.firstOrNull()
                        }

                        "hashCode" -> {
                            System.identityHashCode(proxy)
                        }

                        "toString" -> {
                            "BossMacOSFullscreenListener($windowId)"
                        }

                        else -> {
                            runCatching {
                                if (isNativeMacOSFullscreenExitStarting(method.name)) {
                                    onFullscreenExitStarted(windowId)
                                }
                                nativeMacOSFullscreenStateForEvent(method.name)?.let { isFullscreen ->
                                    onFullscreenChanged(windowId, isFullscreen)
                                    logger.info(
                                        LogCategory.UI,
                                        "Native macOS fullscreen state changed",
                                        mapOf("windowId" to windowId, "isFullscreen" to isFullscreen),
                                    )
                                }
                            }.onFailure { error ->
                                logFailure("handle ${method.name}", windowId, error)
                            }
                            null
                        }
                    }
                }
            val addMethod =
                utilitiesClass.getMethod(
                    "addFullScreenListenerTo",
                    Window::class.java,
                    listenerClass,
                )
            val removeMethod =
                utilitiesClass.getMethod(
                    "removeFullScreenListenerFrom",
                    Window::class.java,
                    listenerClass,
                )

            addMethod.invoke(null, window, listener)
            registrations[windowId] = Registration(window, listener, listenerClass, removeMethod)
        }
    }

    fun unregister(windowId: String) {
        val registration = registrations.remove(windowId) ?: return
        runReflection("unregister", windowId) {
            registration.removeMethod.invoke(
                null,
                registration.window,
                registration.listenerClass.cast(registration.listener),
            )
        }
    }

    private fun runReflection(
        operation: String,
        windowId: String,
        block: () -> Unit,
    ): Boolean =
        try {
            block()
            true
        } catch (e: ReflectiveOperationException) {
            logFailure(operation, windowId, e)
            false
        } catch (e: InaccessibleObjectException) {
            logFailure(operation, windowId, e)
            false
        } catch (e: IllegalArgumentException) {
            logFailure(operation, windowId, e)
            false
        } catch (e: ClassCastException) {
            logFailure(operation, windowId, e)
            false
        } catch (e: SecurityException) {
            logFailure(operation, windowId, e)
            false
        }

    private fun logFailure(
        operation: String,
        windowId: String,
        error: Throwable,
    ) {
        logger.warn(
            LogCategory.UI,
            "Could not $operation native macOS fullscreen listener",
            mapOf("windowId" to windowId),
            error,
        )
    }
}

/**
 * Captures AWT focus lifecycle events on the EDT and exposes a volatile
 * snapshot that JxBrowser callback threads can safely read.
 */
internal class AwtWindowFocusTracker {
    @Volatile
    private var focusedWindowId: String? = null

    fun snapshotRegistration(
        windowId: String,
        isFocused: Boolean,
    ) {
        if (isFocused) {
            focusedWindowId = windowId
        }
    }

    fun createListener(
        windowId: String,
        onFocusGained: () -> Unit = {},
    ): WindowAdapter =
        object : WindowAdapter() {
            override fun windowGainedFocus(e: WindowEvent?) {
                focusedWindowId = windowId
                onFocusGained()
            }

            override fun windowLostFocus(e: WindowEvent?) {
                if (focusedWindowId == windowId) {
                    focusedWindowId = null
                }
            }
        }

    fun onUnregistered(windowId: String) {
        if (focusedWindowId == windowId) {
            focusedWindowId = null
        }
    }

    fun isFocused(windowId: String): Boolean = focusedWindowId == windowId
}

internal data class RegisteredWindowFullscreenState(
    val window: Window,
    val nativeStateAvailable: Boolean,
    val nativeFullscreen: Boolean,
    val composeFullscreen: Boolean,
)

private data class WindowFullscreenSignals(
    val nativeTrackingAvailable: Boolean = false,
    val nativeStateAvailable: Boolean = false,
    val nativeFullscreen: Boolean = false,
    val nativeExitAlreadyNotified: Boolean = false,
    val composeFullscreen: Boolean = false,
)

/** Whether [extendedState] has the ICONIFIED bit, i.e. the window sits in the taskbar. */
internal fun isIconified(extendedState: Int): Boolean = (extendedState and Frame.ICONIFIED) != 0

/**
 * [extendedState] with only the ICONIFIED bit cleared.
 *
 * Deliberately not `Frame.NORMAL`, which is zero and therefore drops MAXIMIZED_BOTH as well:
 * restoring a window the user had maximized would hand it back to them as a small one. Pure and
 * top level so both branches are pinned by a test without needing a display.
 */
internal fun deiconified(extendedState: Int): Int = extendedState and Frame.ICONIFIED.inv()

/**
 * Undo whatever is keeping [this] off the screen, before something tries to focus it.
 *
 * Two different states hide a window and only one of them was handled. The comment this
 * replaced said "make window visible if minimized" and then tested `isVisible`, but a
 * minimized window is already visible: it is ICONIFIED. So `toFront()` and `requestFocus()`
 * ran against a window still in the taskbar, and the caller was told the focus succeeded.
 * Cross-window tab selection made that observable, by selecting a tab in a window the user
 * never saw come forward.
 *
 * Clears only the ICONIFIED bit rather than assigning `Frame.NORMAL`, which would also drop
 * MAXIMIZED_BOTH and restore a maximized window to a small one. Same form as
 * `SettingsWindow`, which already got this right.
 */
private fun Window.restoreForFocus() {
    if (!isVisible) {
        isVisible = true
    }
    if (this is Frame && isIconified(extendedState)) {
        extendedState = deiconified(extendedState)
    }
}

/**
 * Handles multi-window focus tracking with two intentionally different views:
 * [isWindowFocused] is the live AWT focus used to gate browser input, while
 * [focusedWindowFlow] retains last-focused semantics for external actions such
 * as deep links and file opens.
 */
@Suppress("TooManyFunctions")
// One cohesive registry over window focus/registration state - the functions are small,
// closely related accessors and mutations over that single registry, and splitting them
// across objects would only add indirection between callers who need them as a unit.
actual object WindowFocusManager {
    private val windows = ConcurrentHashMap<String, Window>()
    private val fullscreenSignals = ConcurrentHashMap<String, WindowFullscreenSignals>()
    internal val fullscreenExitNotifier = FullscreenExitNotifier()
    private val macOSFullscreenTracker =
        MacOSFullscreenTracker(
            onFullscreenChanged = { windowId, isFullscreen ->
                var shouldNotifyExit = false
                fullscreenSignals.compute(windowId) { _, current ->
                    val previous = current ?: WindowFullscreenSignals()
                    shouldNotifyExit =
                        !isFullscreen &&
                        shouldNotifyNativeFullscreenExit(
                            hadNativeState = previous.nativeStateAvailable,
                            wasNativeFullscreen = previous.nativeFullscreen,
                            composeFullscreen = previous.composeFullscreen,
                            exitAlreadyNotified = previous.nativeExitAlreadyNotified,
                        )
                    previous.copy(
                        nativeStateAvailable = true,
                        nativeFullscreen = isFullscreen,
                        nativeExitAlreadyNotified = false,
                    )
                }
                if (shouldNotifyExit) {
                    fullscreenExitNotifier.notifyExit(windowId)
                }
            },
            onFullscreenExitStarted = { windowId ->
                // An exit-start event proves that the registered window still
                // owns a native fullscreen Space even if registration missed
                // its earlier enter transition.
                fullscreenSignals.compute(windowId) { _, current ->
                    (current ?: WindowFullscreenSignals()).copy(
                        nativeStateAvailable = true,
                        nativeFullscreen = true,
                        nativeExitAlreadyNotified = true,
                    )
                }
                fullscreenExitNotifier.notifyExit(windowId)
            },
        )

    // EDT-confined; registerWindow/unregisterWindow enforce this before mutation.
    private val windowListeners = mutableMapOf<String, WindowAdapter>()
    private val awtFocusTracker = AwtWindowFocusTracker()

    // Mutated only on the EDT (registerWindow, the focus-gained listener,
    // unregisterWindow) but read from CLI, socket, JxBrowser and coroutine
    // threads via resolveActionableWindowId, so the snapshot must be volatile
    // for the same reason AwtWindowFocusTracker's is.
    @Volatile
    private var focusedWindowId: String? = null

    private var mainWindow: Window? = null // Kept for backward compatibility

    // StateFlow to observe focus changes (for elegant focus restoration)
    private val _focusedWindowFlow = MutableStateFlow<String?>(null)
    actual val focusedWindowFlow: StateFlow<String?> = _focusedWindowFlow.asStateFlow()

    /**
     * Registers an application window with focus tracking. Must run on the EDT.
     * A window registered before it is focused remains absent from the live
     * snapshot until its first focus-gained event, so browser input fails closed.
     *
     * @param windowId Unique identifier for the window
     * @param window The AWT window instance
     */
    fun registerWindow(
        windowId: String,
        window: Window,
    ) {
        check(SwingUtilities.isEventDispatchThread()) {
            "WindowFocusManager.registerWindow must run on the EDT"
        }
        windows[windowId] = window

        // First window becomes the main window (backward compatibility).
        if (mainWindow == null) {
            mainWindow = window
            focusedWindowId = windowId
        }

        // Registration runs on the EDT. Snapshot an already-focused window in
        // case its focus-gained event happened before the listener was attached.
        awtFocusTracker.snapshotRegistration(windowId, window.isFocused)

        val listener =
            awtFocusTracker.createListener(windowId) {
                focusedWindowId = windowId
                _focusedWindowFlow.value = windowId
            }

        windowListeners[windowId] = listener
        window.addWindowFocusListener(listener)

        // Native fullscreen tracking is optional and must not prevent the
        // focus listener above from being installed if EAWT is unavailable.
        fullscreenSignals.compute(windowId) { _, current ->
            (current ?: WindowFullscreenSignals()).copy(
                nativeTrackingAvailable = false,
                nativeStateAvailable = false,
                nativeFullscreen = false,
                nativeExitAlreadyNotified = false,
            )
        }
        val nativeTrackingAvailable = macOSFullscreenTracker.register(windowId, window)
        fullscreenSignals.compute(windowId) { _, current ->
            (current ?: WindowFullscreenSignals()).copy(
                nativeTrackingAvailable = nativeTrackingAvailable,
            )
        }
    }

    /**
     * Register the main application window (backward compatibility)
     *
     * @param window The AWT window instance
     */
    fun registerWindow(window: Window) {
        // Generate a default ID for backward compatibility
        val windowId = "window-${System.identityHashCode(window)}"
        registerWindow(windowId, window)
    }

    /**
     * Get a registered window by ID.
     *
     * @param windowId The window ID
     * @return The AWT Window, or null if not registered
     */
    fun getWindow(windowId: String): Window? = windows[windowId]

    /** Records Compose placement without overwriting the native macOS signal. */
    fun updateWindowFullscreen(
        windowId: String,
        isFullscreen: Boolean,
    ) {
        var shouldNotifyExit = false
        fullscreenSignals.compute(windowId) { _, current ->
            if (current == null && !isFullscreen) {
                return@compute null
            }
            val previous = current ?: WindowFullscreenSignals()
            shouldNotifyExit =
                !isFullscreen &&
                shouldNotifyComposeFullscreenExit(
                    wasComposeFullscreen = previous.composeFullscreen,
                    isNativeFullscreen = previous.nativeFullscreen,
                )
            previous.copy(composeFullscreen = isFullscreen)
        }
        if (shouldNotifyExit) {
            fullscreenExitNotifier.notifyExit(windowId)
        }
    }

    /** Returns the requested window and its independently tracked fullscreen signals. */
    internal fun getWindowFullscreenState(windowId: String): RegisteredWindowFullscreenState? {
        val window = windows[windowId] ?: return null
        val signals = fullscreenSignals[windowId] ?: WindowFullscreenSignals()
        return RegisteredWindowFullscreenState(
            window = window,
            // A listener reports transitions, not initial state. Compose plus
            // geometry remains the fallback until the first native event.
            nativeStateAvailable =
                signals.nativeTrackingAvailable &&
                    signals.nativeStateAvailable,
            nativeFullscreen = signals.nativeFullscreen,
            composeFullscreen = signals.composeFullscreen,
        )
    }

    /**
     * Unregisters a window when it closes. Must run on the EDT.
     *
     * @param windowId The window ID to unregister
     */
    fun unregisterWindow(windowId: String) {
        check(SwingUtilities.isEventDispatchThread()) {
            "WindowFocusManager.unregisterWindow must run on the EDT"
        }
        windowListeners.remove(windowId)?.let { listener ->
            windows[windowId]?.removeWindowFocusListener(listener)
        }
        macOSFullscreenTracker.unregister(windowId)

        windows.remove(windowId)
        val signals = fullscreenSignals.remove(windowId)
        if (signals?.composeFullscreen == true || signals?.nativeFullscreen == true) {
            fullscreenExitNotifier.notifyExit(windowId)
        }
        awtFocusTracker.onUnregistered(windowId)
        if (focusedWindowId == windowId) {
            // Preserve the existing last-focused flow contract for external
            // actions; another window will publish itself when it gains focus.
            focusedWindowId = null
            _focusedWindowFlow.value = null
        }
    }

    /**
     * Returns the current AWT focus snapshot maintained by EDT focus events.
     * The volatile snapshot is safe to read from JxBrowser callback threads.
     * It intentionally returns false before the first focus-gained event and
     * after unregister, keeping orphaned owner-scoped browsers fail-closed.
     */
    actual fun isWindowFocused(windowId: String): Boolean = awtFocusTracker.isFocused(windowId)

    actual fun isWindowOpen(windowId: String): Boolean = windows.containsKey(windowId)

    /**
     * Best-effort window id for actions that need "the" active window but may run
     * before a real OS focus-gained event has fired for it — e.g. a deep link
     * dispatched by an MCP tool while the caller's own window (not BOSS) has OS
     * focus. Prefers [focusedWindowId] (set at registration and on every focus
     * gain) over [focusedWindowFlow] (only ever set inside the focus-gained
     * listener, so it can lag or stay null even once a window is plainly
     * available), falling back to any registered window. Returns null only if no
     * window is registered at all.
     *
     * **Threading**: safe to call from any thread. Every source it reads is
     * either volatile ([focusedWindowId]), a [StateFlow] ([focusedWindowFlow])
     * or a [ConcurrentHashMap] ([windows]), so callers on CLI, socket, JxBrowser
     * callback and coroutine threads do not need to hop to the EDT first. The
     * result is a snapshot: the window may be unregistered a moment later, so
     * consumers must tolerate a stale id (every current consumer emits an event
     * keyed by it, which is dropped if no such window listens).
     */
    actual fun resolveActionableWindowId(): String? =
        resolveActionableWindowIdFrom(
            lastFocusedWindowId = focusedWindowId,
            focusFlowWindowId = focusedWindowFlow.value,
            registeredWindowIds = windows.keys,
        )

    /**
     * Bring a specific window to front by its ID
     *
     * @param windowId The ID of the window to focus
     * @return true if the window was found and focused, false otherwise
     */
    actual fun focusWindow(windowId: String): Boolean {
        val window = windows[windowId]
        return if (window != null) {
            SwingUtilities.invokeLater {
                window.restoreForFocus()
                window.toFront()
                window.requestFocus()
            }
            true
        } else {
            false
        }
    }

    /**
     * Bring the first registered window to front (backward compatibility)
     */
    actual fun bringToFront() {
        mainWindow?.let { window ->
            SwingUtilities.invokeLater {
                window.restoreForFocus()

                // Bring to front
                window.toFront()

                // Request focus
                window.requestFocus()
            }
        }
    }
}
