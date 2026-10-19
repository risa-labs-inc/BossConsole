package ai.rever.boss.components.overlays

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

/** Pointer rest time before the tooltip appears, matching `BossTabButton`'s. */
private const val TOOLTIP_DELAY_MS = 500L

/**
 * A hover tooltip that layers correctly above a heavyweight browser surface.
 *
 * Exists because a plain `TooltipArea` is wrong wherever the tooltip can extend over panel
 * content: under HARDWARE_ACCELERATED JxBrowser the page is a native surface composited ABOVE
 * the Compose scene, so a lightweight `Popup` renders behind it and the tooltip is invisible
 * over exactly the tabs people spend most of their time in. The branch below is the same one
 * `BossTabButton` takes for its own title tooltip.
 *
 * Deliberately NOT shared with `BossTabButton` yet. That one's anchor `Box` also owns the
 * drag-bounds registration, the middle-click/right-click `pointerInput`, and the position refs
 * the context menu is placed from; folding it into this wrapper would reshape the node the
 * whole tab drag system measures, which is not a change to make in passing. If a third caller
 * appears, unify then.
 *
 * @param text tooltip content. Blank shows nothing.
 * @param placement where the card sits relative to the anchor.
 */
@Composable
fun HoverTooltipBox(
    text: String,
    modifier: Modifier = Modifier,
    placement: TooltipPlacement = TooltipPlacement.END,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    var showTooltip by remember { mutableStateOf(false) }

    LaunchedEffect(isHovered, text) {
        if (!isHovered || text.isBlank()) {
            showTooltip = false
            return@LaunchedEffect
        }
        delay(TOOLTIP_DELAY_MS)
        showTooltip = isHovered
    }

    Box(
        modifier = modifier.hoverable(interactionSource),
        contentAlignment = contentAlignment,
    ) {
        content()
        if (showTooltip) {
            val heavyweightTooltip = OverlayConfig.heavyweightTooltip
            if (OverlayConfig.useHeavyweightPopups && heavyweightTooltip != null) {
                DisposableEffect(text) {
                    heavyweightTooltip(text)
                    onDispose { OverlayConfig.hideHeavyweightTooltip?.invoke() }
                }
            } else {
                // Composed INSIDE the anchor Box, so the popup's anchor bounds are this Box - and
                // placed by a PopupPositionProvider, which Compose calls with the popup's MEASURED
                // size. The previous version computed an offset in composition from sizes recorded
                // by onGloballyPositioned: on the first frame the card's size was still 0, and the
                // window-space anchor position was applied as an offset relative to the caller's
                // layout, so the card could open on top of its own anchor. Covering the anchor
                // ends the hover, which hides the card, which restores the hover - the tooltip
                // blinked on and off for as long as the pointer rested there.
                val provider = remember(placement) { TooltipPositionProvider(placement) }
                Popup(
                    popupPositionProvider = provider,
                    properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
                ) {
                    TooltipCard(text = text)
                }
            }
        }
    }
}

/** The tooltip card itself. */
@Composable
private fun TooltipCard(text: String) {
    Surface(
        color = BossTheme.colors.raised,
        shape = RoundedCornerShape(BossTheme.radius.input),
    ) {
        Text(
            text = text,
            color = BossTheme.colors.textPrimary,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** Which side of its anchor a [HoverTooltipBox] card sits on. */
enum class TooltipPlacement {
    /** Centered above the anchor. What a horizontal tab bar wants. */
    TOP,

    /** Vertically centered to the anchor's trailing side. What a left rail wants. */
    END,
}

/** Gap between the anchor and the tooltip card. */
private const val TOOLTIP_GAP_PX = 6

private class TooltipPositionProvider(
    private val placement: TooltipPlacement,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = tooltipPosition(placement, anchorBounds, windowSize, popupContentSize)
}

/**
 * Where a tooltip card of [popup] size goes against [anchor], in window coordinates, kept inside
 * [window]. The preferred side is used when the card fits there; otherwise it flips (TOP to below,
 * END to the leading side), because overlapping the anchor is what made the tooltip blink (see
 * the call site). When neither side fits, the final clamp keeps the card visible, and visible
 * wins over not overlapping: a card larger than the space around its anchor can then cover it.
 */
internal fun tooltipPosition(
    placement: TooltipPlacement,
    anchor: IntRect,
    window: IntSize,
    popup: IntSize,
): IntOffset {
    fun clampX(x: Int) = x.coerceIn(0, (window.width - popup.width).coerceAtLeast(0))

    fun clampY(y: Int) = y.coerceIn(0, (window.height - popup.height).coerceAtLeast(0))
    return when (placement) {
        TooltipPlacement.TOP -> {
            val above = anchor.top - popup.height - TOOLTIP_GAP_PX
            val y = if (above >= 0) above else anchor.bottom + TOOLTIP_GAP_PX
            IntOffset(clampX(anchor.left + (anchor.width - popup.width) / 2), clampY(y))
        }

        TooltipPlacement.END -> {
            val after = anchor.right + TOOLTIP_GAP_PX
            val x = if (after + popup.width <= window.width) after else anchor.left - popup.width - TOOLTIP_GAP_PX
            IntOffset(clampX(x), clampY(anchor.top + (anchor.height - popup.height) / 2))
        }
    }
}
