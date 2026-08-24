package ai.rever.boss.components.window_panel.components.main_window_panels

import ai.rever.boss.components.common.rememberFaviconLoader
import ai.rever.boss.components.model.TabDraggableComponent
import ai.rever.boss.components.model.TabDropResult
import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.components.overlays.HoverTooltipBox
import ai.rever.boss.components.overlays.TooltipPlacement
import ai.rever.boss.components.overlays.contextMenu
import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Side of the chip's hit target. The icon inside is smaller; the rest is margin worth clicking. */
internal val FAVICON_CHIP_SIZE = 20.dp

/** The icon itself. */
private val FAVICON_SIZE = 14.dp

/** How much a tab that is not current is faded, so the current one reads without a marker. */
private const val INACTIVE_ICON_ALPHA = 0.55f

/**
 * One tab as a favicon and nothing else.
 *
 * The shared piece behind both favicon-only rows: the strip at the top of a pane, and the row
 * standing in for a collapsed pane's hidden tabs. Neither has room for a title, so the tooltip is
 * the only thing that names the tab - which makes it load-bearing rather than decoration, and is
 * why it is [HoverTooltipBox] (which survives being drawn over a browser's native surface) rather
 * than a plain TooltipArea.
 *
 * A tab with no favicon falls back to its type icon, and one with neither to a dot. Something is
 * always drawn: a gap in a row of favicons reads as a missing tab rather than a plain one.
 */
@Composable
internal fun TabFaviconChip(
    tab: TabInfo,
    isActive: Boolean,
    onClick: () -> Unit,
    size: Dp = FAVICON_CHIP_SIZE,
    // END rather than TOP: a strip at the top of a pane has the window's own chrome above it,
    // and a tooltip placed there would open off the pane entirely.
    placement: TooltipPlacement = TooltipPlacement.END,
    /**
     * The tab's own right-click menu, where the surface showing it owes one.
     *
     * The collapsed rail does: taking the labels away must not take the actions with them, which
     * is the rail's whole contract. The favicon rows inside an expanded bar do not - the full
     * row for that tab is a few pixels away and already carries it.
     */
    contextMenuItems: List<ContextMenuItem> = emptyList(),
    /**
     * The drag system, so a tab can be picked up from here.
     *
     * The chip does NOT register its bounds. A tab already has bounds registered by its row in the
     * window bar, under the same "panelId:tabId" key, and a second surface writing that key would
     * have reorder computing an insert position from whichever of the two laid out last. So this
     * starts drags and never receives them: the bar's rows and the panes' drop zones stay the
     * only places a tab can land.
     */
    tabDragComponent: TabDraggableComponent? = null,
    /** The panel this tab belongs to, for the drag. */
    panelId: String? = null,
    /** The tab's index in that panel, for the drag. */
    tabIndex: Int = -1,
    /** The drop, once the pointer is released. */
    onDragEnd: (TabDropResult?) -> Unit = {},
) {
    val colors = BossTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val loaded = rememberFaviconLoader(tab)

    val dragEnabled = tabDragComponent != null && panelId != null && tabIndex >= 0
    // Where this chip sits in the window, so a drag can start from an absolute point. The ghost
    // follows the pointer, and the pointer is in window coordinates.
    var windowPosition by remember { mutableStateOf(Offset.Zero) }
    val icon = loaded ?: tab.tabIcon

    val background =
        when {
            isActive -> colors.signal.copy(alpha = ACTIVE_CHIP_ALPHA)
            hovered -> colors.raised
            else -> Color.Transparent
        }

    HoverTooltipBox(
        text = tab.title,
        placement = placement,
        modifier = Modifier.size(size),
    ) {
        Box(
            modifier =
                Modifier
                    .size(size)
                    .clip(RoundedCornerShape(4.dp))
                    .background(background)
                    .hoverable(interactionSource)
                    .then(if (contextMenuItems.isEmpty()) Modifier else Modifier.contextMenu(items = contextMenuItems))
                    .onGloballyPositioned { coordinates -> windowPosition = coordinates.positionInWindow() }
                    .then(
                        if (!dragEnabled) {
                            Modifier
                        } else {
                            Modifier.pointerInput(tab, panelId, tabIndex) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        tabDragComponent.startDragging(
                                            tabInfo = tab,
                                            panelId = panelId,
                                            index = tabIndex,
                                            startPosition = windowPosition + offset,
                                        )
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        tabDragComponent.updateDrag(dragAmount)
                                    },
                                    // Cleaned up first either way: a result that throws must not
                                    // leave a ghost stuck to the pointer.
                                    onDragEnd = { onDragEnd(tabDragComponent.endDrag()) },
                                    onDragCancel = { tabDragComponent.cancelDrag() },
                                )
                            }
                        },
                    ).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            TabGlyph(icon = icon, tab = tab, isActive = isActive)
        }
    }
}

/**
 * The favicon, the tab's type icon, or a dot - in that order of preference.
 *
 * Something is always drawn. A gap in a row of favicons reads as a tab that failed to render
 * rather than one that simply has no icon.
 */
@Composable
private fun TabGlyph(
    icon: TabIcon?,
    tab: TabInfo,
    isActive: Boolean,
) {
    val colors = BossTheme.colors
    val painter =
        when {
            icon != null -> icon.asPainter()
            tab.icon != null -> rememberVectorPainter(tab.icon!!)
            else -> null
        }
    val dim = Modifier.alpha(if (isActive) 1f else INACTIVE_ICON_ALPHA).size(FAVICON_SIZE)

    when {
        // A real favicon keeps its own colours; tinting it would turn every site's mark grey.
        icon is TabIcon.Image && painter != null -> {
            Image(painter = painter, contentDescription = tab.title, modifier = dim)
        }

        painter != null -> {
            Icon(
                painter = painter,
                contentDescription = tab.title,
                tint = (icon as? TabIcon.Vector)?.tint ?: colors.textSecondary,
                modifier = dim,
            )
        }

        else -> {
            Box(
                modifier =
                    Modifier
                        .alpha(if (isActive) 1f else INACTIVE_ICON_ALPHA)
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(colors.textSecondary),
            )
        }
    }
}

/** Fill behind the current tab's chip. Enough to find, quiet enough for a row of twenty. */
private const val ACTIVE_CHIP_ALPHA = 0.3f
