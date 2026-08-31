package ai.rever.boss.components.bars.horizontal

import ai.rever.boss.layout.BossChrome
import ai.rever.boss.layout.CAPTURED_BUTTON_START
import ai.rever.boss.layout.CAPTURED_BUTTON_TOP
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

@Composable
fun BossTitleBar(
    title: String = "Boss Console",
    height: Dp = BossChrome.dimens.titleBarHeight,
    onToggleMaximize: (() -> Unit)? = null,
    /**
     * Drawn as a fourth traffic light, at [CAPTURED_BUTTON_START] in from the start.
     *
     * This row is where the lights are, so it is the only chrome where "next to the traffic
     * lights" is a position that exists. When the row is not drawn - the top bar or a left column
     * is carrying the clearance instead - the caller puts the same control at the start of
     * whichever bar took it. See `macTrafficLightInset`.
     */
    leading: (@Composable () -> Unit)? = null,
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
        height = height,
    ) {
        leading?.let {
            Box(
                modifier =
                    Modifier
                        // Top, not CenterStart: the lights sit 16pt below the window's edge, which
                        // is not the middle of a 26dp row. See CAPTURED_BUTTON_TOP.
                        .align(Alignment.TopStart)
                        .padding(start = CAPTURED_BUTTON_START, top = CAPTURED_BUTTON_TOP),
                contentAlignment = Alignment.Center,
            ) { it() }
        }

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
    }
    Divider(color = BossTheme.colors.line, thickness = BossChrome.dimens.dividerThickness)
}
