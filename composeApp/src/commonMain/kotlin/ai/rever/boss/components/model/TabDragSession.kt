package ai.rever.boss.components.model

import ai.rever.boss.plugin.api.TabInfo
import androidx.compose.ui.geometry.Offset

/** Owns only the drag started by one pointer-input coroutine, even if its source is replaced. */
internal class TabDragSession(
    private val component: TabDraggableComponent,
) {
    private var owned: DraggingTabInfo? = null

    private val isOwner: Boolean
        get() = owned != null && component.draggingTab === owned

    fun start(
        tab: TabInfo,
        panelId: String,
        index: Int,
        position: Offset,
    ) {
        if (component.isDragging) return
        component.startDragging(tab, panelId, index, position)
        owned = component.draggingTab
    }

    fun update(delta: Offset) {
        if (isOwner) component.updateDrag(delta)
    }

    fun end(): TabDropResult? {
        if (!isOwner) return null
        owned = null
        return component.endDrag()
    }

    fun cancel() {
        if (isOwner) component.cancelDrag()
        owned = null
    }
}

/** Gesture callbacks do not cover coroutine cancellation or exceptions in the gesture handler. */
internal suspend fun TabDraggableComponent.withDragSession(block: suspend (TabDragSession) -> Unit) {
    val session = TabDragSession(this)
    try {
        block(session)
    } finally {
        session.cancel()
    }
}
