package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.clazz
import ai.rever.boss.window.MacToolbarRuntime.number
import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.send
import ai.rever.boss.window.MacToolbarRuntime.string
import com.sun.jna.Pointer

/** Owned and used on AppKit's main queue, with the window's original appearance retained. */
internal class MacToolbarAppearance(
    private val window: Pointer,
) {
    private val previousAppearance = pointer(window, "appearance")?.also { send(it, "retain") }
    private val previousBackground = pointer(window, "backgroundColor")?.also { send(it, "retain") }
    private val previousTransparency = number(window, "titlebarAppearsTransparent")
    private var requested: Pair<Boolean, Int>? = null
    private var applied: Triple<Boolean, Int, Boolean>? = null

    fun update(
        dark: Boolean,
        argb: Int,
    ) {
        requested = dark to argb
        val fullscreen = number(window, "styleMask") and (1L shl 14) != 0L
        val next = Triple(dark, argb, fullscreen)
        if (applied == next) return
        val name = if (dark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua"
        send(window, "setAppearance:", pointer(clazz("NSAppearance"), "appearanceNamed:", string(name)))
        val color =
            pointer(
                clazz("NSColor"),
                "colorWithSRGBRed:green:blue:alpha:",
                ((argb ushr 16) and 255) / 255.0,
                ((argb ushr 8) and 255) / 255.0,
                (argb and 255) / 255.0,
                ((argb ushr 24) and 255) / 255.0,
            )
        send(window, "setBackgroundColor:", color)
        // Fullscreen chrome is no longer backed by the Compose title-bar inset. Let AppKit
        // paint its themed material there instead of exposing the dark fullscreen backing.
        val opaqueFullscreen = fullscreen && (argb ushr 24) != 0
        send(window, "setTitlebarAppearsTransparent:", if (opaqueFullscreen) 0.toByte() else 1.toByte())
        applied = next
    }

    fun refresh() {
        requested?.let { (dark, argb) -> update(dark, argb) }
    }

    fun close(restore: Boolean) {
        if (restore) {
            send(window, "setAppearance:", previousAppearance)
            send(window, "setBackgroundColor:", previousBackground)
            send(window, "setTitlebarAppearsTransparent:", previousTransparency.toByte())
        }
        send(previousAppearance, "release")
        send(previousBackground, "release")
    }
}
