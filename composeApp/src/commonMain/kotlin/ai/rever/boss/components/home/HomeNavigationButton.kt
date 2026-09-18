package ai.rever.boss.components.home

import ai.rever.boss.components.overlays.HoverTooltipBox
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Built-in Home favorite; the collapsed rail uses the smaller target. */
@Composable
internal fun HomeNavigationButton(
    selected: Boolean,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val tint = if (selected || hovered || focused) MaterialTheme.colors.primary else BossTheme.colors.textPrimary
    val background = if (selected || hovered || focused) BossTheme.colors.signalWash else BossTheme.colors.raised
    HoverTooltipBox(text = "Go Home in this pane") {
        Box(
            modifier =
                Modifier
                    .size(if (compact) 36.dp else 40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(background)
                    .hoverable(interactionSource)
                    .semantics { this.selected = selected }
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Button,
                        onClick = onClick,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Home,
                contentDescription = "Home",
                modifier = Modifier.size(22.dp),
                tint = tint,
            )
        }
    }
}
