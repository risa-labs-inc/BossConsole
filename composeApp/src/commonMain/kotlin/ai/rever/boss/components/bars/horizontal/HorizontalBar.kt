package ai.rever.boss.components.bars.horizontal

import BossDarkSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

@Composable
fun HorizontalBar(
    modifier: Modifier = Modifier,
    backgroundColor: Color = BossDarkSurface,
    height: Dp,
    content: @Composable BoxScope.() -> Unit
) {
    // Title bar with BOSS centered
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(backgroundColor),
        content = content
    )
}

@Composable
fun HorizontalBarRow(modifier: Modifier = Modifier.fillMaxHeight(),
                     vertical: Alignment.Vertical = Alignment.CenterVertically,
                              content: @Composable RowScope.() -> Unit) {
    Row(modifier = modifier, verticalAlignment = vertical, content = content)
}
