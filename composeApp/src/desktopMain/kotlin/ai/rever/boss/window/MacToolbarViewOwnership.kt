package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.number
import ai.rever.boss.window.MacToolbarRuntime.pointer
import com.sun.jna.Pointer

/** Fullscreen AppKit chrome can live in a direct child of the exact document window. */
internal fun ownsNativeToolbarView(
    window: Pointer,
    view: Pointer?,
): Boolean = ownsNativeToolbarWindow(window, pointer(view, "window"))

internal fun ownsNativeToolbarWindow(
    window: Pointer,
    nativeWindow: Pointer?,
): Boolean {
    if (nativeWindow == null) return false
    val fullscreen = number(window, "styleMask") and (1L shl 14) != 0L
    return nativeWindow == window || (fullscreen && pointer(nativeWindow, "parentWindow") == window)
}
