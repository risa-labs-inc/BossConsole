package ai.rever.boss.components.window_panel.components.main_window_panels

import ai.rever.boss.components.overlays.HoverTooltipBox
import ai.rever.boss.components.overlays.TooltipPlacement
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Height of a group header row, matching the section headers it sits alongside. */
private val GROUP_HEADER_HEIGHT = 24.dp

/** Height of the "n more tabs" row. Shorter than a tab, because it is chrome rather than content. */
private val SUMMARY_ROW_HEIGHT = 24.dp

/** Indented to sit under the pane's tabs rather than beside its header. */
private val SUMMARY_ROW_INDENT = 12.dp

/** The little split diagram. Wider than tall, because a window is. */
private val GLYPH_WIDTH = 16.dp
private val GLYPH_HEIGHT = 12.dp

/**
 * The row that says which pane a group of tabs belongs to.
 *
 * The rule alone said only "these are a different pane", which left the reader to work out WHICH
 * pane by opening tabs until something changed. This says it two ways at once: a diagram of the
 * split with this pane filled in, and a word for its position when one is honest ("Left", "Top")
 * or its number when none is.
 *
 * The diagram is the part that always works. Words run out at the first nested split, but a
 * filled rectangle inside an outlined one is exactly as true for three panes as for two, and it
 * follows a divider as it is dragged because it is drawn from the panes' measured rectangles.
 *
 * Clicking the row activates the pane, so the header is also the fastest way to move between
 * panes without touching their content.
 */
@Composable
internal fun TabBarGroupHeader(
    group: TabBarGroup,
    showRule: Boolean,
) {
    val colors = BossTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    // The active pane's header carries the signal, exactly as its active tab's marker does. One
    // group is highlighted at a time and it is always the same group, which is what makes the two
    // read as the same statement rather than two competing ones.
    val tint = if (group.isActive) colors.signalText else colors.textSecondary

    // Hovering the header is what chooses the open pane. It does not expand-while-hovered: see
    // TabGroupExpansion for why that collapses the group the moment the pointer moves onto it.
    val headerHover = remember { MutableInteractionSource() }
    val headerHovered by headerHover.collectIsHoveredAsState()
    LaunchedEffect(headerHovered) { if (headerHovered) group.hoverHeader() }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (showRule) {
            // FULL BLEED, where the divider between two tabs is inset by 8dp. On screen an inset
            // rule of the same weight was indistinguishable from just another gap between tabs.
            Divider(
                modifier = Modifier.fillMaxWidth().padding(top = GROUP_RULE_GAP),
                color = colors.line,
            )
        }
        GroupHeaderRow(group = group, tint = tint, hovered = hovered, hover = headerHover, press = interactionSource)
    }
}

/**
 * The header's one line: the split diagram, the pane's name, and its two actions.
 *
 * Its own composable because the hover it carries is two different things at once - a press
 * target that tints the row, and the signal that chooses which pane is open - and reading them
 * side by side is what makes it obvious they are not the same interaction.
 */
@Composable
private fun GroupHeaderRow(
    group: TabBarGroup,
    tint: Color,
    hovered: Boolean,
    hover: MutableInteractionSource,
    press: MutableInteractionSource,
) {
    val colors = BossTheme.colors
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(GROUP_HEADER_HEIGHT)
                .hoverable(hover)
                .hoverable(press)
                .background(if (hovered) colors.raised else Color.Transparent)
                .clickable(onClick = group.activate)
                .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HoverTooltipBox(
            text = "Go to this pane",
            placement = TooltipPlacement.END,
            modifier = Modifier.size(width = GLYPH_WIDTH, height = GLYPH_HEIGHT),
        ) {
            SplitPositionGlyph(glyph = group.glyph, tint = tint, outline = colors.line)
        }
        Text(
            text = group.label,
            color = tint,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // The pane's own two actions, on the pane's own row. They were a separate full-width
        // "New Tab" row per group, which cost a row of chrome per pane and still had nowhere
        // to put "close this pane".
        HeaderAction(
            icon = Icons.Default.Add,
            description = "New tab in ${group.label}",
            tint = colors.textSecondary,
            onClick = group.newTab,
        )
        group.close?.let { close ->
            HeaderAction(
                icon = Icons.Outlined.Close,
                description = "Close ${group.label}",
                tint = colors.textSecondary,
                onClick = close,
            )
        }
    }
}

/**
 * The line standing in for a collapsed pane's other tabs.
 *
 * Says how many there are and opens them when clicked. It is deliberately a row rather than a
 * caption: it is the thing you click, and a count with no affordance would read as a statement
 * about the pane instead of a way into it.
 */
@Composable
internal fun TabGroupSummaryRow(group: TabBarGroup) {
    val colors = BossTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val hidden = group.state.tabs.size - group.state.renderedTabCount

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(SUMMARY_ROW_HEIGHT)
                .hoverable(interactionSource)
                .background(if (hovered) colors.raised else Color.Transparent)
                .clickable(onClick = group.toggleExpanded)
                .padding(start = SUMMARY_ROW_INDENT, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = if (hidden == 1) "1 more tab" else "$hidden more tabs",
            color = colors.textSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * One icon button on a group header.
 *
 * Always drawn, not revealed on hover the way a section header's "+" is: those are a shortcut for
 * something the bar offers elsewhere too, while closing a pane is offered nowhere else in the bar
 * at all. A control that only exists once you are already pointing at it cannot be found by
 * someone looking for it.
 */
@Composable
private fun HeaderAction(
    icon: ImageVector,
    description: String,
    tint: Color,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    HoverTooltipBox(
        text = description,
        placement = TooltipPlacement.END,
        modifier =
            Modifier
                .size(GROUP_HEADER_HEIGHT)
                .hoverable(interactionSource)
                .clip(RoundedCornerShape(4.dp))
                .background(if (hovered) BossTheme.colors.raised else Color.Transparent)
                .clickable(onClick = onClick),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (hovered) BossTheme.colors.textPrimary else tint,
            modifier = Modifier.size(12.dp),
        )
    }
}

/**
 * The split, drawn: an outline of the whole area with this pane filled in.
 *
 * Nothing is drawn without a measured [glyph]. An unmeasured layout would otherwise render as a
 * filled box covering everything, which is a claim about the split rather than an absence of one.
 */
@Composable
private fun SplitPositionGlyph(
    glyph: PaneGlyph?,
    tint: Color,
    outline: Color,
) {
    Canvas(modifier = Modifier.size(width = GLYPH_WIDTH, height = GLYPH_HEIGHT)) {
        val stroke = 1.dp.toPx()
        drawRect(
            color = outline,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(width = stroke),
        )
        if (glyph == null) return@Canvas
        // Inset by the outline so the fill sits INSIDE the frame rather than on top of it: a pane
        // that touches an edge should still read as bounded by the window.
        val inner = Size(size.width - stroke * 2f, size.height - stroke * 2f)
        drawRect(
            color = tint,
            topLeft = Offset(stroke + glyph.left * inner.width, stroke + glyph.top * inner.height),
            size =
                Size(
                    width = (glyph.right - glyph.left) * inner.width,
                    height = (glyph.bottom - glyph.top) * inner.height,
                ),
        )
    }
}
