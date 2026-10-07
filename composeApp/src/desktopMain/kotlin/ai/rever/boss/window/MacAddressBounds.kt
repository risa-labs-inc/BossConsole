package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.clazz
import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.selector
import ai.rever.boss.window.MacToolbarRuntime.send
import ai.rever.boss.window.MacToolbarRuntime.string
import androidx.compose.ui.unit.IntRect
import com.sun.jna.Memory
import com.sun.jna.Pointer
import kotlin.math.roundToInt

/** NSInvocation avoids architecture-specific objc_msgSend structure return conventions. */
@Suppress("ReturnCount") // Reject detached views or unavailable native conversion before reading coordinates.
internal fun nativeAddressBounds(
    view: Pointer,
    window: Pointer,
): IntRect? {
    val viewWindow = pointer(view, "window") ?: return null
    val signature = pointer(view, "methodSignatureForSelector:", selector("convertRect:toView:"))
    val invocation = pointer(clazz("NSInvocation"), "invocationWithMethodSignature:", signature) ?: return null
    return Memory(32).use { rect ->
        Memory(
            com.sun.jna.Native.POINTER_SIZE
                .toLong(),
        ).use { nilView ->
            nilView.clear()
            send(pointer(view, "valueForKey:", string("bounds")), "getValue:size:", rect, 32L)
            send(invocation, "setTarget:", view)
            send(invocation, "setSelector:", selector("convertRect:toView:"))
            send(invocation, "setArgument:atIndex:", rect, 2L)
            send(invocation, "setArgument:atIndex:", nilView, 3L)
            send(invocation, "invoke")
            send(invocation, "getReturnValue:", rect)
            if (!windowRectToScreen(viewWindow, rect)) return null
            val screenX = rect.getDouble(0)
            val y = rect.getDouble(8)
            val width = rect.getDouble(16)
            val height = rect.getDouble(24)
            send(pointer(window, "valueForKey:", string("frame")), "getValue:size:", rect, 32L)
            val x = screenX - rect.getDouble(0)
            val top = rect.getDouble(8) + rect.getDouble(24) - y - height
            IntRect(x.roundToInt(), top.roundToInt(), (x + width).roundToInt(), (top + height).roundToInt())
        }
    }
}

private fun windowRectToScreen(
    window: Pointer,
    rect: Memory,
): Boolean {
    val method = selector("convertRectToScreen:")
    val signature = pointer(window, "methodSignatureForSelector:", method)
    val invocation = pointer(clazz("NSInvocation"), "invocationWithMethodSignature:", signature) ?: return false
    send(invocation, "setTarget:", window)
    send(invocation, "setSelector:", method)
    send(invocation, "setArgument:atIndex:", rect, 2L)
    send(invocation, "invoke")
    send(invocation, "getReturnValue:", rect)
    return true
}
