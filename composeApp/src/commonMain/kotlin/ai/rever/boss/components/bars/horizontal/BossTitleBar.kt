package ai.rever.boss.components.bars.horizontal

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BossTitleBar(
    title: String = "Boss Console",
    height: Dp = 26.dp,
    onToggleMaximize: (() -> Unit)? = null
) {
    HorizontalBar(
        modifier = Modifier.pointerInput(Unit) {
            detectTapGestures(
                onDoubleTap = {
                    onToggleMaximize?.invoke()
                }
            )
        },
        height = height
    ) {
        Text(
            text = title,
            color = BossTheme.colors.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
        )
    }
    Divider(color = BossTheme.colors.line)
}
