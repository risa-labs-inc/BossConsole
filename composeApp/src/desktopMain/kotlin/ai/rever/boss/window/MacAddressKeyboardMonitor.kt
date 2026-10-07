package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.clazz
import ai.rever.boss.window.MacToolbarRuntime.number
import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.send
import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

private const val ADDRESS_COMMAND = 1L shl 20
private const val ADDRESS_SHIFT = 1L shl 17
private const val ADDRESS_OTHER_MODIFIERS = (1L shl 18) or (1L shl 19)

/** Native field-editor shortcuts never reach Compose's or Chromium's keyboard handlers. */
internal fun macAddressLocalShortcut(
    key: String,
    flags: Long,
): String? {
    val modifiers = flags and (ADDRESS_COMMAND or ADDRESS_SHIFT or ADDRESS_OTHER_MODIFIERS)
    val character = key.lowercase()
    return when (modifiers) {
        ADDRESS_COMMAND -> {
            when (character) {
                "l" -> "focusAddress"
                "a" -> "selectAll:"
                "c" -> "copy:"
                "x" -> "cut:"
                "v" -> "paste:"
                "z" -> "undo:"
                else -> null
            }
        }

        ADDRESS_COMMAND or ADDRESS_SHIFT -> {
            if (character == "z") "redo:" else null
        }

        else -> {
            null
        }
    }
}

/** AppKit queue only. Ignore other windows, sheets and controls rather than borrowing their editor. */
internal fun handleMacAddressKeyEvent(event: Pointer?): Boolean {
    val flags = event?.let { number(it, "modifierFlags") } ?: 0L
    if (event == null || flags and ADDRESS_COMMAND == 0L || number(event, "type") != 10L) return false
    val key = pointer(pointer(event, "charactersIgnoringModifiers"), "UTF8String")?.getString(0).orEmpty()
    val command = macAddressLocalShortcut(key, flags)
    val eventWindow = pointer(event, "window")
    return if (command != null && eventWindow != null) {
        val owner = MacSidebarToolbarBridge.owners.values.singleOrNull { it.ownsAddressEventWindow(eventWindow) }
        owner?.addressField?.performShortcut(command) == true
    } else {
        false
    }
}

/** One local monitor, released when the last toolbar closes. It never observes another app. */
internal object MacAddressKeyboardMonitor {
    private val library = NativeLibrary.getInstance("/usr/lib/libSystem.B.dylib")
    private val descriptor =
        Memory(16).apply {
            setLong(0, 0)
            setLong(8, 32)
        }
    private var monitor: Pointer? = null

    private fun interface EventHandler : Callback {
        fun invoke(
            block: Pointer?,
            event: Pointer?,
        ): Pointer?
    }

    // Keep the callback and block descriptor alive until AppKit removes the monitor.
    @Suppress("TooGenericExceptionCaught") // Java exceptions must not unwind through AppKit's block callback.
    private val handler =
        EventHandler { _, event ->
            try {
                if (handleMacAddressKeyEvent(event)) null else event
            } catch (failure: Exception) {
                org.slf4j.LoggerFactory
                    .getLogger(MacAddressKeyboardMonitor::class.java)
                    .warn("Native address shortcut failed", failure)
                event
            }
        }

    fun install() {
        if (monitor != null) return
        Memory(32).use { literal ->
            literal.setPointer(0, library.getGlobalVariableAddress("_NSConcreteStackBlock"))
            literal.setInt(8, 0)
            literal.setInt(12, 0)
            literal.setPointer(16, CallbackReference.getFunctionPointer(handler))
            literal.setPointer(24, descriptor)
            val block = checkNotNull(library.getFunction("_Block_copy").invokePointer(arrayOf(literal)))
            try {
                monitor =
                    checkNotNull(
                        pointer(
                            clazz("NSEvent"),
                            "addLocalMonitorForEventsMatchingMask:handler:",
                            1L shl 10,
                            block,
                        ),
                    )
                send(monitor, "retain")
            } finally {
                library.getFunction("_Block_release").invokeVoid(arrayOf(block))
            }
        }
    }

    fun removeIfUnused() {
        if (MacSidebarToolbarBridge.owners.isNotEmpty()) return
        monitor?.let {
            send(clazz("NSEvent"), "removeMonitor:", it)
            send(it, "release")
        }
        monitor = null
    }
}
