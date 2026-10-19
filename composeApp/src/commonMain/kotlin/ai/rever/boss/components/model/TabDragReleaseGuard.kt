package ai.rever.boss.components.model

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.awt.AWTEvent
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.InputEvent
import java.awt.event.MouseEvent

/** Recover when a native child or ghost window receives the release instead of the source. */
@Composable
internal fun TabDragReleaseGuard(component: TabDraggableComponent) {
    if (!component.isDragging) return
    DisposableEffect(component) {
        val toolkit = Toolkit.getDefaultToolkit()
        val listener =
            AWTEventListener { event ->
                if (event is MouseEvent && endsTabDrag(event.id, event.button, event.modifiersEx)) {
                    val dragging = component.draggingTab ?: return@AWTEventListener
                    // Let the source process the release and deliver its drop first. Only an
                    // orphaned session is cancelled, never a new drag started in the meantime.
                    EventQueue.invokeLater {
                        if (component.draggingTab === dragging) component.cancelDrag()
                    }
                }
            }
        toolkit.addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK)
        onDispose { toolkit.removeAWTEventListener(listener) }
    }
}

internal fun endsTabDrag(
    eventId: Int,
    button: Int,
    modifiers: Int,
): Boolean =
    (eventId == MouseEvent.MOUSE_RELEASED && button == MouseEvent.BUTTON1) ||
        (eventId == MouseEvent.MOUSE_MOVED && modifiers and InputEvent.BUTTON1_DOWN_MASK == 0)
