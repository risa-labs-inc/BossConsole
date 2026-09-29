package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.clazz
import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.selector
import ai.rever.boss.window.MacToolbarRuntime.send
import ai.rever.boss.window.MacToolbarRuntime.string
import com.sun.jna.Pointer
import com.sun.jna.Structure

/** A real native button avoids NSSearchField hiding its cancel cell outside active editing. */
internal class MacAddressCopyButton {
    private var button: Pointer? = null
    private var hovered = false

    fun install(
        view: Pointer,
        target: Pointer?,
    ) {
        val cancelCell = pointer(pointer(view, "cell"), "cancelButtonCell")
        send(cancelCell, "setTransparent:", 1.toByte())
        send(cancelCell, "setEnabled:", 0.toByte())
        val image =
            pointer(
                clazz("NSImage"),
                "imageWithSystemSymbolName:accessibilityDescription:",
                string("link"),
                string("Copy URL"),
            )
        button = pointer(clazz("NSButton"), "buttonWithImage:target:action:", image, target, selector("copyAddress:"))
        send(button, "setBordered:", 0.toByte())
        send(button, "setRefusesFirstResponder:", 1.toByte())
        send(button, "setToolTip:", string("Copy URL"))
        send(button, "setAccessibilityLabel:", string("Copy URL"))
        send(button, "setTranslatesAutoresizingMaskIntoConstraints:", 0.toByte())
        send(view, "addSubview:", button)
        pinButton(view)
        send(button, "setHidden:", 1.toByte())
        hover(false)
        // InVisibleRect follows the field as the toolbar resizes, without polling its bounds.
        val area =
            pointer(
                pointer(clazz("NSTrackingArea"), "alloc"),
                "initWithRect:options:owner:userInfo:",
                AddressTrackingRect(),
                1L or 32L or 512L,
                target,
                null,
            )
        send(view, "addTrackingArea:", area)
        send(area, "release")
    }

    private fun pinButton(view: Pointer) {
        val trailing =
            pointer(
                pointer(button, "trailingAnchor"),
                "constraintEqualToAnchor:constant:",
                pointer(view, "trailingAnchor"),
                -5.0,
            )
        val center =
            pointer(
                pointer(button, "centerYAnchor"),
                "constraintEqualToAnchor:",
                pointer(view, "centerYAnchor"),
            )
        val width = pointer(pointer(button, "widthAnchor"), "constraintEqualToConstant:", 20.0)
        val height = pointer(pointer(button, "heightAnchor"), "constraintEqualToConstant:", 20.0)
        listOf(trailing, center, width, height).forEach { send(it, "setActive:", 1.toByte()) }
    }

    fun hover(inside: Boolean) {
        if (hovered != inside) {
            hovered = inside
            send(button, "setHidden:", if (inside) 0.toByte() else 1.toByte())
        }
    }

    fun copy(url: String) {
        val pasteboard = pointer(clazz("NSPasteboard"), "generalPasteboard")
        send(pasteboard, "clearContents")
        send(pasteboard, "setString:forType:", string(url), string("public.utf8-plain-text"))
    }
}

@Structure.FieldOrder("x", "y", "width", "height")
internal class AddressTrackingRect(
    @JvmField var x: Double = 0.0,
    @JvmField var y: Double = 0.0,
    @JvmField var width: Double = 0.0,
    @JvmField var height: Double = 0.0,
) : Structure(),
    Structure.ByValue
