package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.clazz
import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.selector
import ai.rever.boss.window.MacToolbarRuntime.send
import ai.rever.boss.window.MacToolbarRuntime.string
import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.util.concurrent.ConcurrentHashMap

/** Native ownership and resize notifications keep the body above and anchored to its owner. */
internal class MacSidebarOverlayOwner(
    private val ownerHandle: Long,
    private val childHandle: Long,
) : AutoCloseable {
    @Volatile private var closed = false
    private var observer: Pointer? = null
    private var geometry: SidebarOverlayAnchor? = null

    fun attach() {
        MacToolbarRuntime.dispatch {
            if (closed) return@dispatch
            val owner = Pointer(ownerHandle)
            val child = Pointer(childHandle)
            if (!MacToolbarRuntime.isLiveWindow(owner) || !MacToolbarRuntime.isLiveWindow(child)) return@dispatch
            send(owner, "addChildWindow:ordered:", child, 1L)
            if (observer == null) {
                observer = SidebarOverlayResizeBridge.observe(owner, this)
            }
            refreshGeometry()
        }
    }

    fun updateGeometry(anchor: SidebarOverlayAnchor) {
        MacToolbarRuntime.dispatch {
            if (closed) return@dispatch
            geometry = anchor
            refreshGeometry()
        }
    }

    /** Called synchronously on AppKit's resize notification, before AWT can report the new bounds. */
    fun refreshGeometry() {
        val anchor = geometry
        if (closed || anchor == null) return
        val owner = Pointer(ownerHandle)
        val child = Pointer(childHandle)
        if (!MacToolbarRuntime.isLiveWindow(owner) || !MacToolbarRuntime.isLiveWindow(child)) return
        val frame =
            Memory(32).use { bytes ->
                send(pointer(owner, "valueForKey:", string("frame")), "getValue:size:", bytes, 32L)
                DoubleArray(4) { bytes.getDouble(it * 8L) }
            }
        val rect =
            SidebarOverlayRect(
                frame[0] + anchor.left,
                frame[1] + anchor.bottom,
                anchor.width.coerceAtLeast(1.0),
                (frame[3] - anchor.top - anchor.bottom).coerceAtLeast(1.0),
            )
        // NSWindow child ownership follows translations, but leaves the child fixed on resize.
        // Set only our owned dialog's frame; never feed stale AWT screen coordinates back to it.
        send(child, "setFrame:display:", rect, 0.toByte())
    }

    override fun close() {
        closed = true
        MacToolbarRuntime.dispatch {
            observer?.let { SidebarOverlayResizeBridge.remove(it) }
            observer = null
            val owner = Pointer(ownerHandle)
            val child = Pointer(childHandle)
            if (MacToolbarRuntime.isLiveWindow(child) && pointer(child, "parentWindow") == owner) {
                send(owner, "removeChildWindow:", child)
            }
        }
    }
}

internal data class SidebarOverlayAnchor(
    val left: Double,
    val top: Double,
    val bottom: Double,
    val width: Double,
)

@Structure.FieldOrder("x", "y", "width", "height")
internal class SidebarOverlayRect(
    @JvmField var x: Double = 0.0,
    @JvmField var y: Double = 0.0,
    @JvmField var width: Double = 0.0,
    @JvmField var height: Double = 0.0,
) : Structure(),
    Structure.ByValue

private object SidebarOverlayResizeBridge {
    private val owners = ConcurrentHashMap<Long, MacSidebarOverlayOwner>()

    private fun interface ResizeCallback : Callback {
        fun invoke(
            self: Pointer?,
            command: Pointer?,
            notification: Pointer?,
        )
    }

    // Strongly retained for the lifetime of the registered Objective-C method.
    private val callback = ResizeCallback { self, _, _ -> refreshSafely(self) }
    private val bridgeClass by lazy {
        val objc = MacToolbarRuntime.objc
        val type =
            objc
                .getFunction("objc_allocateClassPair")
                .invokePointer(arrayOf(clazz("NSObject"), "BossConsoleSidebarOverlayResizeObserver", 0L))
        objc.getFunction("class_addMethod").invokeInt(
            arrayOf(type, selector("windowChanged:"), CallbackReference.getFunctionPointer(callback), "v@:@"),
        )
        objc.getFunction("objc_registerClassPair").invokeVoid(arrayOf(type))
        type
    }

    fun observe(
        window: Pointer,
        owner: MacSidebarOverlayOwner,
    ): Pointer? {
        val observer = pointer(bridgeClass, "new") ?: return null
        owners[Pointer.nativeValue(observer)] = owner
        val center = pointer(clazz("NSNotificationCenter"), "defaultCenter")
        val change = selector("windowChanged:")
        listOf("NSWindowDidResizeNotification", "NSWindowDidMoveNotification").forEach { name ->
            send(center, "addObserver:selector:name:object:", observer, change, string(name), window)
        }
        return observer
    }

    fun remove(observer: Pointer) {
        send(pointer(clazz("NSNotificationCenter"), "defaultCenter"), "removeObserver:", observer)
        owners.remove(Pointer.nativeValue(observer))
        send(observer, "release")
    }

    @Suppress("TooGenericExceptionCaught")
    private fun refreshSafely(observer: Pointer?) {
        try {
            owners[Pointer.nativeValue(observer)]?.refreshGeometry()
        } catch (error: Exception) {
            org.slf4j.LoggerFactory
                .getLogger(MacSidebarOverlayOwner::class.java)
                .warn("Sidebar resize notification failed", error)
        }
    }
}
