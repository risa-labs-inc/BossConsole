package ai.rever.boss.window

import ai.rever.boss.utils.WindowFocusManager

/** App-level window actions, including a Dock reopen after the last window closes. */
internal class ApplicationWindowLifecycle {
    fun openInitialWindow(startWithoutWindow: Boolean = false) {
        if (!startWithoutWindow && WindowManager.windows.isEmpty()) WindowManager.createNewWindow()
    }

    fun reopen() {
        if (WindowManager.windows.isEmpty()) {
            WindowManager.createNewWindow()
            return
        }
        val target =
            WindowManager.windows.firstOrNull { WindowFocusManager.getWindow(it.id)?.isFocused == true }
                ?: WindowManager.windows.last()
        WindowFocusManager.focusWindow(target.id)
    }
}
