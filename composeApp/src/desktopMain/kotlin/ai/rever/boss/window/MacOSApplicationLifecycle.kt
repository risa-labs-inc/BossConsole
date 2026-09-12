package ai.rever.boss.window

import ai.rever.boss.utils.SystemUtils
import ai.rever.boss.utils.WindowFocusManager
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.awt.desktop.AppReopenedListener
import javax.swing.SwingUtilities

/**
 * Bridges macOS Dock activation into the Compose window state owned by BOSS.
 * Registration lasts for the application process and is removed during normal
 * application shutdown.
 */
internal object MacOSApplicationLifecycle {
    fun install(canCreateMainWindow: () -> Boolean): AutoCloseable? {
        if (!SystemUtils.isMacOS || GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported()) {
            return null
        }

        val desktop = Desktop.getDesktop()
        return if (desktop.isSupported(Desktop.Action.APP_EVENT_REOPENED)) {
            val listener = AppReopenedListener { handleReopen(canCreateMainWindow) }
            desktop.addAppEventListener(listener)
            AutoCloseable { desktop.removeAppEventListener(listener) }
        } else {
            null
        }
    }

    private fun handleReopen(canCreateMainWindow: () -> Boolean) {
        val reopen: () -> Unit = {
            val action =
                decideAppReopen(
                    windows =
                        WindowManager.windows.map {
                            WindowLifecycleSnapshot(
                                id = it.id,
                                windowType = it.windowType,
                                isVisible = it.isVisible,
                            )
                        },
                    preferredWindowId = WindowFocusManager.resolveActionableWindowId(),
                    canCreateMainWindow = canCreateMainWindow(),
                )

            when (action) {
                is AppReopenAction.Reveal -> {
                    WindowManager.getWindow(action.windowId)?.isVisible = true
                    WindowFocusManager.focusWindow(action.windowId)
                }

                is AppReopenAction.Focus -> {
                    WindowFocusManager.focusWindow(action.windowId)
                }

                AppReopenAction.CreateMainWindow -> {
                    WindowManager.createNewWindow()
                }

                AppReopenAction.Ignore -> {
                    // The main window is intentionally deferred during browser setup.
                }
            }
        }

        if (SwingUtilities.isEventDispatchThread()) {
            reopen()
        } else {
            SwingUtilities.invokeLater(reopen)
        }
    }
}
