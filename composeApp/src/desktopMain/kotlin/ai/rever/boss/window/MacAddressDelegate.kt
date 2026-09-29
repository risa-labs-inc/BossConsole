package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.clazz
import ai.rever.boss.window.MacToolbarRuntime.number
import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.selector
import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Pointer

/** Strong callback references for NSTextField's delegate, shared by the existing toolbar owner. */
internal object MacAddressDelegate {
    private fun interface Notification : Callback {
        fun invoke(
            self: Pointer?,
            cmd: Pointer?,
            notification: Pointer?,
        )
    }

    private fun interface Command : Callback {
        fun invoke(
            self: Pointer?,
            cmd: Pointer?,
            control: Pointer?,
            editor: Pointer?,
            command: Pointer?,
        ): Byte
    }

    private val entered = Notification { self, _, _ -> field(self)?.copyButton?.hover(true) }
    private val exited = Notification { self, _, _ -> field(self)?.copyButton?.hover(false) }
    private val copy =
        Notification { self, _, _ ->
            val address = field(self)
            address
                ?.editing
                ?.input
                ?.value
                ?.let { address.copyButton.copy(it) }
        }

    private fun field(self: Pointer?): MacToolbarAddressField? {
        val toolbar = MacSidebarToolbarBridge.owners[Pointer.nativeValue(self)]
        return toolbar?.addressField
    }

    private val changed = Notification { self, _, _ -> owner(self)?.notification("change") }
    private val ended = Notification { self, _, _ -> owner(self)?.notification("end") }
    private val command =
        Command { self, _, _, _, cmd ->
            val name =
                MacToolbarRuntime.objc
                    .getFunction("sel_getName")
                    .invokePointer(arrayOf(cmd))
                    ?.getString(0)
                    .orEmpty()
            val app = pointer(clazz("NSApplication"), "sharedApplication")
            val flags = number(pointer(app, "currentEvent"), "modifierFlags")
            if (owner(self)?.command(name, flags and (1L shl 17) != 0L) == true) 1.toByte() else 0.toByte()
        }

    private fun owner(self: Pointer?): MacAddressEditing? {
        val toolbar = MacSidebarToolbarBridge.owners[Pointer.nativeValue(self)]
        return toolbar?.addressField?.editing
    }

    fun install(cls: Pointer) {
        add(cls, "mouseEntered:", entered, "v@:@")
        add(cls, "mouseExited:", exited, "v@:@")
        add(cls, "copyAddress:", copy, "v@:@")
        add(cls, "controlTextDidChange:", changed, "v@:@")
        add(cls, "controlTextDidEndEditing:", ended, "v@:@")
        add(cls, "control:textView:doCommandBySelector:", command, "c@:@@:")
    }

    private fun add(
        cls: Pointer,
        name: String,
        callback: Callback,
        types: String,
    ) {
        check(
            MacToolbarRuntime.objc.getFunction("class_addMethod").invokeInt(
                arrayOf(cls, selector(name), CallbackReference.getFunctionPointer(callback), types),
            ) != 0,
        )
    }
}
