package ai.rever.boss.components.overlays

import ContextMenuBackground
import ContextMenuBorder
import ContextMenuHover
import ai.rever.boss.platform.ContextMenuHandler
import ai.rever.boss.plugin.sandbox.PluginExecutionBoundary
import ai.rever.boss.plugin.ui.BossPopupAnchoring
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.menu.NATIVE_MENU_ICON_POINTS
import ai.rever.boss.plugin.ui.menu.NATIVE_MENU_ICON_SCALE
import ai.rever.boss.plugin.ui.menu.NativeContextMenus
import ai.rever.boss.plugin.ui.menu.NativeMenuAnchor
import ai.rever.boss.plugin.ui.menu.NativeMenuNode
import ai.rever.boss.plugin.ui.menu.shouldUseNativeMenus
import ai.rever.boss.window.WindowAppearanceSettingsManager
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
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
 * @param enabled Whether this item can be acted on. A disabled item is shown greyed out and does
 *   not react to a click, which is how a menu says "this action exists but not for this target"
 *   (e.g. a system plugin cannot be uninstalled) rather than hiding it and leaving the user to
 *   wonder where it went. Honoured on the drawn path and by the native menu, which carries its own
 *   enabled flag, so the two renderers agree.
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
    val enabled: Boolean = true,
    val onClick: () -> Unit = {},
)

/**
 * Whether this menu can be rendered by an operating-system menu without losing anything the user
 * can act on.
 *
 * A native menu item is a label, an enabled flag and an optional shortcut. Two of the app's extras
 * have no native equivalent, and they are not equally important:
 *
 * - A **leading icon is decoration**. macOS menus mostly do not carry one, so dropping it costs
 *   nothing but appearance. It is deliberately NOT disqualifying: nearly every menu item in the
 *   app has an icon, so treating icons as blocking would leave essentially every menu on the
 *   drawn path and make this whole feature invisible.
 * - An **inline trailing button is a distinct action** - the sidebar, workspace and run-history
 *   menus use them for edit and delete. Dropping one silently removes something the user relies
 *   on, so those menus keep the drawn path until they are reshaped to express the same actions as
 *   structure (a submenu).
 */
internal fun List<ContextMenuItem>.isNativeRepresentable(): Boolean =
    all { item ->
        item.trailingIcon == null &&
            item.secondaryTrailingIcon == null &&
            item.subMenu?.isNativeRepresentable() != false
    }

/** Icons already rasterised for a native menu, keyed by the vector they came from. */
internal typealias MenuIcons = Map<ImageVector, ImageBitmap>

/** Every distinct icon in the menu tree, so each is rasterised once rather than once per item. */
internal fun List<ContextMenuItem>.collectIcons(): List<ImageVector> =
    flatMap { item ->
        listOfNotNull(item.icon) + (item.subMenu?.collectIcons() ?: emptyList())
    }.distinct()

/**
 * Rasterise an icon for a native menu item.
 *
 * The tint is a fixed mid-grey rather than a theme colour: the menu's background is the system's,
 * not the app's, and macOS offers no way to mark a peer-supplied image as a template - which is
 * what would let the OS tint it per appearance and while highlighted. This grey approximates the
 * system secondary label colour and stays legible on both the light and dark menu backgrounds.
 */
@Composable
private fun ImageVector.toNativeMenuIcon(): ImageBitmap {
    val painter = rememberVectorPainter(this)
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    return remember(this, density, layoutDirection) {
        // Fixed 2x of the point size, NOT the screen density: the point size is stated by the
        // multi-resolution image the desktop side builds, so rasterising at the display's scale
        // would just hand AppKit a bitmap it reads as an oversized icon.
        val px = NATIVE_MENU_ICON_POINTS * NATIVE_MENU_ICON_SCALE
        val bitmap = ImageBitmap(px, px)
        CanvasDrawScope().draw(
            density = density,
            layoutDirection = layoutDirection,
            canvas = Canvas(bitmap),
            size = Size(px.toFloat(), px.toFloat()),
        ) {
            with(painter) { draw(size, colorFilter = ColorFilter.tint(NATIVE_MENU_ICON_TINT)) }
        }
        bitmap
    }
}

private val NATIVE_MENU_ICON_TINT = Color(0xFF8A8A8E)

/**
 * Convert to the toolkit-neutral model the native engine speaks.
 *
 * The action is wrapped in [PluginExecutionBoundary.invokeAttributed] for the same reason the
 * drawn rows are: a plugin-supplied callback must run inside its own attribution scope, or the
 * next crash on that thread is blamed on nobody and takes the app down instead of the plugin.
 * This is the one seam plugin crash recovery rests on, and it fails silently.
 */
internal fun List<ContextMenuItem>.toNativeMenuNodes(icons: MenuIcons = emptyMap()): List<NativeMenuNode> =
    map { item ->
        when {
            item.isDivider -> {
                NativeMenuNode.Separator
            }

            // isNullOrEmpty, matching the drawn renderer. An item with an EMPTY subMenu and a real
            // onClick is a clickable row there; treating it as a submenu here would make
            // planNativeMenu drop it as an unopenable dead row, so the item would vanish. Reachable
            // through the plugin API, which maps `subMenu?.map { ... }` and hands back an empty
            // non-null list.
            !item.subMenu.isNullOrEmpty() -> {
                NativeMenuNode.Submenu(item.text, item.subMenu.toNativeMenuNodes(icons))
            }

            else -> {
                NativeMenuNode.Item(
                    label = item.text,
                    enabled = item.enabled,
                    icon = item.icon?.let { icons[it] },
                    action = { PluginExecutionBoundary.invokeAttributed(item.onClick) },
                )
            }
        }
    }

/**
 * Forces the menu path in tests.
 *
 * The drawn menu is a Compose tree a UI test can find nodes in; a native menu is an OS window
 * that has none. Without this, every existing menu UI test would pass on CI and fail on a macOS
 * developer machine purely because of which renderer ran.
 */
internal object NativeContextMenuTestOverride {
    @Volatile
    internal var enabled: Boolean? = null
}

/**
 * Read per show, so toggling the preference takes effect on the next right-click without a
 * restart. Falls back to enabled if the settings file cannot be read.
 */
@Composable
private fun useNativeContextMenus(): Boolean {
    NativeContextMenuTestOverride.enabled?.let { return it }
    val settings by WindowAppearanceSettingsManager.currentSettings.collectAsState()
    return shouldUseNativeMenus(
        settingEnabled = settings.useNativeContextMenus,
        isMacOs = NativeContextMenus.isSupported(),
    )
}

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
    // A real OS menu, where the platform and the menu's shape both allow it.
    //
    // This is checked before the heavyweight branch below because it subsumes it: an NSMenu is an
    // OS-owned window, so it is never occluded by the browser's native surface and needs none of
    // the heavyweight-window machinery to say so.
    //
    // Falls through whenever the menu carries an icon or an inline trailing button, which a
    // native menu item cannot render - see [isNativeRepresentable].
    // Tracks a native attempt that could not produce a menu, so this composition falls through to
    // the drawn paths instead of returning with nothing on screen. Every guard in the engine
    // exists to make that decline possible; swallowing it here would defeat all of them, and the
    // reachable causes (no pointer, no showing frame, a throwing `locationOnScreen`) are sticky
    // rather than transient, so the user would get a right-click that repeatedly does nothing.
    var nativeUnavailable by remember { mutableStateOf(false) }

    if (useNativeContextMenus() && !nativeUnavailable && items.isNativeRepresentable()) {
        val dismiss by rememberUpdatedState(onDismissRequest)
        // Rasterised here in composition, where density is available; the effect below is not.
        val icons = items.collectIcons().associateWith { it.toNativeMenuIcon() }
        val nodes by rememberUpdatedState(items.toNativeMenuNodes(icons))
        // Keyed on Unit, NOT on items. This composable exists only while the menu should be up, so
        // entering composition IS the show and leaving it IS the dismissal. Keying on items would
        // restart the effect on every recomposition, because the list is rebuilt each time and its
        // lambdas capture unstable values so equality never holds - and the tab menu is rebuilt on
        // every tab-bar recomposition, e.g. on each line of terminal output. That would tear down
        // and reopen the menu under the user's cursor many times a second.
        DisposableEffect(Unit) {
            val shown =
                NativeContextMenus.show(
                    nodes = nodes,
                    // The pointer IS the intended position for a right-click menu, and reading it
                    // from the OS avoids converting node-relative Compose pixels into screen
                    // coordinates - a conversion this codebase has nowhere else.
                    anchor = NativeMenuAnchor.Cursor,
                    onDismiss = { dismiss() },
                )
            if (!shown) nativeUnavailable = true
            onDispose { NativeContextMenus.hide() }
        }
        if (!nativeUnavailable) return
    }

    val heavyweight = OverlayConfig.heavyweightPopup
    if (routeOverlayHeavyweight(heavyweight != null) && heavyweight != null) {
        // HARDWARE_ACCELERATED browser: a lightweight Compose Popup renders BEHIND the
        // browser's native surface, so a right-click menu over a page would be hidden by
        // the page it belongs to. Route it through a heavyweight window instead. Dormant
        // wherever OFF_SCREEN is the mode (macOS, Linux) - the flag is false there, so
        // this branch is never taken and those platforms keep the exact Popup below.
        //
        // Also dormant in a window with no browser surface (Settings): the heavyweight window
        // is sized to LocalAwtWindow, which is still the MAIN window there, so its scrim would
        // land over the wrong window. See routeOverlayHeavyweight.
        //
        // NOTE: [alignment] is not honoured on this path - the heavyweight window positions
        // from the cursor, not from an alignment within a parent layout. No caller passes a
        // non-default today, so this is latent rather than a live bug, but a caller that did
        // would get different placement per platform. Honouring it means teaching
        // HeavyweightPopup about window-space anchors first.
        // Cursor anchoring: a context menu is opened by a click, so the pointer IS the intended
        // position and no window-space conversion is needed. IntRect.Zero because this path never
        // consults the anchor.
        heavyweight(onDismissRequest, IntRect.Zero, BossPopupAnchoring.Cursor, offset, true) {
            ContextMenuContent(
                items = items,
                modifier = modifier,
                onDismissRequest = onDismissRequest,
            )
        }
    } else {
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

                // Keep parent highlighted when submenu is open. A disabled row never highlights:
                // hover feedback on something that cannot be clicked reads as a dead click target.
                val isHighlighted = item.enabled && (isHovered || (hasSubMenu && expandedSubMenuIndex == index))
                val rowColor = if (item.enabled) colors.textPrimary else colors.textMuted

                Box {
                    Row(
                        modifier =
                            Modifier
                                .hoverable(interactionSource)
                                .then(
                                    if (hasSubMenu || !item.enabled) {
                                        Modifier
                                    } else {
                                        Modifier.clickable {
                                            // Attributed at the call, not at the item: a plugin's
                                            // onClick is a lambda the plugin registered and the HOST
                                            // invokes, so by the time it throws there is nothing
                                            // plugin-shaped on the stack and the crash gets blamed on
                                            // BOSS. Doing it here rather than while mapping the items
                                            // costs no allocation, so the items stay equal across
                                            // recompositions and Compose can still skip this subtree.
                                            // finally, because invokeAttributed rethrows: a plugin
                                            // action that throws used to take the app with it, so the
                                            // menu went too. Now the crash is survivable, and without
                                            // this the menu stays on screen over the crash dialog and
                                            // outlives the plugin it belongs to.
                                            try {
                                                PluginExecutionBoundary.invokeAttributed(item.onClick)
                                            } finally {
                                                // runCatching: if dismissing throws while a plugin
                                                // exception is in flight, a bare call replaces it -
                                                // and with it the attribution tag - so the crash gets
                                                // blamed on BOSS, the exact failure this exists to
                                                // prevent.
                                                runCatching { onDismissRequest() }
                                            }
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
                                tint = rowColor,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = item.text,
                            color = rowColor,
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
                                    // Smaller size for indicator dots
                                    modifier = Modifier.size(if (item.onTrailingClick != null) 16.dp else 8.dp),
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

            // Keep parent highlighted when nested submenu is open. Disabled rows never highlight -
            // see the same treatment in ContextMenuContent.
            val isHighlighted = subItem.enabled && (subIsHovered || (hasNestedSubMenu && expandedSubMenuIndex == index))
            val subRowColor = if (subItem.enabled) colors.textPrimary else colors.textMuted

            Box {
                Row(
                    modifier =
                        Modifier
                            .hoverable(subInteractionSource)
                            .then(
                                if (hasNestedSubMenu || !subItem.enabled) {
                                    Modifier
                                } else {
                                    Modifier.clickable {
                                        // Submenu items are plugin-owned just as often; see above.
                                        try {
                                            PluginExecutionBoundary.invokeAttributed(subItem.onClick)
                                        } finally {
                                            runCatching { onDismissRequest() }
                                        }
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
                            tint = subRowColor,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = subItem.text,
                        color = subRowColor,
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
): Modifier = contextMenu(enabled) { items }

/**
 * [contextMenu] for a menu that is expensive to build, e.g. one derived from several registries.
 *
 * [items] is composed only while the menu is actually open, so a caller that would otherwise hold
 * flow subscriptions for a menu nobody has asked for pays nothing until the right-click. That is not
 * a micro-optimisation where the caller is repeated: the sidebar rail attaches one of these to every
 * plugin icon, and the eager form cost five `collectAsState` subscriptions per icon and re-queried
 * every plugin's contributed items on every plugin load, for panels the user had never opened.
 *
 * The catch, for anyone writing a provider: it is composed inside the open-menu branch, so anything
 * remembered in it dies when the menu closes - including a `rememberCoroutineScope`, which the menu
 * closing on the very click that launched the work would then cancel. Work started from a menu row
 * has to be owned by something that outlives the menu.
 */
fun Modifier.contextMenu(
    enabled: Boolean = true,
    items: @Composable () -> List<ContextMenuItem>,
): Modifier =
    composed {
        var showMenu by remember { mutableStateOf(false) }
        var menuPosition by remember { mutableStateOf(IntOffset.Zero) }

        // Get the platform-specific handler
        val handler = remember { ContextMenuHandler() }

        if (showMenu && enabled) {
            ContextMenu(
                items = items(),
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
