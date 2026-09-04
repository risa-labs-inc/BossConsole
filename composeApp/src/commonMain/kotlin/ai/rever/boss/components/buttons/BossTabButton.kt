package ai.rever.boss.components.buttons

import ai.rever.boss.components.model.TabDraggableComponent
import ai.rever.boss.components.model.TabDropResult
import ai.rever.boss.components.plugin.tab_types.fluck.FluckTabInfo
import ai.rever.boss.components.overlays.ContextMenu
import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.components.overlays.OverlayConfig
import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.VolumeUp
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

/**
 * Fill behind the selected tab in the pane being worked in.
 *
 * Enough amber to find without scanning, little enough that a column of tabs does not become a
 * column of colour. The marker beside it is the saturated version of the same statement.
 */
private const val SELECTED_FILL_ALPHA = 0.16f

/** Fill behind the selected tab of a pane that is not the active one. Present, but not amber. */
private const val INACTIVE_FILL_ALPHA = 0.35f

/** Fill under the pointer. Below the selected fill, so hovering the selected tab does not flatten it. */
private const val HOVER_FILL_ALPHA = 0.55f

/** The pin glyph on a pinned tab. Smaller than the close beside it: it is a state, not a button. */
private val PIN_INDICATOR_SIZE = 10.dp

/**
 * The speaker glyph on a tab that is producing sound (issue #308). One size step above
 * the pin: it has to be findable by scanning a full bar, which is the entire question
 * it exists to answer.
 */
private val AUDIO_INDICATOR_SIZE = 12.dp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BossTabButton(
    fileName: String,
    icon: ImageVector? = null,
    iconPainter: Painter? = null,
    tabIcon: TabIcon? = null,
    isSelected: Boolean = false,
    /**
     * This tab is pinned.
     *
     * Pinning already puts a tab first in its panel and, in a bar showing one pane, under a
     * "PINNED" heading. Neither survives contact with a split: several panes' pinned blocks run
     * together down one column, and the headings are dropped there because the rules between
     * panes are already doing that job. So the tab itself has to say it.
     */
    isPinned: Boolean = false,
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
    /**
     * Lay this tab out as a row in a LEFT tab bar rather than a column in a TOP one.
     *
     * Two things change and nothing else does. The tab fills the bar's width and takes a fixed
     * [tabHeight] instead of filling the bar's height and taking a computed [tabWidth]; and the
     * active marker moves from a underline at the bottom to a bar down the leading edge, which
     * is where a vertical list's selection reads.
     *
     * Everything the tab DOES - favicon, title badge, hover/selected close button, middle-click
     * close, right-click menu, drag gestures and the bounds they register - is shared, because
     * none of it is about which way the bar runs.
     */
    vertical: Boolean = false,
    /** Fixed row height in [vertical] mode. Ignored otherwise. */
    tabHeight: Dp = 32.dp,
    /**
     * Optional marker drawn immediately after the title, before the close button. Used for the
     * plugin build tag, which qualifies the title (this panel is not running the released build) and
     * so belongs beside it rather than out at the tab's edge.
     */
    titleBadge: (@Composable () -> Unit)? = null,
    // Drag-related parameters
    tabDragComponent: TabDraggableComponent? = null,
    tabInfo: TabInfo? = null,
    panelId: String? = null,
    tabIndex: Int = -1,
    onDragStart: () -> Unit = {},
    onDragEnd: (TabDropResult?) -> Unit = {},
    /**
     * Reports whether this tab's context menu is open.
     *
     * Exists for one owner: the vertical bar's hover-reveal drawer, which is disposed when the
     * pointer leaves it. A right-click opens the menu in its own popup, the pointer moves off the
     * drawer to reach it, and the drawer would retract and take the menu's composition with it.
     * So hover cannot be the only vote on whether the drawer stays. Default no-op, which is what
     * every owner whose lifetime is not tied to the pointer wants.
     */
    onContextMenuVisibilityChange: (Boolean) -> Unit = {},
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
        val heavyweightTooltip = OverlayConfig.heavyweightTooltip
        if (OverlayConfig.useHeavyweightPopups && heavyweightTooltip != null) {
            // HARDWARE_ACCELERATED: a lightweight Compose Popup renders BEHIND the
            // browser's heavyweight surface, so a tab tooltip over a browser tab would
            // be hidden by the page. Show it in a small native window instead.
            // OFF_SCREEN keeps the Compose path below unchanged.
            DisposableEffect(fileName) {
                heavyweightTooltip(fileName)
                onDispose { OverlayConfig.hideHeavyweightTooltip?.invoke() }
            }
        } else {
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
    }

    // State for context menu
    var showContextMenu by remember { mutableStateOf(false) }

    // Read by the long-lived pointer handler below, which must not restart when these change.
    val currentMenuItems by rememberUpdatedState(contextMenuItems)
    val currentOnClose by rememberUpdatedState(onClose)

    // Reported on change rather than written from the setter, so a tab disposed with its menu
    // still open (closing the tab from the menu does exactly that) clears the flag it set.
    // Strictly paired: one `true` when the menu opens, one `false` when that same effect is
    // disposed - whether because the menu closed or because the row left composition.
    //
    // Reporting `showContextMenu` unconditionally instead looks equivalent and is not. The
    // listener counts these as deltas, so the `false` every row emits on its FIRST composition
    // decrements a count it never incremented: a menu open in the hover drawer was cancelled by
    // any other row mounting - a scroll of the shared column, or a background pane opening a tab
    // - which retracted the drawer and took the open menu with it. Closing a menu reported
    // `false` twice for the same reason (once from onDispose, once from the re-run body).
    val latestContextMenuVisibility by rememberUpdatedState(onContextMenuVisibilityChange)
    DisposableEffect(showContextMenu) {
        if (showContextMenu) latestContextMenuVisibility(true)
        onDispose { if (showContextMenu) latestContextMenuVisibility(false) }
    }

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

    // The tab's own surface, in order: selected in the pane being worked in, selected in a
    // background pane, merely under the pointer, none of those.
    //
    // A 3dp marker on its own was not enough to find the selected tab at a glance, especially
    // down a vertical bar where every row is the same width and the marker is a sliver at the
    // far edge. The fill is what the eye lands on; the marker says which pane it belongs to.
    //
    // The second case is neutral rather than amber because the amber is a claim about where the
    // user IS, and two panes both making it is the confusion the marker's own focused/unfocused
    // split already exists to avoid.
    //
    // ALPHA over the theme's colours rather than a fixed wash: this row is drawn on `panel` in
    // the tab bar and could be drawn on another surface elsewhere, and a tint composited over
    // whatever is behind it is right in both places, in either theme. signalWash is the fixed
    // amber-on-ink equivalent and would be a hair off wherever ink is not what is underneath.
    val tabSurface =
        when {
            isSelected && isFocused -> colors.signal.copy(alpha = SELECTED_FILL_ALPHA)
            isSelected -> colors.lineStrong.copy(alpha = INACTIVE_FILL_ALPHA)
            isHovered -> colors.raised.copy(alpha = HOVER_FILL_ALPHA)
            else -> Color.Transparent
        }

    Box(
        modifier =
            modifier
                .let { base ->
                    when {
                        // Vertical bar: the bar's width, a fixed row height. There is no width
                        // to negotiate, so tabWidth is not consulted at all.
                        vertical -> base.fillMaxWidth().height(tabHeight)

                        // Explicit width from the parent (Safari-style shrink-to-fit).
                        tabWidth != null -> base.fillMaxHeight().width(tabWidth)

                        // Legacy sizing: content-driven width clamped to 180–450 dp.
                        else -> base.fillMaxHeight().width(IntrinsicSize.Min).widthIn(min = 180.dp, max = 450.dp)
                    }
                }
                // Under the content and under the marker, which is drawn last so it stays a hard
                // edge against the fill rather than being tinted by it.
                .background(color = tabSurface, shape = RoundedCornerShape(radii.input))
                .hoverable(interactionSource)
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
                }.pointerInput(Unit) {
                    // Handle right-click for context menu and middle-click to close (Issue #328)
                    //
                    // Keyed on Unit, and the two things it needs are read through
                    // rememberUpdatedState instead.
                    //
                    // It used to be keyed on contextMenuItems, which is a fresh list of freshly
                    // built lambdas on every composition - so the key differed every time and
                    // this coroutine was cancelled and restarted on every recomposition of the
                    // tab (hover, favicon load, any tab-state change). A right-click landing in
                    // that gap found no handler here, went unconsumed, and the tab bar's own
                    // menu answered it instead. That is the "sometimes the wrong menu" bug.
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press) {
                                val awtEvent = event.nativeEvent as? java.awt.event.MouseEvent
                                // Middle-click (button 2): close tab
                                if (awtEvent?.button == 2) {
                                    // Launch on the composable's coroutine scope to properly trigger state updates
                                    closeScope.launch {
                                        currentOnClose()
                                    }
                                    event.changes.forEach { it.consume() }
                                } else if (awtEvent?.button == 3 && currentMenuItems.isNotEmpty()) {
                                    // Right-click (button 3): show context menu
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
                titleBadge?.invoke()

                // Speaker glyph while this tab is producing sound (issue #308), read straight
                // off tabInfo rather than a parameter: FluckTabInfo carries live playback
                // state pushed from Chromium's audio events, and leaving this composable's
                // signature unchanged keeps detekt's signature-keyed baseline entries valid.
                val isPlayingAudio = (tabInfo as? FluckTabInfo)?.isPlayingAudio == true

                // The alpha animation runs unconditionally - Compose state, cheap at either
                // target - and the icon only composes while visible, so a silent tab's row
                // measures exactly what it did before this feature existed.
                val audioAlpha by animateFloatAsState(
                    targetValue = if (isPlayingAudio) 1f else 0f,
                    animationSpec = tween(durationMillis = 200),
                    label = "audioPlayingIndicator",
                )
                if (audioAlpha > 0.01f) {
                    Icon(
                        imageVector = Icons.Filled.VolumeUp,
                        contentDescription = "Playing audio",
                        tint = colors.signal,
                        modifier =
                            Modifier
                                .size(AUDIO_INDICATOR_SIZE)
                                .alpha(audioAlpha),
                    )
                }

                // Always drawn, unlike the close icon beside it: a tab is pinned whether or not
                // the pointer is anywhere near it, and an indicator you have to hover to see
                // cannot answer "which of these are pinned" at a glance - which is the only
                // question it exists for.
                if (isPinned) {
                    Icon(
                        imageVector = Icons.Filled.PushPin,
                        contentDescription = "Pinned",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(PIN_INDICATOR_SIZE),
                    )
                }

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
                        .let { base ->
                            // The marker runs along the edge the bar is anchored to, so it reads
                            // as "this row of the list" in a column and "this column of the strip"
                            // in a row. A bottom underline on a vertical tab would sit between two
                            // stacked tabs and belong to neither.
                            if (vertical) {
                                base.align(Alignment.CenterStart).fillMaxHeight().width(3.dp)
                            } else {
                                base.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp)
                            }
                        }.background(
                            // Signature element: the active-tab marker wears the amber
                            // signal when focused, and a quiet line when not.
                            color = if (isFocused) colors.signal else colors.line,
                            shape = RoundedCornerShape(2.dp),
                        ),
            )
        }
    }
}
