package ai.rever.boss.components.window_panel.components.main_window_panels

import ai.rever.boss.components.common.rememberFaviconLoader
import ai.rever.boss.components.model.TabDraggableComponent
import ai.rever.boss.components.model.TabDropResult
import ai.rever.boss.components.model.withDragSession
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
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

/** The cross's glyph. */
private val CLOSE_ICON_SIZE = 11.dp

/** Corner radius of a chip, and of the pill a chip and its cross make together. */
private val CHIP_RADIUS = 4.dp

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
    /**
     * Closes this tab, revealed as a cross beside the favicon while the pointer is on it.
     *
     * Null where a surface should not offer one. The collapsed RAIL passes null: it is a column
     * a few pixels wide standing in for the whole bar, and a close target that appears there
     * under a pointer on its way somewhere else would be closing tabs by accident.
     */
    onClose: (() -> Unit)? = null,
) {
    val colors = BossTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val loaded = rememberFaviconLoader(tab)

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

    // The cross is revealed on hover and takes real width while it is there, so the chips to its
    // right shift over. That is deliberate rather than tolerated: a 20dp chip has no room to
    // overlay a target anyone could hit, and reserving the width permanently would cost the strip
    // about a third of the tabs it can show - the density is the whole reason the strip exists.
    // The pointer is over the favicon when the cross appears to its RIGHT, so the chip under the
    // pointer never moves out from under it.
    // Always shown, not revealed on hover. A cross that appears under the pointer is a cross you
    // cannot see before you go looking for it: closing a tab from the strip meant hovering each
    // chip to find out whether it could be closed at all. It also made the chip change WIDTH on
    // hover, so the strip reflowed under the pointer.
    //
    // The cost is real and accepted: every chip is now wider by the cross, so fewer fit before the
    // strip scrolls. A pane with many tabs is exactly where closing one from here is most useful.
    val showClose = onClose != null

    HoverTooltipBox(
        text = tab.title,
        placement = placement,
        // Hover is tracked out here, around the chip AND the cross. On the inner box it would
        // drop the moment the pointer crossed onto the cross, collapsing the thing the pointer
        // was reaching for - a flicker loop rather than a button.
        modifier = Modifier.hoverable(interactionSource),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier =
                    Modifier
                        .size(size)
                        .clip(chipShape(squareTrailingEdge = showClose))
                        .background(background)
                        // No hoverable here: it is registered once around the chip AND the cross,
                        // above. Registering the same source twice happens to balance its
                        // enter/exit pairs, which is what let this survive - but it reads as
                        // though the outer one were the redundant of the two.
                        .optionalContextMenu(contextMenuItems)
                        .onGloballyPositioned { coordinates -> windowPosition = coordinates.positionInWindow() }
                        .then(
                            // Inlined rather than a named `dragEnabled` flag: the flag was these
                            // three conditions, and the `if` then repeated all three beside it -
                            // which read as a guard doing more than it does, and cost the smart
                            // cast the branch needs.
                            if (tabDragComponent == null || panelId == null || tabIndex < 0) {
                                Modifier
                            } else {
                                Modifier.tabChipDrag(
                                    tab = tab,
                                    panelId = panelId,
                                    tabIndex = tabIndex,
                                    windowPosition = { windowPosition },
                                    tabDragComponent = tabDragComponent,
                                    onDragEnd = onDragEnd,
                                )
                            },
                        ).clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                TabGlyph(icon = icon, tab = tab, isActive = isActive)
            }

            // `showClose` already carries `onClose != null`; repeating it here only looked like
            // a null check, and the compiler reads it as always true.
            if (showClose) {
                // No gap: the cross is part of the chip, not a button next to it.
                TabCloseButton(base = background, onClose = onClose)
            }
        }
    }
}

/**
 * A chip's outline: rounded all round, or square on the trailing side while its cross is out.
 *
 * Squaring that edge is what makes the chip and the cross meet flush and read as one pill, rather
 * than as two rounded things touching with a seam down the middle. [TabCloseButton] clips to the
 * mirror of it.
 */
private fun chipShape(squareTrailingEdge: Boolean) =
    if (squareTrailingEdge) {
        RoundedCornerShape(
            topStart = CHIP_RADIUS,
            bottomStart = CHIP_RADIUS,
            topEnd = 0.dp,
            bottomEnd = 0.dp,
        )
    } else {
        RoundedCornerShape(CHIP_RADIUS)
    }

/** A right-click menu where the surface owes one, and nothing at all where it does not. */
private fun Modifier.optionalContextMenu(items: List<ContextMenuItem>): Modifier =
    if (items.isEmpty()) this else this.contextMenu(items = items)

/**
 * Picking a tab up from a chip.
 *
 * Its own modifier so [TabFaviconChip] stays a description of what is drawn. [windowPosition] is a
 * lambda because the gesture reads it when the drag STARTS rather than when the modifier is built:
 * the chip is measured after this runs, so a captured value would be the position from the frame
 * before, which is where the ghost would appear.
 *
 * Six parameters plus the receiver, and they are the drag's own identity - which tab, in which
 * panel, at which index, from where. Wrapping them in a holder would move the same six values
 * behind one name without making the call site say less.
 */
@Suppress("LongParameterList")
private fun Modifier.tabChipDrag(
    tab: TabInfo,
    panelId: String,
    tabIndex: Int,
    windowPosition: () -> Offset,
    tabDragComponent: TabDraggableComponent,
    onDragEnd: (TabDropResult?) -> Unit,
): Modifier =
    pointerInput(tab, panelId, tabIndex) {
        tabDragComponent.withDragSession { session ->
            detectDragGestures(
                onDragStart = { offset ->
                    session.start(
                        tab = tab,
                        panelId = panelId,
                        index = tabIndex,
                        position = windowPosition() + offset,
                    )
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    session.update(dragAmount)
                },
                // Cleaned up first either way: a result that throws must not leave a ghost stuck to
                // the pointer.
                onDragEnd = { onDragEnd(session.end()) },
                onDragCancel = { session.cancel() },
            )
        }
    }

/**
 * The cross beside a chip, whenever that chip can be closed.
 *
 * Its own click target rather than a gesture on the chip, because the chip already answers a
 * click by selecting the tab and a drag by picking it up. Sized to the chip so the two read as
 * one control while the pointer is on them.
 */
@Composable
private fun TabCloseButton(
    base: Color,
    onClose: () -> Unit,
) {
    val colors = BossTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    Box(
        modifier =
            Modifier
                .size(FAVICON_CHIP_SIZE)
                // Mirror of [chipShape], so the two halves close one pill.
                .clip(
                    RoundedCornerShape(
                        topStart = 0.dp,
                        bottomStart = 0.dp,
                        topEnd = CHIP_RADIUS,
                        bottomEnd = CHIP_RADIUS,
                    ),
                )
                // Carries the chip's own background rather than starting transparent, or the
                // pill would be filled on one side and see-through on the other. Its own hover
                // is what brightens it.
                .background(if (hovered) colors.lineStrong else base)
                .hoverable(interactionSource)
                .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = "Close tab",
            tint = if (hovered) colors.textPrimary else colors.textSecondary,
            modifier = Modifier.size(CLOSE_ICON_SIZE),
        )
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
            tab.icon != null -> rememberVectorPainter(tab.icon)
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
