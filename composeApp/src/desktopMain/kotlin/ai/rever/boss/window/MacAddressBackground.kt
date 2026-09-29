package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.send
import com.sun.jna.Pointer

/** AppKit supplies the outer toolbar glass; the URL text must not draw a second capsule. */
internal fun installNativeAddressBackground(
    item: Pointer,
    field: Pointer,
) {
    send(field, "setBezeled:", 0.toByte())
    send(field, "setBordered:", 0.toByte())
    send(field, "setDrawsBackground:", 0.toByte())
    send(item, "setView:", field)
    send(item, "setBordered:", 1.toByte())
}
