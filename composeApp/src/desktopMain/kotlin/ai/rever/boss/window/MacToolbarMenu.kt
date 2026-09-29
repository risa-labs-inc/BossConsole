package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.clazz
import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.selector
import ai.rever.boss.window.MacToolbarRuntime.send
import ai.rever.boss.window.MacToolbarRuntime.string
import com.sun.jna.Pointer

/** A stock borderless NSPopUpButton; AppKit owns its single-line layout and menu interaction. */
internal object MacToolbarMenu {
    fun update(
        item: Pointer,
        action: NativeTitleBarAction,
        target: Pointer?,
    ) {
        val popup = pointer(item, "view") ?: create(item)
        send(item, "setLabel:", string(action.label))
        send(item, "setToolTip:", string("${action.label} — ${action.subtitle.orEmpty()}"))
        send(item, "setBordered:", 0.toByte())
        send(item, "setEnabled:", 1.toByte())
        MacToolbarContextMenu.update(popup, action.contextMenu, target)
        val menu = pointer(pointer(clazz("NSMenu"), "alloc"), "initWithTitle:", string("Spaces"))
        try {
            send(menu, "setAutoenablesItems:", 0.toByte())
            val header =
                pointer(
                    pointer(clazz("NSMenuItem"), "alloc"),
                    "initWithTitle:action:keyEquivalent:",
                    string(action.label),
                    null,
                    string(""),
                )
            send(menu, "addItem:", header)
            send(header, "release")
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
                send(row, "setState:", if (entry.active) 1L else 0L)
                send(menu, "addItem:", row)
                send(row, "release")
            }
            send(popup, "setMenu:", menu)
            send(popup, "sizeToFit")
        } finally {
            send(menu, "release")
        }
    }

    private fun create(item: Pointer): Pointer {
        val popup = checkNotNull(pointer(MacToolbarContextMenu.popupClass, "new"))
        send(popup, "setPullsDown:", 1.toByte())
        send(popup, "setBordered:", 0.toByte())
        send(item, "setView:", popup)
        send(popup, "release")
        return popup
    }
}
