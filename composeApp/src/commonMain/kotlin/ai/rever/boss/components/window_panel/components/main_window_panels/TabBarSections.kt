package ai.rever.boss.components.window_panel.components.main_window_panels

import ai.rever.boss.components.overlays.HoverTooltipBox
import ai.rever.boss.components.overlays.TooltipPlacement
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The vertical tab bar's list chrome: section headers, the rule between sections, and the
 * New Tab row.
 *
 * Split from `BossVerticalTabBar.kt` because these are rows INSIDE the scrolling list, while that
 * file is about the bar and rail that contain it - and because the two together crossed detekt's
 * per-file function count, which is a fair signal that one file was doing two jobs.
 */

/** Height of a section header row ("PINNED" / "OPEN"), which also hosts that section's hover "+". */
private val SECTION_HEADER_HEIGHT = 24.dp

/** Height of the Arc-style "New Tab" row. */
private val NEW_TAB_ROW_HEIGHT = 32.dp

/**
 * One section header in the vertical bar: a quiet label, and a "+" that fades in on hover.
 *
 * Arc draws no label above its pinned block - just a separator line. A label is used here for two
 * reasons: this bar has two sections whose difference is not visually obvious the way a favicon
 * grid is, and the header is what the per-section "+" hangs off. Both are set small, uppercase and
 * muted so they read as chrome rather than as content.
 *
 * The "+" appears on hover rather than always, so the resting bar stays as quiet as Arc's. It is
 * still reachable without hovering: the row at the bottom of the bar is always visible, and it is
 * the same action for the Open section.
 */
@Composable
fun SectionHeader(
    label: String,
    onAdd: () -> Unit,
    addHint: String,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(SECTION_HEADER_HEIGHT)
                .hoverable(interactionSource)
                .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = BossTheme.colors.textSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Space is reserved whether or not the icon is drawn, so the label does not shift
        // sideways as the pointer crosses the row.
        Box(modifier = Modifier.size(SECTION_HEADER_HEIGHT), contentAlignment = Alignment.Center) {
            if (hovered) {
                // The whole 20dp box is the target, not the 14dp glyph inside it: a header "+"
                // that only exists while the pointer is on the row is hard enough to hit without
                // also being a pixel hunt.
                HoverTooltipBox(
                    text = addHint,
                    placement = TooltipPlacement.END,
                    modifier =
                        Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(onClick = onAdd),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = addHint,
                        tint = BossTheme.colors.textSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

/**
 * The line between the pinned block and the open one, plus the Open section's own header.
 *
 * Arc's equivalent is a single thin rule with no label. The label is kept here because this bar
 * carries two named sections rather than a favicon grid and a list, and because it is what the
 * section's "+" hangs off.
 */
@Composable
fun SectionBreak(onAdd: () -> Unit) {
    Divider(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        color = BossTheme.colors.line,
    )
    SectionHeader(label = "OPEN", onAdd = onAdd, addHint = "New tab")
}

/**
 * The "New Tab" row at the head of the bar, directly under its header - which is where Arc puts
 * it, before the first tab rather than after the last.
 *
 * Full width and left-aligned rather than a centred square button, so it reads as a row of the
 * list it extends rather than as a floating control - which is the whole difference between this
 * and the "+" the horizontal strip uses, where a row would have nowhere to sit.
 *
 * A window bar draws one per pane, at the head of that pane's group, so "+" always adds a tab to
 * the pane whose rows it sits among.
 */
@Composable
fun NewTabRow(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(NEW_TAB_ROW_HEIGHT)
                .hoverable(interactionSource)
                .background(if (hovered) BossTheme.colors.raised else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            contentDescription = null,
            tint = BossTheme.colors.textSecondary,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = "New Tab",
            color = BossTheme.colors.textSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
