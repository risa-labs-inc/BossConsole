package ai.rever.boss.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.awt.Dialog
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities

/** Remote-input boundary only: never changes native modality or local focus behavior. */
internal object WindowInputModalBoundary {
    private val markers = ConcurrentHashMap<Window, Any>()
    private val epochs = WeakHashMap<Window, AtomicLong>()

    fun isModal(window: Window): Boolean = (window is Dialog && window.isModal) || markers.containsKey(window)

    /** Capture on EDT after ordinary policy checks; later native dispatch reads atomics only. */
    fun captureAuthority(root: Window): () -> Boolean {
        check(SwingUtilities.isEventDispatchThread())
        val epoch = epochs.getOrPut(root) { AtomicLong() }
        val admitted = epoch.get()
        return { epoch.get() == admitted }
    }

    fun register(window: Window): AutoCloseable {
        check(SwingUtilities.isEventDispatchThread())
        val token = Any()
        markers[window] = token
        invalidateAncestors(window)
        val listener =
            object : ComponentAdapter() {
                override fun componentShown(event: ComponentEvent) = invalidateAncestors(window)

                override fun componentHidden(event: ComponentEvent) = invalidateAncestors(window)
            }
        window.addComponentListener(listener)
        return AutoCloseable {
            check(SwingUtilities.isEventDispatchThread())
            window.removeComponentListener(listener)
            if (markers[window] === token) {
                markers.remove(window)
                invalidateAncestors(window)
            }
        }
    }

    private fun invalidateAncestors(window: Window) {
        check(SwingUtilities.isEventDispatchThread())
        generateSequence(window) { it.owner }.forEach { ancestor -> epochs[ancestor]?.incrementAndGet() }
    }
}

/** Composition owns the marker exactly as it owns the modeless native dialog. */
@Composable
internal fun RegisterLogicalModalBoundary(window: Window) {
    DisposableEffect(window) {
        val registration = WindowInputModalBoundary.register(window)
        onDispose { registration.close() }
    }
}
