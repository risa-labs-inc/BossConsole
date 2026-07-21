package ai.rever.boss.components.dividers

import BossDarkBorder
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material.Divider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun VDivider(modifier: Modifier = Modifier) {
    Divider(
        modifier = modifier
            .fillMaxHeight()
            .width(1.dp),
        color = BossDarkBorder
    )
}

