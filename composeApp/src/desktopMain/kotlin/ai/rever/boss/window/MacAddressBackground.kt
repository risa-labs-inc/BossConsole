package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.clazz
import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.send
import ai.rever.boss.window.MacToolbarRuntime.string
import com.sun.jna.Memory
import com.sun.jna.Pointer

/** Keep the native search bezel and field editor; only its toolbar width is customized. */
internal fun installNativeAddressBackground(
    item: Pointer,
    field: Pointer,
): Double {
    send(field, "sizeToFit")
    val height =
        Memory(32).use { rect ->
            send(pointer(field, "valueForKey:", string("frame")), "getValue:size:", rect, 32L)
            rect.getDouble(24).takeIf { it.isFinite() && it > 0.0 } ?: 24.0
        }
    // A direct NSSearchField toolbar item gets AppKit's fixed search-field width cap.
    val container = checkNotNull(pointer(clazz("NSView"), "new"))
    send(container, "setFrameSize:", ToolbarIconSize(180.0, height))
    send(field, "setFrameSize:", ToolbarIconSize(180.0, height))
    send(field, "setAutoresizingMask:", 2L or 16L)
    send(container, "addSubview:", field)
    send(item, "setView:", container)
    send(container, "release")
    // AppKit supplies toolbar glass. Keep the field's bezel so its native text inset
    // and field editor respect that rounded background instead of painting over it.
    send(item, "setBordered:", 1.toByte())
    return height
}
