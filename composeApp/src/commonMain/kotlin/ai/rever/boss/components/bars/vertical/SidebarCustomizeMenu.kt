package ai.rever.boss.components.bars.vertical

import ai.rever.boss.components.buttons.BossActionButton
import ai.rever.boss.components.model.BossDraggableComponent
import ai.rever.boss.components.overlays.ContextMenu
import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.components.sidebar.SidebarVisibilitySettings
import ai.rever.boss.components.sidebar.SidebarVisibilitySettingsManager
import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.isFirst
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.Panel.Companion.opposite
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.plugin.api.SidebarItem
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.LocalWindowId
import ai.rever.boss.window.MenuActionsHandler
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Internal sentinel id for the customize button's synthetic [SidebarItem].
 *
 * The button isn't backed by a real plugin panel — we synthesise a
 * SidebarItem only so the model's existing drag-overlay machinery can
 * render the long-press lift animation against it. We never inject this
 * id into [BossDraggableComponent.itemsBySlot]; on drop we just stop
 * dragging without mutating the slot maps. Compare against this id when
 * filtering listings (e.g. the customize menu's own panel list).
 *
 * The leading `__` is the reserved-prefix convention enforced by
 * [PanelRegistry.registerPanel][ai.rever.boss.plugin.api.PanelRegistry.registerPanel],
 * which refuses to register any plugin panel whose id starts with `__`.
 * That keeps the sentinel collision-free at the boundary instead of
 * relying on plugin authors to read the docs.
 */
internal const val CUSTOMIZE_BUTTON_ID = "__sidebar_customize__"

private val sidebarCustomizeLogger = BossLogger.forComponent("SidebarCustomizeMenu")

/**
 * Three-dot "Customize sidebar" button. Click → a context menu whose
 * top level lists each rendered sidebar slot (left.top.top,
 * left.top.bottom, left.bottom, right.top.top, right.top.bottom), and
 * each one's submenu lists the panels currently registered to that
 * slot with a checkmark indicating visibility. Clicking a panel item
 * toggles its hidden state (persisted via
 * [SidebarVisibilitySettingsManager]).
 *
 * The button itself matches every other sidebar icon: same
 * [BossActionButton] styling, same long-press drag affordance. The
 * drag is wired to relocation — on drop over any of the five rendered
 * sidebar slots the new home is persisted; otherwise the button snaps
 * back. The button never enters [BossDraggableComponent.itemsBySlot] —
 * it's rendered by [BossLeftSideBar] / [BossRightSideBar] based on
 * the persisted slot id.
 */
@Composable
fun BossDraggableComponent.SidebarCustomizeMenu(
    /** Slot the button visually belongs to; controls hint direction & drag overlay rendering. */
    slot: Panel = left.top.bottom,
    modifier: Modifier = Modifier,
) {
    val visibility by SidebarVisibilitySettingsManager.currentSettings.collectAsState()
    val scope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }

    // Open the customize menu when the OS-level "View → Customize Sidebar…"
    // item is clicked for THIS window. In focus mode the sidebar (and so
    // this composable) may not be composed at the moment of the click —
    // the StateFlow nature of customizeSidebarTriggers lets us still pick
    // up the request once the sidebar is force-revealed by BossApp's
    // subscriber. Triggers are keyed by windowId so concurrent requests
    // in other windows don't clobber ours.
    val windowId = LocalWindowId.current
    val customizeTriggers by MenuActionsHandler.customizeSidebarTriggers.collectAsState()
    LaunchedEffect(customizeTriggers, windowId) {
        if (windowId != null && customizeTriggers.containsKey(windowId)) {
            menuExpanded = true
            // Clear our entry so later recompositions (e.g. sidebar hide/
            // reveal in focus mode) don't re-open the menu.
            MenuActionsHandler.clearCustomizeSidebarTrigger(windowId)
        }
    }

    // No remember-key on slot: the synthesised SidebarItem doesn't read
    // slot, so re-creating it on every drag-driven slot change is wasted
    // work — the model's drag overlay just needs object identity here.
    val syntheticItem =
        remember {
            SidebarItem(
                pluginContentId =
                    ai.rever.boss.plugin.api.PanelId(
                        panelId = CUSTOMIZE_BUTTON_ID,
                        defaultOrder = Int.MAX_VALUE,
                        pluginId = "ai.rever.boss.sidebar.customize",
                    ),
                icon = Icons.Default.MoreHoriz,
                label = "Customize sidebar",
            )
        }
    val currentItem by rememberUpdatedState(syntheticItem)
    val currentSlot by rememberUpdatedState(slot)
    val isBeingDragged = draggingItem?.first?.id == CUSTOMIZE_BUTTON_ID

    var componentPositionInWindow by remember { mutableStateOf<Offset?>(null) }
    var pendingDragStartOffset by remember { mutableStateOf<Offset?>(null) }

    // Mirror DraggableActionButton: kick off the model's drag once we
    // know both the touch offset and the button's window position.
    LaunchedEffect(componentPositionInWindow, pendingDragStartOffset) {
        val startOffset = pendingDragStartOffset
        val currentPos = componentPositionInWindow
        if (startOffset != null && currentPos != null) {
            startDragging(currentItem, currentSlot, currentPos + startOffset)
            pendingDragStartOffset = null
        }
    }

    // Cleanup if disposed mid-drag. Use cancelDragSnapBack — NOT
    // stopDragging — because the customize button isn't a real plugin
    // panel and must never be written into itemsBySlot. stopDragging()
    // would inject the synthetic id into the current drop-target slot
    // if one happened to be set when disposal fires (workspace switch,
    // focus-mode hide, window close mid-gesture).
    DisposableEffect(CUSTOMIZE_BUTTON_ID) {
        onDispose {
            if (draggingItem?.first?.id == CUSTOMIZE_BUTTON_ID) {
                cancelDragSnapBack()
            }
        }
    }

    // Build the slot → panels grouping for the menu. Only include slots
    // that have at least one registered panel so the menu doesn't grow
    // empty parents.
    //
    // No remember() here: the menu depends on both `visibility` (hide/show
    // toggles) AND `itemsBySlot` (drag-and-drop slot reassignments).
    // visibility is observed via collectAsState above; itemsBySlot is a
    // mutableStateMap whose reads inside buildSlotMenu register Compose
    // observations on this composition, so a drag that moves a panel
    // between slots invalidates and rebuilds the menu automatically.
    // Recomputing each composition is cheap (5 slots, ≤ a few items each).
    val menuItems =
        buildSlotMenu(this, visibility) { panelId, hidden ->
            scope.launch {
                SidebarVisibilitySettingsManager.setHidden(panelId, hidden)
                if (hidden) {
                    // If the panel was open, close it — otherwise the user is
                    // left with content visible but no icon to dismiss it.
                    closePanelForItem(panelId)
                }
            }
        }

    // Outer Box constrains the BossActionButton's modifier chain so the
    // selection background ends up the same size as the regular sidebar
    // icons (which receive .size(32.dp) from SidebarSlotContainer). Without
    // this, the BossActionButton's internal .size(28.dp) gets clamped UP to
    // the larger surviving min constraint and the selection rectangle on
    // the three-dot icon renders visibly bigger than its neighbours.
    Box(
        modifier =
            modifier
                .padding(vertical = 4.dp)
                .size(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        BossActionButton(
            imageVector = Icons.Default.MoreHoriz,
            text = "Customize sidebar",
            isSelected = menuExpanded,
            hintDirection = slot.opposite,
            modifier =
                Modifier
                    .onGloballyPositioned { layoutCoordinates ->
                        val newPos = layoutCoordinates.positionInWindow()
                        if (componentPositionInWindow != newPos) {
                            componentPositionInWindow = newPos
                        }
                    }.size(40.dp)
                    .alpha(if (isBeingDragged) 0f else 1f)
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { touchOffset ->
                                pendingDragStartOffset = touchOffset
                            },
                            onDragEnd = {
                                // The customize button isn't a real plugin
                                // panel, so it never goes into itemsBySlot.
                                // If the drop landed on one of the five
                                // rendered sidebar slots, persist that as
                                // the button's new home; otherwise snap
                                // back to its current position.
                                pendingDragStartOffset = null
                                if (draggingItem?.first?.id == CUSTOMIZE_BUTTON_ID) {
                                    val target = dropTargetSlot
                                    val newSlotId =
                                        target?.let {
                                            SidebarVisibilitySettings.slotIdFor(it)
                                        }
                                    if (newSlotId != null) {
                                        scope.launch {
                                            SidebarVisibilitySettingsManager
                                                .setCustomizeButtonSlot(newSlotId)
                                        }
                                    }
                                    cancelDragSnapBack()
                                }
                            },
                            onDragCancel = {
                                pendingDragStartOffset = null
                                if (draggingItem?.first?.id == CUSTOMIZE_BUTTON_ID) {
                                    cancelDragSnapBack()
                                }
                            },
                            onDrag = { change: PointerInputChange, dragAmount: Offset ->
                                if (draggingItem?.first?.id == CUSTOMIZE_BUTTON_ID) {
                                    change.consume()
                                    updateDragDelta(dragAmount)
                                }
                            },
                        )
                    },
        ) {
            // Tap → toggle the context menu. Suppressed if a drag is
            // in flight (matches DraggableActionButton's behaviour).
            if (draggingItem == null) {
                menuExpanded = !menuExpanded
            }
        }

        if (menuExpanded) {
            // The Popup is anchored to this Box (its declared parent),
            // not the window. BottomStart drops the menu directly
            // beneath the button on the left sidebar; BottomEnd
            // mirrors it on the right sidebar.
            //
            // The Box is the same 40dp width as the sidebar column, so
            // a zero-offset BottomStart/BottomEnd would put the menu's
            // body directly on top of (or behind) the sidebar buttons.
            // Shift the popup horizontally by the button's width so its
            // edge sits flush against the *outer* edge of the sidebar
            // column and the menu opens entirely in the main content
            // area: rightwards on the left sidebar, leftwards on the
            // right sidebar.
            val buttonWidthPx = with(LocalDensity.current) { 40.dp.roundToPx() }
            val alignment: Alignment
            val popupOffset: IntOffset
            if (slot.isFirst) {
                alignment = Alignment.BottomStart
                popupOffset = IntOffset(buttonWidthPx, 0)
            } else {
                alignment = Alignment.BottomEnd
                popupOffset = IntOffset(-buttonWidthPx, 0)
            }
            ContextMenu(
                items = menuItems,
                alignment = alignment,
                offset = popupOffset,
                onDismissRequest = { menuExpanded = false },
            )
        }
    }
}

/**
 * Build the two-level ContextMenuItem list:
 *   slot label  ►  [panel  ✓, panel, ...]
 *
 * Slots with no panels are omitted so the menu doesn't show empty
 * parents.
 */
private fun buildSlotMenu(
    model: BossDraggableComponent,
    settings: SidebarVisibilitySettings,
    onToggle: (panelId: String, hide: Boolean) -> Unit,
): List<ContextMenuItem> {
    val slotOrder =
        listOf(
            SidebarVisibilitySettings.SLOT_LEFT_TOP_TOP to "Left · Top · Top",
            SidebarVisibilitySettings.SLOT_LEFT_TOP_BOTTOM to "Left · Top · Bottom",
            SidebarVisibilitySettings.SLOT_LEFT_BOTTOM to "Left · Bottom",
            SidebarVisibilitySettings.SLOT_RIGHT_TOP_TOP to "Right · Top · Top",
            SidebarVisibilitySettings.SLOT_RIGHT_TOP_BOTTOM to "Right · Top · Bottom",
        )
    val hidden = settings.hiddenPanelIds
    val parents = mutableListOf<ContextMenuItem>()
    for ((slotId, label) in slotOrder) {
        val panel = SidebarVisibilitySettings.panelFor(slotId)
        val rawItems =
            model
                .getItemsForSlotUnfiltered(panel)
                .filter { it.id != CUSTOMIZE_BUTTON_ID }
        // De-duplicate defensively (PanelRegistry shouldn't produce
        // duplicates per slot, but if it ever does we'd otherwise render
        // collided checkbox entries that confuse the toggle behaviour).
        // Log if it actually fires so the upstream bug surfaces.
        val items = rawItems.distinctBy { it.id }
        if (items.size != rawItems.size) {
            sidebarCustomizeLogger.warn(
                LogCategory.UI,
                "Duplicate panel ids in slot",
                mapOf(
                    "slotId" to slotId,
                    "rawCount" to rawItems.size,
                    "uniqueCount" to items.size,
                ),
            )
        }
        if (items.isEmpty()) continue

        val children =
            items.map { item ->
                val isHidden = item.id in hidden
                ContextMenuItem(
                    text = item.label,
                    icon = item.icon,
                    trailingIcon = if (!isHidden) Icons.Default.Check else null,
                    onClick = { onToggle(item.id, !isHidden) },
                )
            }
        parents += ContextMenuItem(text = label, subMenu = children)
    }
    return parents
}
