package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.send
import com.sun.jna.Pointer

/** Native child ownership keeps the hover body above its owner and moves both in one AppKit operation. */
internal class MacSidebarOverlayOwner(
    private val ownerHandle: Long,
    private val childHandle: Long,
) : AutoCloseable {
    @Volatile private var closed = false

    fun attach() {
        MacToolbarRuntime.dispatch {
            if (closed) return@dispatch
            val owner = Pointer(ownerHandle)
            val child = Pointer(childHandle)
            if (!MacToolbarRuntime.isLiveWindow(owner) || !MacToolbarRuntime.isLiveWindow(child)) return@dispatch
            // AWT ownership does not guarantee AppKit child ordering for an unfocusable utility
            // dialog. NSWindowAbove also keeps ordering intact when the title bar raises the owner.
            send(owner, "addChildWindow:ordered:", child, 1L)
        }
    }

    override fun close() {
        closed = true
        MacToolbarRuntime.dispatch {
            val owner = Pointer(ownerHandle)
            val child = Pointer(childHandle)
            if (MacToolbarRuntime.isLiveWindow(child) && pointer(child, "parentWindow") == owner) {
                send(owner, "removeChildWindow:", child)
            }
        }
    }
}
