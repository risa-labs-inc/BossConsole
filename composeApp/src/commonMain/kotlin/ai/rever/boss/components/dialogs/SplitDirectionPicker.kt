package ai.rever.boss.components.dialogs

import ai.rever.boss.components.overlays.HoverTooltipBox
import ai.rever.boss.components.overlays.TooltipPlacement
import ai.rever.boss.components.window_panel.SplitDirection
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The glyph inside a chip: the same 4:3 window the tab bar's group headers draw. */
private val GLYPH_WIDTH = 16.dp
private val GLYPH_HEIGHT = 12.dp

/** The chip around it, big enough to be a comfortable click target at the dialog's scale. */
private val CHIP_SIZE = 24.dp

/**
 * Where a new tab goes: this pane, or a new one on a given side.
 *
 * Five chips rather than a checkbox, because "open in a split" is not one question - it is
 * "which side", and a checkbox would have had to pick a default side silently. Each chip draws
 * the window with the new tab's half filled, the same way a group header draws which pane its
 * tabs belong to, so the answer is legible without reading any of the labels.
 *
 * [selected] is null for "this pane", which is the leading chip and the default. Choosing a side
 * does not split anything yet: it records the request, and the split happens when the tab is
 * actually created. A pane made before there is a tab to put in it is closed again about 50ms
 * later by checkAndCloseEmptyPanels, which is why nothing here acts on the click directly.
 */
@Composable
internal fun SplitDirectionPicker(
    selected: SplitDirection?,
    onSelect: (SplitDirection?) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "Open in",
            color = BossTheme.colors.textSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(end = 4.dp),
        )

        DirectionChip(
            label = "This pane",
            fill = Fill(0f, 0f, 1f, 1f),
            isSelected = selected == null,
            onClick = { onSelect(null) },
        )

        SplitDirection.entries.forEach { direction ->
            DirectionChip(
                label = "New pane ${direction.displayName.lowercase()}",
                fill = direction.fill(),
                isSelected = selected == direction,
                onClick = { onSelect(direction) },
            )
        }
    }
}

/** The part of the window the new tab would occupy, as fractions of it. */
private data class Fill(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

private fun SplitDirection.fill(): Fill =
    when (this) {
        SplitDirection.LEFT -> Fill(0f, 0f, 0.5f, 1f)
        SplitDirection.RIGHT -> Fill(0.5f, 0f, 1f, 1f)
        SplitDirection.UP -> Fill(0f, 0f, 1f, 0.5f)
        SplitDirection.DOWN -> Fill(0f, 0.5f, 1f, 1f)
    }

@Composable
private fun DirectionChip(
    label: String,
    fill: Fill,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colors = BossTheme.colors

    HoverTooltipBox(
        text = label,
        placement = TooltipPlacement.TOP,
        modifier = Modifier.size(CHIP_SIZE),
    ) {
        Box(
            modifier =
                Modifier
                    .size(CHIP_SIZE)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isSelected) colors.signal.copy(alpha = SELECTED_CHIP_ALPHA) else Color.Transparent)
                    .border(
                        width = 1.dp,
                        color = if (isSelected) colors.signal else Color.Transparent,
                        shape = RoundedCornerShape(4.dp),
                    ).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(width = GLYPH_WIDTH, height = GLYPH_HEIGHT)) {
                val stroke = 1.dp.toPx()
                val outline = if (isSelected) colors.onSignal else colors.textSecondary
                drawRect(
                    color = outline,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke),
                )
                // Inset by the outline so the fill sits INSIDE the frame rather than on top of
                // it: a pane that touches an edge should still read as bounded by the window.
                val inner = Size(size.width - stroke * 2f, size.height - stroke * 2f)
                drawRect(
                    color = outline,
                    topLeft = Offset(stroke + fill.left * inner.width, stroke + fill.top * inner.height),
                    size =
                        Size(
                            width = (fill.right - fill.left) * inner.width,
                            height = (fill.bottom - fill.top) * inner.height,
                        ),
                )
            }
        }
    }
}

/** Enough for the chosen chip to read as chosen without drowning the glyph inside it. */
private const val SELECTED_CHIP_ALPHA = 0.28f
