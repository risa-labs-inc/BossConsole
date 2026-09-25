package ai.rever.boss.components.model

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope

/** A pointer handler owns its drag even when cancellation bypasses the gesture callbacks. */
internal suspend fun PointerInputScope.detectTabDragGestures(
    component: TabDraggableComponent,
    onStart: (Offset) -> Unit,
    onEnd: (TabDropResult?) -> Unit,
    sourceIndex: () -> Int? = { null },
) {
    var ownedDrag: DraggingTabInfo? = null

    fun ownsDrag(): Boolean = ownedDrag != null && component.draggingTab === ownedDrag

    try {
        detectDragGestures(
            onDragStart = { offset ->
                if (!component.isDragging) {
                    try {
                        onStart(offset)
                    } finally {
                        ownedDrag = component.draggingTab
                    }
                }
            },
            onDrag = { change, amount ->
                if (ownsDrag()) {
                    change.consume()
                    component.updateDrag(amount)
                }
            },
            onDragEnd = {
                if (ownsDrag()) {
                    val result = component.endDrag(sourceIndex())
                    ownedDrag = null
                    onEnd(result)
                }
                ownedDrag = null
            },
            onDragCancel = {
                if (ownsDrag()) {
                    component.cancelDrag()
                    ownedDrag = null
                    onEnd(null)
                }
                ownedDrag = null
            },
        )
    } finally {
        // Removal, pointerInput key changes and exceptions do not necessarily call onDragCancel.
        // Identity prevents an obsolete handler from cancelling a replacement drag.
        if (ownsDrag()) {
            component.cancelDrag()
            ownedDrag = null
            onEnd(null)
        }
    }
}
