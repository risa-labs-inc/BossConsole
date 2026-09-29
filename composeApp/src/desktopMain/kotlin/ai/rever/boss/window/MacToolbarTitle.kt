package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.clazz
import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.send
import ai.rever.boss.window.MacToolbarRuntime.string
import com.sun.jna.Memory
import com.sun.jna.Pointer

/** Standard AppKit label, separate from the Space popup and never drawn as a button. */
internal object MacToolbarTitle {
    fun updateTextItem(
        item: Pointer,
        action: NativeTitleBarAction,
        addressField: MacToolbarAddressField,
        delegate: Pointer?,
        icon: Pointer?,
    ): Boolean =
        when {
            action.id == "terminal_title" -> {
                update(item, action.label)
                true
            }

            action.textInput != null -> {
                addressField.update(item, action.textInput, delegate, icon)
                true
            }

            else -> {
                false
            }
        }

    fun update(
        item: Pointer,
        title: String,
    ) {
        val label = pointer(item, "view") ?: create(item)
        send(label, "setStringValue:", string(title))
        send(label, "sizeToFit")
        send(item, "setLabel:", string(title))
        send(item, "setToolTip:", string(title))
        // Keep the text field at its fitted height. A forced 24pt field places its text
        // near the top while AppKit centers the popup's text, producing different baselines.
        val height = fittedHeight(label)
        send(item, "setMinSize:", ToolbarIconSize(60.0, height))
        send(item, "setMaxSize:", ToolbarIconSize(360.0, height))
    }

    private fun fittedHeight(label: Pointer): Double {
        val frame = pointer(label, "valueForKey:", string("frame")) ?: return 17.0
        return Memory(32).use { bytes ->
            send(frame, "getValue:size:", bytes, 32L)
            bytes.getDouble(24).takeIf { it.isFinite() && it > 0.0 } ?: 17.0
        }
    }

    private fun create(item: Pointer): Pointer {
        val label = checkNotNull(pointer(clazz("NSTextField"), "labelWithString:", string("")))
        send(label, "setFont:", pointer(clazz("NSFont"), "boldSystemFontOfSize:", 13.0))
        send(label, "setTextColor:", pointer(clazz("NSColor"), "labelColor"))
        send(label, "setLineBreakMode:", 4L) // NSLineBreakByTruncatingTail
        send(label, "setUsesSingleLineMode:", 1.toByte())
        send(item, "setView:", label)
        send(item, "setBordered:", 0.toByte())
        // Match the Space popup's ordinary-item role. AppKit moves navigational items
        // ahead of ordinary items regardless of their insertion order.
        send(item, "setNavigational:", 0.toByte())
        return label
    }
}
