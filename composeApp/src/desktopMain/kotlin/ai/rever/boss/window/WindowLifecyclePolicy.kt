package ai.rever.boss.window

/** The result of asking BOSS to close a main application window. */
internal enum class WindowCloseDisposition {
    HIDE_AND_RETAIN,
    DISPOSE,
}

/** A platform-neutral view of the window state needed for application reopen. */
internal data class WindowLifecycleSnapshot(
    val id: String,
    val windowType: WindowType,
    val isVisible: Boolean,
)

/** The action to take when macOS asks a running BOSS application to reopen. */
internal sealed interface AppReopenAction {
    data class Reveal(
        val windowId: String,
    ) : AppReopenAction

    data class Focus(
        val windowId: String,
    ) : AppReopenAction

    data object CreateMainWindow : AppReopenAction

    data object Ignore : AppReopenAction
}

/**
 * Preserve only the final main window on macOS. Every other close keeps the
 * existing destructive window-close behavior.
 */
internal fun decideWindowCloseDisposition(
    isMacOS: Boolean,
    closingWindowType: WindowType,
    hasOtherMainWindows: Boolean,
): WindowCloseDisposition =
    if (isMacOS && closingWindowType == WindowType.MAIN && !hasOtherMainWindows) {
        WindowCloseDisposition.HIDE_AND_RETAIN
    } else {
        WindowCloseDisposition.DISPOSE
    }

/**
 * Prefer the retained main window, then the most recently actionable visible
 * main window, then any visible main window. Auxiliary windows never stand in
 * for the main BOSS workspace.
 */
internal fun decideAppReopen(
    windows: List<WindowLifecycleSnapshot>,
    preferredWindowId: String?,
    canCreateMainWindow: Boolean = true,
): AppReopenAction {
    val mainWindows = windows.filter { it.windowType == WindowType.MAIN }
    val hiddenMainWindow = mainWindows.firstOrNull { !it.isVisible }
    val visibleMainWindows = mainWindows.filter { it.isVisible }
    val preferredMainWindow = visibleMainWindows.firstOrNull { it.id == preferredWindowId }
    val firstVisibleMainWindow = visibleMainWindows.firstOrNull()

    return when {
        hiddenMainWindow != null -> AppReopenAction.Reveal(hiddenMainWindow.id)
        preferredMainWindow != null -> AppReopenAction.Focus(preferredMainWindow.id)
        firstVisibleMainWindow != null -> AppReopenAction.Focus(firstVisibleMainWindow.id)
        canCreateMainWindow -> AppReopenAction.CreateMainWindow
        else -> AppReopenAction.Ignore
    }
}
