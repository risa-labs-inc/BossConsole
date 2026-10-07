package ai.rever.boss.window

import ai.rever.boss.components.window_panel.SplitViewStateRegistry
import ai.rever.boss.utils.SystemUtils
import ai.rever.boss.utils.WindowFocusManager
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.awt.Frame

private val closeLogger = BossLogger.forComponent("WindowClosePreparation")

/** Shared by window close and app Quit, before Compose disposes the owning AWT window. */
internal fun prepareWindowForClose(windowId: String) {
    val window = WindowFocusManager.getWindow(windowId)
    if (window is Frame) {
        var needsTransitionWait = false
        if (window.extendedState != Frame.NORMAL) {
            closeLogger.debug(
                LogCategory.UI,
                "Exiting maximized state before window close",
                mapOf("windowId" to windowId, "extendedState" to window.extendedState.toString()),
            )
            window.extendedState = Frame.NORMAL
            needsTransitionWait = true
        }
        if (SystemUtils.isMacOS && exitNativeFullscreenForClose(windowId, window)) needsTransitionWait = true
        // Preserve the existing close-path wait: both transitions are asynchronous.
        if (needsTransitionWait) runBlocking { delay(150) }
    }
    // Detach browser UI before AWT disposal to avoid OffScreenWidget native crashes.
    SplitViewStateRegistry.getState(windowId)?.disposeAllBrowsersBlocking()
}

private fun exitNativeFullscreenForClose(
    windowId: String,
    window: Frame,
): Boolean {
    val screenBounds =
        window.graphicsConfiguration
            ?.device
            ?.defaultConfiguration
            ?.bounds
    val bounds = window.bounds
    val isFullscreen = screenBounds?.let { bounds.width >= it.width && bounds.height >= it.height } == true
    // The JDK operation toggles fullscreen, so never issue it for a normal window.
    return if (!isFullscreen) {
        false
    } else {
        try {
            closeLogger.debug(
                LogCategory.UI,
                "Requesting macOS fullscreen exit before window close",
                mapOf("windowId" to windowId),
            )
            val appClass = Class.forName("com.apple.eawt.Application")
            val app = appClass.getMethod("getApplication").invoke(null)
            appClass.getMethod("requestToggleFullScreen", java.awt.Window::class.java).invoke(app, window)
            true
        } catch (e: ReflectiveOperationException) {
            closeLogger.debug(
                LogCategory.UI,
                "macOS fullscreen exit not available",
                mapOf("errorType" to e.javaClass.simpleName, "reason" to (e.message ?: "unknown")),
            )
            false
        }
    }
}
