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
        val menu = create(action, target)
        try {
            send(item, "setMenu:", menu)
            send(item, "setShowsIndicator:", 0.toByte())
        } finally {
            send(menu, "release")
        }
    }

    fun show(
        view: Pointer,
        action: NativeTitleBarAction,
        target: Pointer?,
    ) {
        val event = pointer(pointer(clazz("NSApplication"), "sharedApplication"), "currentEvent") ?: return
        val menu = create(action, target)
        try {
            send(clazz("NSMenu"), "popUpContextMenu:withEvent:forView:", menu, event, view)
        } finally {
            send(menu, "release")
        }
    }

    private fun create(
        action: NativeTitleBarAction,
        target: Pointer?,
    ): Pointer {
        val menu = pointer(pointer(clazz("NSMenu"), "alloc"), "initWithTitle:", string(action.label))
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
            send(row, "setEnabled:", if (entry.enabled) 1.toByte() else 0.toByte())
            send(row, "setState:", if (entry.active) 1L else 0L)
            send(menu, "addItem:", row)
            send(row, "release")
        }
        return checkNotNull(menu)
    }
}
