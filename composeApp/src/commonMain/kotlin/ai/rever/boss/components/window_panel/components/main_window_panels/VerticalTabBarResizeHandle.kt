package ai.rever.boss.components.window_panel.components.main_window_panels

import ai.rever.boss.components.sidebar.sidebarResizePreview
import ai.rever.boss.components.sidebar.sidebarResizeStartWidth
import ai.rever.boss.platform.CursorUtil.cursorForHorizontalResize
import ai.rever.boss.window.TabBarVerticalWidthRange
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * How wide a band of the bar's trailing edge answers to a resize drag.
 *
 * An OVERLAY on the bar, not a strip beside it. The first version was a 6dp painted strip laid out
 * between the bar and the content, which meant the bar's contents ended 6dp short of the boundary
 * where they used to end 1dp short - a margin down the bar's right edge that nobody asked for.
 *
 * It takes no layout width and covers the trailing 10dp inside the sidebar, making the resize
 * cursor and drag easier to acquire without adding a visible strip. It stays inside the BAR because
 * JxBrowser composites its surface above the Compose scene and a band over a browser pane would
 * never see the pointer.
 */
private val RESIZE_BAND = 10.dp

@Composable
internal fun BoxScope.VerticalTabBarResizeHandle(
    enabled: Boolean,
    currentWidth: Float,
    onPreview: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    onCancel: () -> Unit,
) {
    if (!enabled) return

    // rememberUpdatedState, because the gesture coroutine outlives the composition that started
    // it: a drag begun before a recomposition would otherwise go on reporting to the callbacks
    // captured when it started, and go on measuring from a width that has since moved.
    val latestWidth by rememberUpdatedState(currentWidth)
    val latestPreview by rememberUpdatedState(onPreview)
    val latestCommit by rememberUpdatedState(onCommit)
    val latestCancel by rememberUpdatedState(onCancel)

    Box(
        modifier =
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(RESIZE_BAND)
                .cursorForHorizontalResize()
                // pointerInput(Unit), so the gesture is not restarted by the width changing under
                // it - which it does on every frame of the drag this block reports.
                .pointerInput(Unit) {
                    var requestedWidth = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { requestedWidth = sidebarResizeStartWidth(latestWidth) },
                        onDragEnd = { latestCommit(requestedWidth) },
                        onDragCancel = { latestCancel() },
                    ) { change, amount ->
                        change.consume()
                        requestedWidth += amount.toDp().value
                        latestPreview(sidebarResizePreview(requestedWidth))
                    }
                },
    )
}

/** Persisted expanded widths share the appearance slider range; drag previews may be narrower. */
internal fun clampBarWidth(dp: Float): Float = dp.coerceIn(TabBarVerticalWidthRange)
