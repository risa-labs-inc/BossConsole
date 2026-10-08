package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.send
import com.sun.jna.Pointer

/** Presentation is independent of geometry; never force-display the window again. */
internal fun updateMacSidebarOverlayPresentation(
    handle: Long,
    width: Int,
) {
    if (handle == 0L) return
    MacToolbarRuntime.dispatch {
        val window = Pointer(handle)
        if (!MacToolbarRuntime.isLiveWindow(window)) return@dispatch
        send(window, "setAnimationBehavior:", 2L)
        send(window, "setHasShadow:", 0.toByte())
        // AppKit clamps borderless windows to ten points. Hide the final strip.
        send(window, "setAlphaValue:", if (width < 10) 0.0 else 1.0)
    }
}
