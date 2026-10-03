package ai.rever.boss.sharing

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

/** Read-only state for one supplied HWND. Never searches for or activates another window. */
internal data class WindowsCaptureWindowState(
    val live: Boolean,
    val processId: Long,
    val rootHandle: Long,
    val visible: Boolean,
    val minimized: Boolean,
)

/** Native minimization can precede the AWT ICONIFIED event. Recheck identity after observing it. */
internal fun exactOwnedWindowsWindowMinimized(
    nativeHandle: Long,
    processId: Long,
    readState: (Long) -> WindowsCaptureWindowState,
): Boolean {
    if (nativeHandle == 0L || processId !in 1..0xffffffffL) return false

    fun valid(state: WindowsCaptureWindowState): Boolean =
        state.live && state.visible && state.minimized &&
            state.processId == processId && state.rootHandle == nativeHandle
    return valid(readState(nativeHandle)) && valid(readState(nativeHandle))
}

/** Loaded only from the Windows branch of the capture lifecycle check. */
internal object WindowsAppCaptureState {
    private val api: WindowApi by lazy {
        Native.load("user32", WindowApi::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }

    fun minimized(nativeHandle: Long): Boolean =
        exactOwnedWindowsWindowMinimized(
            nativeHandle,
            ProcessHandle.current().pid(),
            ::readState,
        )

    private fun readState(nativeHandle: Long): WindowsCaptureWindowState {
        val window = Pointer(nativeHandle)
        val owner = IntByReference()
        val thread = api.GetWindowThreadProcessId(window, owner)
        return WindowsCaptureWindowState(
            live = thread != 0 && api.IsWindow(window) != 0,
            processId = Integer.toUnsignedLong(owner.value),
            rootHandle = Pointer.nativeValue(api.GetAncestor(window, 2)), // GA_ROOT
            visible = api.IsWindowVisible(window) != 0,
            minimized = api.IsIconic(window) != 0,
        )
    }

    @Suppress("FunctionNaming", "ktlint:standard:function-naming") // Fixed Win32 export names.
    internal interface WindowApi : StdCallLibrary {
        fun IsWindow(window: Pointer): Int

        fun GetWindowThreadProcessId(
            window: Pointer,
            processId: IntByReference,
        ): Int

        fun GetAncestor(
            window: Pointer,
            flags: Int,
        ): Pointer?

        fun IsWindowVisible(window: Pointer): Int

        fun IsIconic(window: Pointer): Int
    }
}
