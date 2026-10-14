package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.clazz
import ai.rever.boss.window.MacToolbarRuntime.number
import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.selector
import ai.rever.boss.window.MacToolbarRuntime.send
import ai.rever.boss.window.MacToolbarRuntime.string
import com.sun.jna.Pointer

/** Actual AppKit traffic lights; never replace or modify the system-owned originals. */
internal object MacSharingWindowControls {
    const val GROUP = "sharing_window_controls"

    val selectors =
        linkedMapOf(
            "sharing_window_close" to "performClose:",
            "sharing_window_minimize" to "performMiniaturize:",
            "sharing_window_fullscreen" to "toggleFullScreen:",
        )

    private val controls =
        listOf(
            NativeTitleBarAction("sharing_window_close", "Close shared window") {},
            NativeTitleBarAction("sharing_window_minimize", "Minimize shared window") {},
            NativeTitleBarAction("sharing_window_fullscreen", "Toggle shared window fullscreen") {},
        )

    fun actions(visible: Boolean): List<NativeTitleBarAction> = if (visible) controls else emptyList()

    fun buttonIndex(id: String): Long? =
        selectors.keys
            .indexOf(id)
            .takeIf { it >= 0 }
            ?.toLong()

    fun shouldShow(
        window: Pointer,
        sharing: Boolean,
        transitioning: Boolean,
    ): Boolean {
        val hiddenChrome =
            number(window, "isMiniaturized") != 0L || number(window, "styleMask") and (1L shl 14) != 0L
        val inactive = !sharing || transitioning
        if (inactive || number(window, "isVisible") == 0L || hiddenChrome) {
            return false
        }
        // Inactive/grey buttons are still visible. Fullscreen chrome hiding is excluded above.
        return (0L..2L).all { index ->
            val button = pointer(window, "standardWindowButton:", index)
            button != null && pointer(button, "window") == window &&
                number(button, "isHiddenOrHasHiddenAncestor") != 0L
        }
    }

    @Suppress("TooGenericExceptionCaught") // Release the owned native item if any JNA setup call fails.
    fun create(
        identifier: Pointer?,
        window: Pointer,
        delegate: Pointer?,
        tag: (Pointer, String) -> Unit,
    ): Pointer {
        val item =
            checkNotNull(pointer(pointer(clazz("NSToolbarItem"), "alloc"), "initWithItemIdentifier:", identifier))
        val view = checkNotNull(pointer(clazz("NSStackView"), "new"))
        try {
            send(view, "setOrientation:", 0L)
            send(view, "setAlignment:", 10L) // NSLayoutAttributeCenterY.
            send(view, "setSpacing:", 6.0)
            send(view, "setFrameSize:", ToolbarIconSize(54.0, 22.0))
            controls.forEachIndexed { index, action ->
                val button =
                    checkNotNull(
                        pointer(
                            clazz("NSWindow"),
                            "standardWindowButton:forStyleMask:",
                            index.toLong(),
                            number(window, "styleMask"),
                        ),
                    )
                // Retain AppKit's cell, dimensions and hover drawing. Dispatch through the same
                // scoped window actions for local and authenticated remote input.
                tag(button, action.id)
                send(button, "setTarget:", delegate)
                send(button, "setAction:", selector("activate:"))
                send(button, "setToolTip:", string(action.label))
                send(button, "setAccessibilityLabel:", string(action.label))
                send(view, "addArrangedSubview:", button)
            }
            send(item, "setView:", view)
            send(item, "setLabel:", string("Shared window controls"))
            send(item, "setBordered:", 0.toByte())
            send(item, "setNavigational:", 1.toByte())
            send(item, "setVisibilityPriority:", 1000L)
            send(item, "setMinSize:", ToolbarIconSize(54.0, 22.0))
            send(item, "setMaxSize:", ToolbarIconSize(54.0, 22.0))
            return item
        } catch (failure: Exception) {
            send(item, "release")
            throw failure
        } finally {
            send(view, "release")
        }
    }
}
