package ai.rever.boss.window

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.util.concurrent.ConcurrentHashMap

/** AppKit calls must use its main queue, not the Swing EDT. Loaded only on macOS. */
internal object MacToolbarRuntime {
    val objc: NativeLibrary = NativeLibrary.getInstance("objc")
    private val message = objc.getFunction("objc_msgSend")
    private val dispatchLibrary = NativeLibrary.getInstance("/usr/lib/libSystem.B.dylib")
    private val mainQueue = dispatchLibrary.getGlobalVariableAddress("_dispatch_main_q")
    private val dispatchApi = Native.load("/usr/lib/libSystem.B.dylib", DispatchApi::class.java)
    private val pending = ConcurrentHashMap.newKeySet<MainQueueCallback>()
    private val selectors = ConcurrentHashMap<String, Pointer>()

    // This is a native callback boundary: Java exceptions must not unwind into AppKit.
    @Suppress("TooGenericExceptionCaught")
    fun dispatch(action: () -> Unit) {
        lateinit var callback: MainQueueCallback
        callback =
            MainQueueCallback {
                val pool = pointer(clazz("NSAutoreleasePool"), "new")
                try {
                    action()
                } catch (error: Exception) {
                    org.slf4j.LoggerFactory
                        .getLogger(MacToolbarRuntime::class.java)
                        .warn("AppKit toolbar operation failed", error)
                } finally {
                    send(pool, "drain")
                    pending.remove(callback)
                }
            }
        pending.add(callback)
        try {
            dispatchApi.dispatch_async_f(mainQueue, null, callback)
        } catch (error: Exception) {
            pending.remove(callback)
            throw error
        }
    }

    fun clazz(name: String): Pointer? = objc.getFunction("objc_getClass").invokePointer(arrayOf(name))

    fun selector(name: String): Pointer =
        selectors.computeIfAbsent(name) {
            objc.getFunction("sel_registerName").invokePointer(arrayOf(it))
        }

    fun string(value: String): Pointer? = pointer(clazz("NSString"), "stringWithUTF8String:", value)

    // Fixed Function arguments preserve the Apple Silicon ABI; a variadic JNA declaration does not.
    fun pointer(
        receiver: Pointer?,
        name: String,
        vararg args: Any?,
    ): Pointer? = message.invokePointer(arrayOf(receiver, selector(name), *args))

    fun number(
        receiver: Pointer?,
        name: String,
        vararg args: Any?,
    ): Long = message.invokeLong(arrayOf(receiver, selector(name), *args))

    fun send(
        receiver: Pointer?,
        name: String,
        vararg args: Any?,
    ) {
        message.invokeVoid(arrayOf(receiver, selector(name), *args))
    }

    fun isLiveWindow(window: Pointer): Boolean {
        val windows = pointer(pointer(clazz("NSApplication"), "sharedApplication"), "windows")
        return (0 until number(windows, "count")).any {
            pointer(windows, "objectAtIndex:", it) == window
        }
    }

    fun supports(
        receiver: Pointer?,
        name: String,
    ): Boolean = message.invokeInt(arrayOf(receiver, selector("respondsToSelector:"), selector(name))) != 0

    /** KVC boxes NSRect, avoiding Intel versus ARM structure-return calling conventions. */
    fun headerHeight(window: Pointer): Double? {
        fun rect(key: String): DoubleArray? {
            val value = pointer(window, "valueForKey:", string(key)) ?: return null
            return Memory(32).use { bytes ->
                send(value, "getValue:size:", bytes, 32L)
                DoubleArray(4) { bytes.getDouble(it * 8L) }
            }
        }
        val frame = rect("frame")
        val layout = rect("contentLayoutRect")
        if (frame == null || layout == null) return null
        return (frame[3] - layout[1] - layout[3]).takeIf { it.isFinite() && it >= 0.0 }
    }

    private fun interface MainQueueCallback : Callback {
        fun invoke(context: Pointer?)
    }

    private interface DispatchApi : Library {
        @Suppress("FunctionNaming", "ktlint:standard:function-naming")
        fun dispatch_async_f(
            queue: Pointer,
            context: Pointer?,
            callback: MainQueueCallback,
        )
    }
}
