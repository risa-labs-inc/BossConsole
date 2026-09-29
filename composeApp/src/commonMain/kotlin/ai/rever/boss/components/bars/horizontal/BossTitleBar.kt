package ai.rever.boss.components.bars.horizontal

import ai.rever.boss.components.buttons.BossActionButton
import ai.rever.boss.layout.BossChrome
import ai.rever.boss.layout.TRAFFIC_LIGHT_WIDTH
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.utils.SystemUtils
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewSidebar
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
    height: Dp = BossChrome.dimens.titleBarHeight,
    onToggleMaximize: (() -> Unit)? = null,
    onToggleSidebar: (() -> Unit)? = null,
    sidebarExpanded: Boolean = false,
) {
    HorizontalBar(
        modifier =
            Modifier.pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        onToggleMaximize?.invoke()
                    },
                )
            },
        height = if (onToggleSidebar != null) height.coerceAtLeast(32.dp) else height,
    ) {
        Text(
            text = title,
            color = BossTheme.colors.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center),
        )
        if (onToggleSidebar != null) {
            BossActionButton(
                imageVector = Icons.AutoMirrored.Outlined.ViewSidebar,
                text = if (sidebarExpanded) "Hide sidebar" else "Show sidebar",
                hintText = if (sidebarExpanded) "Hide sidebar" else "Show sidebar",
                isSelected = sidebarExpanded,
                iconSize = 18.dp,
                modifier =
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = if (SystemUtils.isMacOS) TRAFFIC_LIGHT_WIDTH + 6.dp else 6.dp)
                        .size(28.dp),
                onClick = onToggleSidebar,
            )
        }
    }
    Divider(color = BossTheme.colors.line, thickness = BossChrome.dimens.dividerThickness)
}
