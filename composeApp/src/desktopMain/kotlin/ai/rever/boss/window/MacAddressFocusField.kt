package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.clazz
import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.selector
import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.Pointer

/** Stock NSSearchField appearance and editing; only bridge its native focus handoff. */
internal object MacAddressFocusField {
    private fun interface Focus : Callback {
        fun invoke(
            self: Pointer?,
            command: Pointer?,
        ): Byte
    }

    private fun interface Action : Callback {
        fun invoke(
            self: Pointer?,
            command: Pointer?,
            sender: Pointer?,
        )
    }

    private fun parent(method: String): Function =
        Function.getFunction(
            checkNotNull(
                MacToolbarRuntime.objc.getFunction("class_getMethodImplementation").invokePointer(
                    arrayOf(clazz("NSSearchField"), selector(method)),
                ),
            ),
        )

    private val parentFocus: Function by lazy { parent("becomeFirstResponder") }
    private val parentMouse: Function by lazy { parent("mouseDown:") }
    private val parentSelect: Function by lazy { parent("selectText:") }

    private val focus =
        Focus { self, command ->
            // Preserve AppKit's field editor and selection by calling the superclass implementation.
            val accepted = parentFocus.invokeInt(arrayOf(self, command)) != 0
            claim(self)
            if (accepted) 1.toByte() else 0.toByte()
        }

    private val mouse =
        Action { self, command, event ->
            parentMouse.invokeVoid(arrayOf(self, command, event))
            claim(self)
        }

    private val select =
        Action { self, command, sender ->
            // Selection can end and restart the shared NSTextView editor within the same field.
            parentSelect.invokeVoid(arrayOf(self, command, sender))
            claim(self)
        }

    private fun claim(self: Pointer?) {
        val delegate = pointer(self, "delegate")
        val field = MacSidebarToolbarBridge.owners[Pointer.nativeValue(delegate)]?.addressField
        if (field != null && field.editing.view == self) field.claimEditorFocus()
    }

    val fieldClass: Pointer by lazy {
        val objc = MacToolbarRuntime.objc
        val cls =
            checkNotNull(
                objc.getFunction("objc_allocateClassPair").invokePointer(
                    arrayOf(clazz("NSSearchField"), "BossConsoleAddressSearchField", 0L),
                ),
            )
        listOf(
            Triple("becomeFirstResponder", focus, "c@:"),
            Triple("mouseDown:", mouse, "v@:@"),
            Triple("selectText:", select, "v@:@"),
        ).forEach { (name, callback, types) ->
            check(
                objc.getFunction("class_addMethod").invokeInt(
                    arrayOf(cls, selector(name), CallbackReference.getFunctionPointer(callback), types),
                ) != 0,
            )
        }
        objc.getFunction("objc_registerClassPair").invokeVoid(arrayOf(cls))
        cls
    }
}
