package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.clazz
import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.selector
import ai.rever.boss.window.MacToolbarRuntime.send
import ai.rever.boss.window.MacToolbarRuntime.string
import com.sun.jna.Pointer

/** Standard AppKit toolbar menu for the trailing More button. */
internal object MacToolbarActionMenu {
    fun update(
        item: Pointer,
        action: NativeTitleBarAction,
        target: Pointer?,
    ) {
        val menu = pointer(pointer(clazz("NSMenu"), "alloc"), "initWithTitle:", string(action.label))
        try {
            send(menu, "setAutoenablesItems:", 0.toByte())
            action.menu.orEmpty().forEach { entry ->
                val row =
                    pointer(
                        pointer(clazz("NSMenuItem"), "alloc"),
                        "initWithTitle:action:keyEquivalent:",
                        string(entry.label),
                        selector("activate:"),
                        string(""),
                    )
                send(row, "setTarget:", target)
                send(row, "setRepresentedObject:", string(entry.id))
                send(menu, "addItem:", row)
                send(row, "release")
            }
            send(item, "setMenu:", menu)
            send(item, "setShowsIndicator:", 0.toByte())
        } finally {
            send(menu, "release")
        }
    }
}
