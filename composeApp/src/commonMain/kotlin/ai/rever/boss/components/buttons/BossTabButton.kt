package ai.rever.boss.components.buttons

import ai.rever.boss.components.model.TabDraggableComponent
import ai.rever.boss.components.model.TabDropResult
import ai.rever.boss.components.overlays.ContextMenu
import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Icon
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Diversity2
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BossTabButton(
    fileName: String,
    icon: ImageVector? = null,
    iconPainter: Painter? = null,
    tabIcon: TabIcon? = null,
    isSelected: Boolean = false,
    isFocused: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onClose: () -> Unit = {},
    contextMenuItems: List<ContextMenuItem> = emptyList(),
    /**
     * Explicit width for this tab. When provided, the tab is sized exactly to this value
     * (no min/max content sizing). Callers compute this from available row width to get
     * Safari-style "shrink to fit, then scroll" behaviour. Defaults to null which falls
     * back to the legacy intrinsic-min sizing with a 180–450 dp width clamp.
     */
    tabWidth: Dp? = null,
    // Drag-related parameters
    tabDragComponent: TabDraggableComponent? = null,
    tabInfo: TabInfo? = null,
    panelId: String? = null,
    tabIndex: Int = -1,
    onDragStart: () -> Unit = {},
    onDragEnd: (TabDropResult?) -> Unit = {},
) {
    // BOSS design-system tokens — semantic accessors over BossDesignSystem.kt.
    val colors = BossTheme.colors
    val radii = BossTheme.radius
    val space = BossTheme.space

    // Determine which icon to use
    val painter =
        when {
            tabIcon != null -> tabIcon.asPainter()
            iconPainter != null -> iconPainter
            icon != null -> rememberVectorPainter(icon)
            else -> null
        }

    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    // State for tooltip
    var showTooltip by remember { mutableStateOf(false) }
    // Non-observable holders: avoid triggering remeasure during the layout phase.
    // Trade-off: popup positions won't update if button moves while open (acceptable for tooltips/menus).
    val buttonPositionRef = remember { floatArrayOf(0f, 0f) }
    val buttonSizeRef = remember { intArrayOf(0, 0) }
    val tooltipSizeRef = remember { intArrayOf(0, 0) }

    // Calculate tooltip position - centered above the button
    fun computeTooltipPosition() =
        IntOffset(
            x = buttonPositionRef[0].toInt() + (buttonSizeRef[0] - tooltipSizeRef[0]) / 2,
            y = buttonPositionRef[1].toInt() - tooltipSizeRef[1] - 5,
        )

    // Handle hover tooltip delay
    LaunchedEffect(isHovered) {
        if (isHovered) {
            delay(500) // 500ms delay before showing tooltip
            if (isHovered) { // Check if still hovering after delay
                showTooltip = true
            }
        } else {
            showTooltip = false
        }
    }

    // Show tooltip popup if hovering
    if (showTooltip) {
        Popup(
            alignment = Alignment.TopStart,
            offset = computeTooltipPosition(),
            properties =
                PopupProperties(
                    focusable = false,
                    dismissOnClickOutside = false,
                ),
        ) {
            Surface(
                modifier =
                    Modifier
                        .onGloballyPositioned { coordinates ->
                            tooltipSizeRef[0] = coordinates.size.width
                            tooltipSizeRef[1] = coordinates.size.height
                        },
                color = colors.raised,
                shape = RoundedCornerShape(radii.input),
            ) {
                Text(
                    text = fileName,
                    color = colors.textPrimary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }

    // State for context menu
    var showContextMenu by remember { mutableStateOf(false) }

    // Coroutine scope for middle-click close (Issue #328)
    // Using scope.launch because calling onClose directly from pointerInput's
    // awaitPointerEventScope doesn't properly trigger Compose state updates
    val closeScope = rememberCoroutineScope()

    // Track window position for drag
    var windowPosition by remember { mutableStateOf(Offset.Zero) }

    // Register tab bounds for drag system
    // Include tabIndex >= 0 check to avoid creating invalid composite IDs with index -1
    val compositeTabId = if (panelId != null && tabInfo != null && tabIndex >= 0) "$panelId:${tabInfo.id}" else null
    DisposableEffect(compositeTabId) {
        onDispose {
            compositeTabId?.let { tabDragComponent?.unregisterTabBounds(it) }
        }
    }

    // Show context menu with proper positioning
    if (showContextMenu && contextMenuItems.isNotEmpty()) {
        ContextMenu(
            items = contextMenuItems,
            offset =
                IntOffset(
                    buttonPositionRef[0].toInt(),
                    buttonPositionRef[1].toInt() + buttonSizeRef[1],
                ),
            onDismissRequest = { showContextMenu = false },
        )
    }

    // Check if drag is enabled
    val isDragEnabled = tabDragComponent != null && tabInfo != null && panelId != null && tabIndex >= 0

    // Cleanup drag state if this component is disposed while dragging
    // This prevents "stuck" drag overlays when gesture is interrupted
    DisposableEffect(tabDragComponent, tabInfo?.id) {
        onDispose {
            // Only cancel if THIS tab is the one being dragged
            if (tabDragComponent?.draggingTab?.tabInfo?.id == tabInfo?.id) {
                tabDragComponent?.cancelDrag()
            }
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxHeight()
                .let { base ->
                    if (tabWidth != null) {
                        // Explicit width from the parent (Safari-style shrink-to-fit).
                        base.width(tabWidth)
                    } else {
                        // Legacy sizing: content-driven width clamped to 180–450 dp.
                        base.width(IntrinsicSize.Min).widthIn(min = 180.dp, max = 450.dp)
                    }
                }.hoverable(interactionSource)
                .onGloballyPositioned { coordinates ->
                    val pos = coordinates.positionInParent()
                    buttonPositionRef[0] = pos.x
                    buttonPositionRef[1] = pos.y
                    windowPosition = coordinates.positionInWindow()
                    buttonSizeRef[0] = coordinates.size.width
                    buttonSizeRef[1] = coordinates.size.height
                    // Register bounds for drag system (include actual index for LazyRow virtualization)
                    if (compositeTabId != null && tabDragComponent != null && tabIndex >= 0) {
                        val bounds = coordinates.boundsInWindow()
                        tabDragComponent.registerTabBounds(compositeTabId, bounds, tabIndex)
                    }
                }.pointerInput(contextMenuItems) {
                    // Handle right-click for context menu and middle-click to close (Issue #328)
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press) {
                                val awtEvent = event.nativeEvent as? java.awt.event.MouseEvent
                                // Middle-click (button 2): close tab
                                if (awtEvent?.button == 2) {
                                    // Launch on the composable's coroutine scope to properly trigger state updates
                                    closeScope.launch {
                                        onClose()
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                                // Right-click (button 3): show context menu
                                else if (awtEvent?.button == 3 && contextMenuItems.isNotEmpty()) {
                                    showContextMenu = true
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
                }.then(
                    if (isDragEnabled) {
                        Modifier.pointerInput(tabInfo, panelId, tabIndex) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    // Calculate absolute position for drag start
                                    val absolutePosition = windowPosition + offset
                                    tabDragComponent.startDragging(
                                        tabInfo = tabInfo,
                                        panelId = panelId,
                                        index = tabIndex,
                                        startPosition = absolutePosition,
                                    )
                                    onDragStart()
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    tabDragComponent.updateDrag(dragAmount)
                                },
                                onDragEnd = {
                                    // Always clean up drag state first to prevent stuck ghost
                                    val result = tabDragComponent.endDrag()
                                    onDragEnd(result)
                                },
                                onDragCancel = {
                                    tabDragComponent.cancelDrag()
                                },
                            )
                        }
                    } else {
                        Modifier
                    },
                ),
    ) {
        TextButton(
            modifier = Modifier.fillMaxHeight(),
            colors =
                ButtonDefaults.buttonColors(
                    backgroundColor = Color.Transparent,
                    contentColor = if (isSelected) colors.textPrimary else colors.textPrimary.copy(0.8f),
                ),
            contentPadding = PaddingValues(horizontal = space.sm),
            onClick = onClick,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Render icon based on type
                when {
                    // For bitmap images (favicons), use Image to preserve colors
                    tabIcon is ai.rever.boss.plugin.api.TabIcon.Image && painter != null -> {
                        Image(
                            painter = painter,
                            contentDescription = fileName,
                            modifier = Modifier.size(14.dp),
                        )
                    }

                    // For vector icons with custom tint (file type icons)
                    tabIcon is ai.rever.boss.plugin.api.TabIcon.Vector && tabIcon.tint != null && painter != null -> {
                        val tintColor = tabIcon.tint // Local copy for smart cast
                        Icon(
                            painter = painter,
                            contentDescription = fileName,
                            modifier = Modifier.size(14.dp),
                            tint = tintColor!!,
                        )
                    }

                    // For vector icons without tint, use default
                    painter != null -> {
                        Icon(
                            painter = painter,
                            contentDescription = fileName,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }

                Text(
                    text = fileName,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    textAlign = TextAlign.Start,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    softWrap = false,
                )
                // Only show close icon when needed to save space
                if (isSelected || isHovered) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Close $fileName",
                        modifier =
                            Modifier
                                .size(12.dp)
                                .clickable(onClick = onClose),
                    )
                }
            }
        }

        if (isSelected) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(
                            // Signature element: the active-tab marker wears the amber
                            // signal when focused, and a quiet line when not.
                            color = if (isFocused) colors.signal else colors.line,
                            shape = RoundedCornerShape(2.dp),
                        ),
            )
        }
    }
}
