package ai.rever.boss.components.buttons

import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.Panel.Companion.right
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.components.overlays.ContextMenu
import ai.rever.boss.components.overlays.ContextMenuItem
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

/**
 * State holder for hover popup to survive recomposition.
 */
private class HoverPopupState {
    var isShowing by mutableStateOf(false)
    var capturedText by mutableStateOf<String?>(null)
}

@Composable
fun BossActionButton(
    imageVector: ImageVector,
    text: String,
    isSelected: Boolean,
    modifier: Modifier,
    hintDirection: Panel = bottom,
    onClick: () -> Unit
) = BossActionButton(
    imageVector = imageVector,
    text = text,
    isSelected = isSelected,
    modifier = modifier,
    hintText = text,
    showHintWithDelay = false,
    hintDirection = hintDirection,
    onClick = onClick
)

@Composable
fun BossActionButton(
    imageVector: ImageVector? = null,
    leftLogo: (@Composable () -> Unit)? = null,
    leftIcon: ImageVector? = null,
    text: String,
    fontSize: TextUnit = 13.sp,
    color: Color = BossTheme.colors.textPrimary,
    iconColor: Color? = null, // Optional separate icon color (defaults to color if null)
    iconSize: Dp = 20.dp, // Icon size for imageVector mode
    maxTextWidth: Dp? = null, // Optional max width for text (truncates with ellipsis)
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(2.dp),
    isSelected: Boolean = false,
    contextMenuItems: List<ContextMenuItem>? = null,
    contextDirection: Panel = bottom,
    hintText: String? = null,
    showHintWithDelay: Boolean = true,
    hintDirection:  Panel = bottom,
    onClick: () -> Unit = {}
) {
    // Resolved icon color - use iconColor if provided, otherwise fall back to color
    val resolvedIconColor = iconColor ?: color
    // BOSS design-system tokens — semantic accessors over BossDesignSystem.kt.
    val colors = BossTheme.colors
    val radii = BossTheme.radius
    // State for context menu
    var showContextMenu by remember { mutableStateOf(false) }
    // Non-observable holders: avoid triggering remeasure during the layout phase.
    // Trade-off: popup positions won't update if button moves while open (acceptable for modal popups).
    val buttonPositionRef = remember { floatArrayOf(0f, 0f) }
    val buttonSizeRef = remember { intArrayOf(0, 0) }
    val contextMenuSizeRef = remember { intArrayOf(0, 0) }
    val hintPopupSizeRef = remember { intArrayOf(0, 0) }

    // Popup positions are computed lazily when Popups become visible
    fun computeMenuPosition(): IntOffset {
        val x = buttonPositionRef[0].toInt() +
                when (contextDirection) {
                    right -> buttonSizeRef[0]
                    left -> -contextMenuSizeRef[0]
                    else -> (buttonSizeRef[0] - contextMenuSizeRef[0]) / 2
                }
        val y = buttonPositionRef[1].toInt() +
                when (contextDirection) {
                    top -> -contextMenuSizeRef[1]
                    bottom -> buttonSizeRef[1]
                    else -> 0
                }
        return IntOffset(x, y)
    }

    // State for hover popup - use class-level state holder to survive recomposition
    val hoverState = remember { HoverPopupState() }
    fun computeHoverPopupPosition(): IntOffset {
        return IntOffset(
            buttonPositionRef[0].toInt() +
                when (hintDirection) {
                    right -> buttonSizeRef[0]
                    left -> -hintPopupSizeRef[0]
                    else -> (buttonSizeRef[0] - hintPopupSizeRef[0]) / 2
                },
            buttonPositionRef[1].toInt() +
                when (hintDirection) {
                    top -> -hintPopupSizeRef[1]
                    bottom -> buttonSizeRef[1]
                    else -> 0
                }
        )
    }

    // Use interaction source to track states
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()

    // Determine active state based on hover, focus or selection
    val isActive = isHovered || isFocused || isSelected

    // Use rememberUpdatedState to access latest hintText without triggering recomposition
    val latestHintText by rememberUpdatedState(hintText)

    // Handle hover popup delay - only track isHovered to avoid flickering from hint text updates
    LaunchedEffect(Unit) {
        snapshotFlow { isHovered }
            .collect { hovering ->
                if (hovering && latestHintText != null) {
                    // Capture hint text when hover starts (only if not already showing)
                    if (!hoverState.isShowing) {
                        hoverState.capturedText = latestHintText
                        if (showHintWithDelay) {
                            delay(500)
                        }
                        // Re-check hover state after delay
                        if (isHovered) {
                            hoverState.isShowing = true
                        }
                    }
                } else if (!hovering) {
                    hoverState.isShowing = false
                    hoverState.capturedText = null
                }
            }
    }

    // Show context menu if enabled
    if (showContextMenu && contextMenuItems != null) {
        ContextMenu(
            items = contextMenuItems,
            offset = computeMenuPosition(),
            onDismissRequest = { showContextMenu = false },
            modifier = Modifier.onGloballyPositioned { coordinates ->
                contextMenuSizeRef[0] = coordinates.size.width
                contextMenuSizeRef[1] = coordinates.size.height
            }
        )
    }
    
    // Show hover popup if hovering and hint text is provided
    // Use hoverState.capturedText to prevent flickering when content updates during hover
    val displayHintText = hoverState.capturedText ?: hintText
    if (hoverState.isShowing && displayHintText != null) {
        Popup(
            alignment = Alignment.TopStart,
            offset = computeHoverPopupPosition(),
            properties = PopupProperties(focusable = false)
        ) {
            Surface(
                modifier = Modifier
                    .onGloballyPositioned { coordinates ->
                        hintPopupSizeRef[0] = coordinates.size.width
                        hintPopupSizeRef[1] = coordinates.size.height
                    },
                color = colors.raised,
                shape = RoundedCornerShape(radii.input)
            ) {
                Row(modifier = Modifier.defaultMinSize(2.dp)
                    .padding(vertical = 0.dp, horizontal = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayHintText,
                        color = colors.textPrimary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
        }
    }

    @Composable
    fun MainText() {
        Text(
            text = text,
            color = if (isActive) color else color.copy(alpha = 0.8f),
            fontSize = fontSize,
            fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (maxTextWidth != null) Modifier.widthIn(max = maxTextWidth) else Modifier
        )
    }

    @Composable
    fun MainIcon(icon: ImageVector = Icons.Outlined.KeyboardArrowDown, size: Dp = 16.dp) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            modifier = Modifier.size(size),
            tint = if (isActive) resolvedIconColor else resolvedIconColor.copy(alpha = 0.8f)
        )
    }

    var _leftLogo = leftLogo
    var _contentPadding = contentPadding


    leftLogo?.let {
        _contentPadding = PaddingValues(vertical = 2.dp, horizontal = 10.dp)
    }?: leftIcon?.let {
        _contentPadding = PaddingValues(vertical = 6.dp, horizontal = 10.dp)
        _leftLogo = { MainIcon(it) }
    }

    // Define button click handler
    val handleClick = {
        if (contextMenuItems != null) {
            showContextMenu = true
            hoverState.isShowing = false // Hide hover popup when showing context menu
        }
        // Always call the provided onClick handler
        onClick()
    }

    // Use Button instead of IconButton to get better hover support
    TextButton(
        onClick = handleClick,
        interactionSource = interactionSource,
        colors = ButtonDefaults.buttonColors(
            backgroundColor = if (isSelected) {
                if (isFocused) {
                    colors.signal
                } else {
                    color.copy(alpha = 0.1f)
                }
            } else {
                Color.Transparent
            },
            contentColor = color
        ),
        contentPadding = _contentPadding,
        modifier = modifier
            .defaultMinSize(minHeight = 2.dp, minWidth = 2.dp)
            .run {
                if (imageVector != null) {
                    size(28.dp).hoverable(interactionSource)
                } else {
                    hoverable(interactionSource)
                }
            }
            .onGloballyPositioned { coordinates ->
                val pos = coordinates.positionInParent()
                buttonPositionRef[0] = pos.x
                buttonPositionRef[1] = pos.y
                buttonSizeRef[0] = coordinates.size.width
                buttonSizeRef[1] = coordinates.size.height
            }
    ) {
        if (_leftLogo != null) {
            _leftLogo()
            Spacer(modifier = Modifier.width(8.dp))
            MainText()
            MainIcon()
        } else if (imageVector != null) {
            MainIcon(imageVector, iconSize)
        } else {
            MainText()
        }
    }
}
