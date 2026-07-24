package ai.rever.boss.components.window_panel.components.main_window_panels

import ContextMenuBackground
import ContextMenuBorder
import ContextMenuHover
import ai.rever.boss.components.common.rememberFaviconLoader
import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val OVERLAY_REVEAL_DELAY_MS = 180L

/**
 * Hosts the MRU tab-switcher overlay with a short reveal delay so a quick tap-to-switch
 * (press-and-release within the delay) doesn't flash the switcher. Renders nothing in
 * positional mode, where [data] stays null.
 *
 * Call this from within a `Box` and pass `Modifier.align(...)` to position the card.
 */
@Composable
fun TabCycleOverlayHost(
    data: TabCycleOverlayData?,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(data == null) {
        if (data == null) {
            visible = false
        } else {
            delay(OVERLAY_REVEAL_DELAY_MS)
            visible = true
        }
    }
    if (data != null && visible) {
        TabCycleOverlay(data = data, modifier = modifier)
    }
}

/**
 * The switcher card: a vertical list of the open tabs in MRU cycle order with the
 * currently-highlighted candidate emphasized (VS Code Ctrl+Tab style).
 *
 * Styled to match the app's floating surfaces: the same background/border tokens and
 * 8.dp radius as dialogs (e.g. NewTabDialog), context-menu row metrics (16.dp icon,
 * 13.sp label, 12.dp horizontal padding, ContextMenuHover for the active row), and the
 * BossTheme.colors.signal / FontWeight.Medium selection emphasis used by the tab bar.
 */
@Composable
private fun TabCycleOverlay(
    data: TabCycleOverlayData,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .background(ContextMenuBackground, RoundedCornerShape(8.dp))
                .border(1.dp, ContextMenuBorder, RoundedCornerShape(8.dp))
                .widthIn(min = 280.dp, max = 460.dp)
                .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        data.tabs.forEachIndexed { index, tab ->
            val highlighted = index == data.highlightedIndex
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (highlighted) ContextMenuHover else Color.Transparent)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TabCycleIcon(tab = tab, highlighted = highlighted)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = tab.title.ifBlank { "Untitled" },
                    color = if (highlighted) BossTheme.colors.textPrimary else BossTheme.colors.textSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (highlighted) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Renders a tab's icon exactly as the tab bar does (see BossTabButton): the live favicon
 * for browser tabs, the tab's tinted vector for typed tabs, falling back to the static
 * vector icon. Favicons and tinted vectors keep their own colors; the plain vector
 * fallback follows the row's emphasis so it reads like the rest of the list.
 */
@Composable
private fun TabCycleIcon(
    tab: TabInfo,
    highlighted: Boolean,
) {
    // Same resolution order as BossTabButtonWithFavicon: loaded favicon > tabIcon > icon.
    val effectiveTabIcon = rememberFaviconLoader(tab) ?: tab.tabIcon
    val painter = effectiveTabIcon?.asPainter() ?: rememberVectorPainter(tab.icon)
    when {
        // Bitmap favicons: use Image to preserve their colors.
        effectiveTabIcon is TabIcon.Image -> {
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }

        // Vector icons with a custom tint (e.g. file-type colors).
        effectiveTabIcon is TabIcon.Vector && effectiveTabIcon.tint != null -> {
            val tintColor = effectiveTabIcon.tint
            Icon(
                painter = painter,
                contentDescription = null,
                tint = tintColor!!,
                modifier = Modifier.size(16.dp),
            )
        }

        // Plain vector icons / fallback: follow the row emphasis.
        else -> {
            Icon(
                painter = painter,
                contentDescription = null,
                tint = if (highlighted) BossTheme.colors.textPrimary else BossTheme.colors.textSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
