package ai.rever.boss.components.bars.vertical

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

@Composable
fun VerticalBar(width: Dp, content: @Composable BoxScope.() -> Unit) {
    // Title bar with BOSS centered
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(width)
            .background(BossTheme.colors.raised)
    ) {
        content()
    }
}
