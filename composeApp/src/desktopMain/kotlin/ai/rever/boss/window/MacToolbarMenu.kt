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
        val controlClass = if (action.menu == null) MacToolbarContextMenu.buttonClass else MacToolbarContextMenu.popupClass
        val existing = pointer(item, "view")
        val popup = if (existing != null && pointer(existing, "class") == controlClass) existing else create(item, controlClass, action.menu != null)
        send(item, "setLabel:", string(action.label))
        send(item, "setToolTip:", string("${action.label} — ${action.subtitle.orEmpty()}"))
        send(item, "setBordered:", 0.toByte())
        send(item, "setEnabled:", 1.toByte())
        MacToolbarContextMenu.update(popup, action.contextMenu, target)
        if (action.menu == null) {
            send(popup, "setTitle:", string(action.label))
            send(popup, "setFont:", pointer(clazz("NSFont"), "systemFontOfSize:", 13.0))
            send(popup, "setContentTintColor:", pointer(clazz("NSColor"), "labelColor"))
            val chevron = pointer(clazz("NSImage"), "imageWithSystemSymbolName:accessibilityDescription:", string("chevron.down"), null)
            val symbolSize = pointer(clazz("NSImageSymbolConfiguration"), "configurationWithPointSize:weight:", 10.0, 0.0)
            send(popup, "setImage:", pointer(chevron, "imageWithSymbolConfiguration:", symbolSize))
            send(popup, "setImagePosition:", 3L) // NSImageRight: matches the native Space popup.
            send(popup, "setEnabled:", 1.toByte())
            send(popup, "setTarget:", target)
            send(popup, "setAction:", selector("activate:"))
            send(popup, "setTag:", MacToolbarRuntime.number(item, "tag"))
            send(popup, "sizeToFit")
            return
        }
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
            MacToolbarActionMenu.appendEntries(checkNotNull(menu), action.menu.orEmpty(), target)
            send(popup, "setMenu:", menu)
            send(popup, "sizeToFit")
        } finally {
            send(menu, "release")
        }
    }

    private fun create(item: Pointer, controlClass: Pointer, pullsDown: Boolean): Pointer {
        val popup = checkNotNull(pointer(controlClass, "new"))
        if (pullsDown) send(popup, "setPullsDown:", 1.toByte())
        send(popup, "setBordered:", 0.toByte())
        send(item, "setView:", popup)
        send(popup, "release")
        return popup
    }
}
