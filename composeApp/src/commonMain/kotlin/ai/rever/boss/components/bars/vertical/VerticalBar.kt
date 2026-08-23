package ai.rever.boss.components.bars.vertical

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

@Composable
fun VerticalBar(
    width: Dp,
    modifier: Modifier = Modifier,
    // Parameterised to match HorizontalBar, which has always taken one: the window's icon
    // strips want `raised`, but a panel's vertical tab bar wants `panel` - the same token its
    // horizontal counterpart uses, and the same one BossMainPanel fills its border ring with.
    backgroundColor: Color = BossTheme.colors.raised,
    content: @Composable BoxScope.() -> Unit,
) {
    // Title bar with BOSS centered
    Box(
        modifier =
            modifier
                .fillMaxHeight()
                .width(width)
                .background(backgroundColor),
    ) {
        content()
    }
}
