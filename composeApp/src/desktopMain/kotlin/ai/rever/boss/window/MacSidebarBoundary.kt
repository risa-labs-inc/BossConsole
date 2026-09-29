package ai.rever.boss.window

import ai.rever.boss.window.MacToolbarRuntime.clazz
import ai.rever.boss.window.MacToolbarRuntime.pointer
import ai.rever.boss.window.MacToolbarRuntime.send
import com.sun.jna.Pointer

/** Reserve the sidebar column after the traffic lights and sidebar control, as BossTerm does. */
internal object MacSidebarBoundary {
    // AppKit already reserves this leading space for the traffic-light cluster.
    private const val NATIVE_LEADING_WIDTH = 100f

    fun leadingWidth(sidebar: NativeTitleBarAction?): Float {
        val origin = sidebar?.sidebarLeading ?: 0f
        return (origin - NATIVE_LEADING_WIDTH).coerceAtLeast(0f)
    }

    fun trailingWidth(sidebar: NativeTitleBarAction?): Float {
        val width = sidebar?.sidebarWidth ?: 0f
        if (width <= 0f) return 0f
        // Traffic lights occupy sidebar width only while the sidebar starts at the window edge.
        // With a left plugin open, that inset belongs to the plugin column instead.
        val origin = sidebar?.sidebarLeading ?: 0f
        return (origin + width - leadingWidth(sidebar) - 140f).coerceAtLeast(0f)
    }

    fun create(identifier: Pointer?): Pointer {
        val item =
            checkNotNull(
                pointer(pointer(clazz("NSToolbarItem"), "alloc"), "initWithItemIdentifier:", identifier),
            )
        val view = pointer(clazz("NSView"), "new")
        send(item, "setView:", view)
        send(view, "release")
        send(item, "setNavigational:", 1.toByte())
        send(item, "setBordered:", 0.toByte())
        return item
    }

    fun update(
        item: Pointer,
        sidebar: NativeTitleBarAction?,
        leading: Boolean,
    ) {
        // The measured sidebar origin is absolute; reserve only the part beyond AppKit's
        // existing traffic-light inset, rather than adding that inset a second time.
        val width = if (leading) leadingWidth(sidebar) else trailingWidth(sidebar)
        val remaining = width.toDouble().coerceAtLeast(0.0)
        send(item, "setMinSize:", ToolbarIconSize(remaining, 1.0))
        send(item, "setMaxSize:", ToolbarIconSize(remaining, 1.0))
    }
}
