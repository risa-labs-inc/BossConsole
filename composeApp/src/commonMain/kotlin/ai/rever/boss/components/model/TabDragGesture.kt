package ai.rever.boss.components.model

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.positionChange

/** A pointer handler owns its drag even when cancellation bypasses the gesture callbacks. */
internal suspend fun PointerInputScope.detectTabDragGestures(
    component: TabDraggableComponent,
    onStart: (Offset) -> Unit,
    onEnd: (TabDropResult?) -> Unit,
    sourceIndex: () -> Int? = { null },
) {
    component.withDragSession { session ->
        try {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                // Compose's mouse drag slop is only 0.125dp. Tabs can create splits on drop,
                // so use the normal touch slop for every pointer; leave small slips to click.
                val start =
                    awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        ?: return@awaitEachGesture
                if (!session.start { onStart(start.position) }) return@awaitEachGesture
                // Start at the threshold-crossing position, without applying its delta twice.
                val completed =
                    drag(start.id) { change ->
                        if (session.isOwner) {
                            session.update(change.positionChange())
                            change.consume()
                        }
                    }
                if (completed) {
                    if (session.isOwner) onEnd(session.end(sourceIndex()))
                } else if (session.cancel()) {
                    onEnd(null)
                }
            }
        } finally {
            // Teardown and callback failures terminate only this gesture and notify once.
            if (session.cancel()) onEnd(null)
        }
    }
}
