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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
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

    // Non-observable holders, matching BossTabButton: writing these from onGloballyPositioned
    // as snapshot state would remeasure during the layout phase. The cost is that an open
    // tooltip does not follow an anchor that moves under it, which for a hover tooltip is
    // nothing - the pointer moving is what moved it.
    val anchorPosition = remember { floatArrayOf(0f, 0f) }
    val anchorSize = remember { intArrayOf(0, 0) }
    val tooltipSize = remember { intArrayOf(0, 0) }

    LaunchedEffect(isHovered, text) {
        if (!isHovered || text.isBlank()) {
            showTooltip = false
            return@LaunchedEffect
        }
        delay(TOOLTIP_DELAY_MS)
        showTooltip = isHovered
    }

    if (showTooltip) {
        val heavyweightTooltip = OverlayConfig.heavyweightTooltip
        if (OverlayConfig.useHeavyweightPopups && heavyweightTooltip != null) {
            DisposableEffect(text) {
                heavyweightTooltip(text)
                onDispose { OverlayConfig.hideHeavyweightTooltip?.invoke() }
            }
        } else {
            Popup(
                alignment = Alignment.TopStart,
                offset = tooltipOffset(placement, anchorPosition, anchorSize, tooltipSize),
                properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
            ) {
                TooltipCard(text = text, onMeasured = { w, h ->
                    tooltipSize[0] = w
                    tooltipSize[1] = h
                })
            }
        }
    }

    Box(
        modifier =
            modifier
                .hoverable(interactionSource)
                .onGloballyPositioned { coordinates ->
                    val pos = coordinates.positionInWindow()
                    anchorPosition[0] = pos.x
                    anchorPosition[1] = pos.y
                    anchorSize[0] = coordinates.size.width
                    anchorSize[1] = coordinates.size.height
                },
        contentAlignment = contentAlignment,
        content = content,
    )
}

/**
 * The tooltip card itself.
 *
 * [onMeasured] reports its size back so the offset can centre it against the anchor. It has to be
 * measured rather than estimated, because that is what decides where the card goes and the text
 * inside it is arbitrary.
 */
@Composable
private fun TooltipCard(
    text: String,
    onMeasured: (Int, Int) -> Unit,
) {
    Surface(
        modifier =
            Modifier.onGloballyPositioned { coordinates ->
                onMeasured(coordinates.size.width, coordinates.size.height)
            },
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

private fun tooltipOffset(
    placement: TooltipPlacement,
    anchorPosition: FloatArray,
    anchorSize: IntArray,
    tooltipSize: IntArray,
): IntOffset =
    when (placement) {
        TooltipPlacement.TOP -> {
            IntOffset(
                x = anchorPosition[0].toInt() + (anchorSize[0] - tooltipSize[0]) / 2,
                y = anchorPosition[1].toInt() - tooltipSize[1] - TOOLTIP_GAP_PX,
            )
        }

        TooltipPlacement.END -> {
            IntOffset(
                x = anchorPosition[0].toInt() + anchorSize[0] + TOOLTIP_GAP_PX,
                y = anchorPosition[1].toInt() + (anchorSize[1] - tooltipSize[1]) / 2,
            )
        }
    }
