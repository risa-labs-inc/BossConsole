package ai.rever.boss.components.dividers

import BossDarkBorder
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Divider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SDivider() {
    Divider(
        modifier = Modifier.padding(8.dp),
        color = BossDarkBorder
    )
}
