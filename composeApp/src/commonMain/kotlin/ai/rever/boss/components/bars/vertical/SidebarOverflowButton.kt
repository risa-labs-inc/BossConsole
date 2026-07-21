package ai.rever.boss.components.bars.vertical

import ai.rever.boss.components.buttons.BossActionButton
import ai.rever.boss.components.model.BossDraggableComponent
import ai.rever.boss.components.overlays.ContextMenu
import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.components.overlays.contextMenu
import ai.rever.boss.components.sidebar.rememberSidebarSettingsMenuItems
import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.Panel.Companion.isFirst
import ai.rever.boss.plugin.api.Panel.Companion.opposite
import ai.rever.boss.plugin.api.SidebarItem
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * "More" button rendered at the end of a sidebar slot whose plugin icons
 * exceed the slot's visible limit. Click → a context menu listing the
 * overflowed [items]; clicking one behaves exactly like clicking its
 * sidebar icon (toggle the slot's panel, or focus the hosting main tab).
 *
 * Vertical three-dot icon on purpose — the horizontal three-dot in the
 * same rail is the customize-sidebar button, and the two do different
 * things. Unlike the customize menu, item clicks dismiss the popup
 * (ContextMenu's default), because the natural next focus is the panel
 * that just opened.
 *
 * Overflowed items can't be drag-reordered from the menu; dropping an
 * icon onto the slot still appends it (possibly into overflow) via the
 * existing drop handling, so nothing becomes unreachable.
 */
@Composable
fun BossDraggableComponent.SidebarOverflowButton(
    slot: Panel,
    items: List<SidebarItem>,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    // Right-click (long-press on touch) → "Sidebar settings"
    val settingsMenuItems = rememberSidebarSettingsMenuItems()

    // Built fresh each composition: isSelected reads panelsData snapshot
    // state, so open/close of any listed panel re-renders the checkmarks.
    val menuItems = items.map { item ->
        ContextMenuItem(
            text = item.label,
            icon = item.icon,
            trailingIcon = if (isSelected(item)) Icons.Default.Check else null,
            onClick = { handleSidebarItemClick(item) },
        )
    }

    // Same outer-Box sizing trick as SidebarCustomizeMenu: constrain the
    // 40dp BossActionButton so its selection background matches the
    // 32dp icons around it.
    Box(
        modifier = modifier
            .padding(vertical = 4.dp)
            .size(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        BossActionButton(
            imageVector = Icons.Default.MoreVert,
            text = "${items.size} more",
            isSelected = menuExpanded || items.any { isSelected(it) },
            hintDirection = slot.opposite,
            modifier = Modifier
                .size(40.dp)
                .contextMenu(items = settingsMenuItems),
        ) {
            if (draggingItem == null) {
                menuExpanded = !menuExpanded
            }
        }

        if (menuExpanded) {
            // Anchoring mirrors SidebarCustomizeMenu: shift the popup by
            // the button's width so the menu opens in the main content
            // area, rightwards from the left rail and leftwards from the
            // right rail.
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
