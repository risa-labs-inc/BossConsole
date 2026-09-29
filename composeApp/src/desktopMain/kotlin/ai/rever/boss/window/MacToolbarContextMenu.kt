package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.clazz
import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.selector
import ai.rever.boss.window.MacToolbarRuntime.send
import ai.rever.boss.window.MacToolbarRuntime.string
import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Pointer

/** Only bridges the context-menu event; all drawing and left-click behavior remain NSPopUpButton's. */
internal object MacToolbarContextMenu {
    private interface MenuCallback : Callback {
        fun invoke(
            self: Pointer?,
            command: Pointer?,
            event: Pointer?,
        ): Pointer?
    }

    private val menuCallback =
        object : MenuCallback {
            override fun invoke(
                self: Pointer?,
                command: Pointer?,
                event: Pointer?,
            ): Pointer? = pointer(pointer(self, "cell"), "representedObject")
        }

    val popupClass: Pointer by lazy {
        val objc = MacToolbarRuntime.objc
        val cls =
            checkNotNull(
                objc
                    .getFunction("objc_allocateClassPair")
                    .invokePointer(arrayOf(clazz("NSPopUpButton"), "BossConsoleSpacePopUpButton", 0L)),
            )
        check(
            objc.getFunction("class_addMethod").invokeInt(
                arrayOf(
                    cls,
                    selector("menuForEvent:"),
                    CallbackReference.getFunctionPointer(menuCallback),
                    "@@:@",
                ),
            ) != 0,
        )
        objc.getFunction("objc_registerClassPair").invokeVoid(arrayOf(cls))
        cls
    }

    fun update(
        popup: Pointer,
        entries: List<NativeTitleBarAction>,
        target: Pointer?,
    ) {
        val menu = pointer(pointer(clazz("NSMenu"), "alloc"), "initWithTitle:", string("Space"))
        try {
            send(menu, "setAutoenablesItems:", 0.toByte())
            entries.forEach { entry ->
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
            // NSCell retains this object; it dies with the native control, without an owner map.
            send(pointer(popup, "cell"), "setRepresentedObject:", if (entries.isEmpty()) null else menu)
        } finally {
            send(menu, "release")
        }
    }
}
