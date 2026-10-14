package ai.rever.boss.components.sidebar

import ai.rever.boss.components.window_panel.components.main_window_panels.TabGlyph
import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabInfo
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun PaneChipLabel(
    icon: TabIcon?,
    tab: TabInfo,
    active: Boolean,
    width: Dp?,
) {
    // Balance the leading close target so the icon is centered over the whole tab.
    val modifier = if (width == null) Modifier else Modifier.fillMaxWidth().padding(end = 20.dp)
    Box(modifier, contentAlignment = Alignment.Center) {
        TabGlyph(icon, tab, active)
    }
}

@Composable
internal fun paneChipSurface(
    width: Dp?,
    background: Color,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
): Modifier =
    if (width == null) {
        Modifier
    } else {
        Modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .clickable(interactionSource = interactionSource, indication = LocalIndication.current, onClick = onClick)
    }

/** Pane tabs paint their shared background once, on the enclosing row. */
internal fun Modifier.paneChipContentBackground(
    width: Dp?,
    background: Color,
): Modifier = if (width == null) background(background) else this

/** Pane strips handle selection on the complete oval; compact chips retain their own target. */
internal fun Modifier.paneChipContentClick(
    width: Dp?,
    onClick: () -> Unit,
): Modifier = if (width == null) clickable(onClick = onClick) else this
