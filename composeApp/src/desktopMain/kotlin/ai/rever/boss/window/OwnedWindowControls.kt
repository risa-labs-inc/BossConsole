package ai.rever.boss.window

import ai.rever.boss.sharing.onEdt
import androidx.compose.ui.awt.ComposeWindow
import java.awt.Window
import javax.swing.SwingUtilities

/** Semantic operations only: no OS input, arbitrary selectors, or capture-consent callbacks. */
internal class OwnedWindowControlEntry(
    private val nativeHandle: Long,
    private val currentHandle: () -> Long?,
    private val handlers: Map<String, () -> Unit>,
) {
    private var retired = false

    fun current(): Boolean = !retired && nativeHandle != 0L && currentHandle() == nativeHandle

    fun capabilities(): List<String> = if (current()) handlers.keys.filter { it in ACTIONS } else emptyList()

    fun perform(
        action: String,
        deadline: Long,
        authorized: () -> Boolean,
    ): Boolean {
        check(SwingUtilities.isEventDispatchThread())
        val callback = handlers[action]?.takeIf { action in ACTIONS } ?: return false
        val allowed = authorized() && current() && System.currentTimeMillis() < deadline
        if (allowed) callback()
        return allowed
    }

    fun retire() {
        retired = true
    }

    companion object {
        val ACTIONS = setOf("restore", "exit-fullscreen", "minimize", "maximize", "unmaximize", "close")
    }
}

/** Registration lifetime is exactly one live BossWindow; handles never locate another window. */
internal object OwnedWindowControls {
    private data class Registration(
        val id: String,
        val entry: OwnedWindowControlEntry,
    )

    private val windows = mutableMapOf<Window, Registration>()

    fun register(
        windowId: String,
        window: ComposeWindow,
        handlers: Map<String, () -> Unit>,
    ): AutoCloseable =
        onEdt {
            val entry =
                OwnedWindowControlEntry(
                    window.windowHandle,
                    { window.takeIf { it.isDisplayable }?.windowHandle },
                    handlers.toMap(),
                )
            val registration = Registration(windowId, entry)
            windows.put(window, registration)?.entry?.retire()
            AutoCloseable {
                onEdt {
                    entry.retire()
                    if (windows[window] === registration) windows.remove(window)
                }
            }
        }

    fun capabilities(windowId: String): List<String> =
        onEdt {
            windows.values
                .singleOrNull { it.id == windowId }
                ?.entry
                ?.capabilities()
                .orEmpty()
        }

    fun supports(
        window: Window,
        action: String,
    ): Boolean {
        check(SwingUtilities.isEventDispatchThread())
        return action in windows[window]?.entry?.capabilities().orEmpty()
    }

    fun perform(
        window: Window,
        action: String,
        deadline: Long,
        authorized: () -> Boolean,
    ): Boolean {
        check(SwingUtilities.isEventDispatchThread())
        return windows[window]?.entry?.perform(action, deadline, authorized) == true
    }
}
