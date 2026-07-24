package ai.rever.boss.components.overlays

import ContextMenuBackground
import ContextMenuBorder
import ContextMenuHover
import ai.rever.boss.platform.ContextMenuHandler
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * A context menu item that can be displayed in the context menu.
 *
 * @param text The text to display for this item
 * @param icon The icon to display for this item (optional)
 * @param isDivider Whether this item is a divider
 * @param trailingIcon Optional trailing icon (e.g., action button)
 * @param trailingIconColor Color for trailing icon (defaults to gray)
 * @param onTrailingClick Action when trailing icon is clicked
 * @param secondaryTrailingIcon Optional second trailing icon (e.g., delete button)
 * @param secondaryTrailingIconColor Color for secondary trailing icon (defaults to gray)
 * @param onSecondaryTrailingClick Action when secondary trailing icon is clicked
 * @param onClick The action to perform when this item is clicked (last param for trailing lambda)
 */
data class ContextMenuItem(
    val text: String = "",
    val icon: ImageVector? = null,
    val isDivider: Boolean = false,
    val trailingIcon: ImageVector? = null,
    val trailingIconColor: Color? = null,
    val onTrailingClick: (() -> Unit)? = null,
    val secondaryTrailingIcon: ImageVector? = null,
    val secondaryTrailingIconColor: Color? = null,
    val onSecondaryTrailingClick: (() -> Unit)? = null,
    val subMenu: List<ContextMenuItem>? = null, // Submenu items
    val onClick: () -> Unit = {},
)

/**
 * A custom context menu that can be shown on right-click or long press
 * depending on the platform.
 *
 * @param items The list of menu items to display
 * @param offset The offset from the mouse position to display the menu
 * @param onDismissRequest Callback when the menu should be dismissed
 */
@Composable
fun ContextMenu(
    items: List<ContextMenuItem>,
    offset: IntOffset = IntOffset.Zero,
    alignment: Alignment = Alignment.TopStart,
    modifier: Modifier = Modifier,
    onDismissRequest: () -> Unit,
) {
    Popup(
        onDismissRequest = onDismissRequest,
        alignment = alignment,
        offset = offset,
        properties = PopupProperties(focusable = true),
    ) {
        ContextMenuContent(
            items = items,
            modifier = modifier,
            onDismissRequest = onDismissRequest,
        )
    }
}

@Composable
private fun ContextMenuContent(
    items: List<ContextMenuItem>,
    modifier: Modifier = Modifier,
    onDismissRequest: () -> Unit,
) {
    var expandedSubMenuIndex by remember { mutableStateOf<Int?>(null) }
    var isSubMenuHovered by remember { mutableStateOf(false) }
    val colors = BossTheme.colors

    Column(
        modifier =
            modifier
                .background(
                    color = ContextMenuBackground,
                    shape = RoundedCornerShape(4.dp),
                ).border(
                    width = 1.dp,
                    color = ContextMenuBorder,
                    shape = RoundedCornerShape(4.dp),
                ).padding(vertical = 4.dp)
                .width(IntrinsicSize.Max),
    ) {
        items.forEachIndexed { index, item ->
            if (item.isDivider) {
                Divider(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    color = ContextMenuBorder,
                    thickness = 1.dp,
                )
            } else {
                val interactionSource = remember { MutableInteractionSource() }
                val isHovered by interactionSource.collectIsHoveredAsState()
                val hasSubMenu = !item.subMenu.isNullOrEmpty()

                // Update expanded submenu on hover - only close if hovering a different item
                LaunchedEffect(isHovered) {
                    if (isHovered) {
                        if (hasSubMenu) {
                            expandedSubMenuIndex = index
                        } else {
                            // Hovering a non-submenu item, close any open submenu
                            expandedSubMenuIndex = null
                        }
                    }
                }

                // Non-observable holder: avoids triggering remeasure during layout phase.
                // Trade-off: popup position won't update if parent moves while open (acceptable for menus).
                val rowWidthRef = remember { intArrayOf(0) }

                // Keep parent highlighted when submenu is open
                val isHighlighted = isHovered || (hasSubMenu && expandedSubMenuIndex == index)

                Box {
                    Row(
                        modifier =
                            Modifier
                                .hoverable(interactionSource)
                                .then(
                                    if (hasSubMenu) {
                                        Modifier
                                    } else {
                                        Modifier.clickable {
                                            item.onClick()
                                            onDismissRequest()
                                        }
                                    },
                                ).background(
                                    if (isHighlighted) ContextMenuHover else Color.Transparent,
                                ).padding(horizontal = 12.dp, vertical = 4.dp)
                                .fillMaxWidth()
                                .onGloballyPositioned { coordinates ->
                                    rowWidthRef[0] = coordinates.size.width
                                },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (item.icon != null) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.text,
                                tint = colors.textPrimary,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = item.text,
                            color = colors.textPrimary,
                            fontSize = 13.sp,
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .align(Alignment.CenterVertically)
                                    .padding(bottom = 4.dp),
                        )

                        // Show arrow for submenu
                        if (hasSubMenu) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "›",
                                color = colors.textSecondary,
                                fontSize = 16.sp,
                            )
                        }

                        // Primary trailing icon (e.g., play/stop button or status indicator)
                        if (item.trailingIcon != null) {
                            Spacer(modifier = Modifier.width(12.dp))
                            Box(
                                modifier =
                                    Modifier
                                        .size(20.dp)
                                        .then(
                                            if (item.onTrailingClick != null) {
                                                Modifier.clickable(
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = null,
                                                ) {
                                                    item.onTrailingClick.invoke()
                                                    onDismissRequest()
                                                }
                                            } else {
                                                Modifier
                                            },
                                        ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = item.trailingIcon,
                                    contentDescription = "Action",
                                    tint = item.trailingIconColor ?: colors.textSecondary,
                                    modifier = Modifier.size(if (item.onTrailingClick != null) 16.dp else 8.dp), // Smaller for indicator dots
                                )
                            }
                        }
                        // Secondary trailing icon (e.g., delete button)
                        if (item.secondaryTrailingIcon != null && item.onSecondaryTrailingClick != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier =
                                    Modifier
                                        .size(18.dp)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) {
                                            item.onSecondaryTrailingClick.invoke()
                                            onDismissRequest()
                                        },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = item.secondaryTrailingIcon,
                                    contentDescription = "Delete",
                                    tint = item.secondaryTrailingIconColor ?: colors.textSecondary,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }

                    // Render submenu with scroll support - positioned to the right of parent menu
                    if (hasSubMenu && expandedSubMenuIndex == index) {
                        val subMenuInteractionSource = remember { MutableInteractionSource() }
                        val subMenuHovered by subMenuInteractionSource.collectIsHoveredAsState()

                        // Track submenu hover state
                        LaunchedEffect(subMenuHovered) {
                            isSubMenuHovered = subMenuHovered
                        }

                        Popup(
                            alignment = Alignment.TopStart,
                            offset = IntOffset(rowWidthRef[0], 0), // Position to the right of parent menu item
                        ) {
                            val scrollState = rememberScrollState()
                            val needsScrollbar = scrollState.maxValue > 0

                            Row(
                                modifier =
                                    Modifier
                                        .hoverable(subMenuInteractionSource)
                                        .heightIn(max = 400.dp)
                                        .background(
                                            color = ContextMenuBackground,
                                            shape = RoundedCornerShape(4.dp),
                                        ).border(
                                            width = 1.dp,
                                            color = ContextMenuBorder,
                                            shape = RoundedCornerShape(4.dp),
                                        ),
                            ) {
                                Column(
                                    modifier =
                                        Modifier
                                            .padding(vertical = 4.dp)
                                            .widthIn(min = 150.dp)
                                            .width(IntrinsicSize.Max)
                                            .verticalScroll(scrollState),
                                ) {
                                    SubMenuContent(
                                        items = item.subMenu,
                                        onDismissRequest = onDismissRequest,
                                    )
                                }
                                // Only show scrollbar when content overflows
                                if (needsScrollbar) {
                                    VerticalScrollbar(
                                        modifier =
                                            Modifier
                                                .padding(vertical = 4.dp, horizontal = 2.dp),
                                        adapter = rememberScrollbarAdapter(scrollState),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Recursive submenu content that supports nested submenus.
 */
@Composable
private fun SubMenuContent(
    items: List<ContextMenuItem>,
    onDismissRequest: () -> Unit,
) {
    var expandedSubMenuIndex by remember { mutableStateOf<Int?>(null) }
    val colors = BossTheme.colors

    items.forEachIndexed { index, subItem ->
        if (subItem.isDivider) {
            Divider(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                color = ContextMenuBorder,
                thickness = 1.dp,
            )
        } else {
            val subInteractionSource = remember { MutableInteractionSource() }
            val subIsHovered by subInteractionSource.collectIsHoveredAsState()
            val hasNestedSubMenu = !subItem.subMenu.isNullOrEmpty()

            // Update expanded submenu on hover
            LaunchedEffect(subIsHovered) {
                if (subIsHovered) {
                    if (hasNestedSubMenu) {
                        expandedSubMenuIndex = index
                    } else {
                        expandedSubMenuIndex = null
                    }
                }
            }

            // Non-observable holder to avoid triggering remeasure during layout
            val rowWidthRef = remember { intArrayOf(0) }

            // Keep parent highlighted when nested submenu is open
            val isHighlighted = subIsHovered || (hasNestedSubMenu && expandedSubMenuIndex == index)

            Box {
                Row(
                    modifier =
                        Modifier
                            .hoverable(subInteractionSource)
                            .then(
                                if (hasNestedSubMenu) {
                                    Modifier
                                } else {
                                    Modifier.clickable {
                                        subItem.onClick()
                                        onDismissRequest()
                                    }
                                },
                            ).background(
                                if (isHighlighted) ContextMenuHover else Color.Transparent,
                            ).padding(horizontal = 12.dp, vertical = 4.dp)
                            .fillMaxWidth()
                            .onGloballyPositioned { coordinates ->
                                rowWidthRef[0] = coordinates.size.width
                            },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (subItem.icon != null) {
                        Icon(
                            imageVector = subItem.icon,
                            contentDescription = subItem.text,
                            tint = colors.textPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = subItem.text,
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier =
                            Modifier
                                .weight(1f)
                                .align(Alignment.CenterVertically)
                                .padding(bottom = 4.dp),
                    )

                    // Show arrow for nested submenu
                    if (hasNestedSubMenu) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "›",
                            color = colors.textSecondary,
                            fontSize = 16.sp,
                        )
                    }

                    // Trailing icon (e.g., checkmark for visibility state)
                    if (subItem.trailingIcon != null) {
                        Spacer(modifier = Modifier.width(12.dp))
                        Box(
                            modifier = Modifier.size(20.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = subItem.trailingIcon,
                                contentDescription = "Action",
                                tint = subItem.trailingIconColor ?: colors.textSecondary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }

                // Render nested submenu
                if (hasNestedSubMenu && expandedSubMenuIndex == index) {
                    Popup(
                        alignment = Alignment.TopStart,
                        offset = IntOffset(rowWidthRef[0], 0),
                    ) {
                        val scrollState = rememberScrollState()
                        val needsScrollbar = scrollState.maxValue > 0

                        Row(
                            modifier =
                                Modifier
                                    .heightIn(max = 400.dp)
                                    .background(
                                        color = ContextMenuBackground,
                                        shape = RoundedCornerShape(4.dp),
                                    ).border(
                                        width = 1.dp,
                                        color = ContextMenuBorder,
                                        shape = RoundedCornerShape(4.dp),
                                    ),
                        ) {
                            Column(
                                modifier =
                                    Modifier
                                        .padding(vertical = 4.dp)
                                        .widthIn(min = 150.dp)
                                        .width(IntrinsicSize.Max)
                                        .verticalScroll(scrollState),
                            ) {
                                SubMenuContent(
                                    items = subItem.subMenu,
                                    onDismissRequest = onDismissRequest,
                                )
                            }
                            if (needsScrollbar) {
                                VerticalScrollbar(
                                    modifier =
                                        Modifier
                                            .padding(vertical = 4.dp, horizontal = 2.dp),
                                    adapter = rememberScrollbarAdapter(scrollState),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Extension function to make any Compose UI element show a context menu.
 *
 * Uses platform-specific implementations:
 * - On desktop/web: Right-click activation
 * - On mobile (iOS/Android): Long press activation
 *
 * @param enabled Whether the context menu functionality is enabled
 * @param items The items to show in the context menu
 * @return A modifier that enables platform-appropriate context menu functionality
 */
fun Modifier.contextMenu(
    enabled: Boolean = true,
    items: List<ContextMenuItem>,
): Modifier =
    composed {
        var showMenu by remember { mutableStateOf(false) }
        var menuPosition by remember { mutableStateOf(IntOffset.Zero) }

        // Get the platform-specific handler
        val handler = remember { ContextMenuHandler() }

        if (showMenu && enabled) {
            ContextMenu(
                items = items,
                offset = menuPosition,
                onDismissRequest = { showMenu = false },
            )
        }

        // Apply platform-specific behavior
        with(handler) {
            this@composed.applyContextMenuBehavior(
                showMenu = showMenu,
                setShowMenu = { showMenu = it },
                setMenuPosition = { menuPosition = it },
            )
        }
    }
