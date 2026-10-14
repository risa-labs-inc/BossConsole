package ai.rever.boss.components.model

import ai.rever.boss.plugin.api.TabInfo
import androidx.compose.ui.geometry.Offset

/** Owns only the drag started by one pointer-input coroutine, even if its source is replaced. */
internal class TabDragSession(
    private val component: TabDraggableComponent,
) {
    private var owned: DraggingTabInfo? = null

    val isOwner: Boolean
        get() = owned != null && component.draggingTab === owned

    fun start(
        tab: TabInfo,
        panelId: String,
        index: Int,
        position: Offset,
    ): Boolean = start { component.startDragging(tab, panelId, index, position) }

    fun start(onStart: () -> Unit): Boolean {
        if (component.isDragging) return false
        try {
            onStart()
        } finally {
            owned = component.draggingTab
        }
        return isOwner
    }

    fun update(delta: Offset) {
        if (isOwner) component.updateDrag(delta)
    }

    fun end(sourceIndex: Int? = null): TabDropResult? {
        if (!isOwner) return null
        val result = component.endDrag(sourceIndex)
        owned = null
        return result
    }

    fun cancel(): Boolean {
        val cancelled = isOwner
        if (cancelled) component.cancelDrag()
        owned = null
        return cancelled
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
