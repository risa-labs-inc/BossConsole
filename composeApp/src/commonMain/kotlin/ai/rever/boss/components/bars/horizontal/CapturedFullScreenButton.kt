package ai.rever.boss.components.bars.horizontal

import ai.rever.boss.components.overlays.HoverTooltipBox
import ai.rever.boss.components.overlays.TooltipPlacement
import ai.rever.boss.layout.TRAFFIC_LIGHT_DIAMETER
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.onClick
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * The blue circle that enters and leaves captured full screen, sized and placed to read as a fourth
 * traffic light.
 *
 * ### Why this colour is a literal and not a theme token
 *
 * Every other colour in the app comes from `BossTheme.colors`, and this deliberately does not. It
 * sits in a row with the macOS close, minimise and zoom buttons, and **those do not change with the
 * theme** - they are the same red, amber and green in every appearance macOS has. A button in that
 * row that restyled itself per theme would stop reading as part of the cluster, which is the one
 * thing it has to do. It is a signal colour borrowed from Parallels, which is where the idea and
 * the user's expectation both come from, not a brand colour.
 *
 * Adding it to `BossColorScheme` was the alternative and is worse twice over: that type lives in
 * `plugin-ui-core`, which carries binary-compatibility constraints for dynamically loaded plugins,
 * and a token implies six theme-specific values where the correct answer is one.
 */
private val TRAFFIC_LIGHT_BLUE = Color(0xFF2F7CF6)

private val TRAFFIC_LIGHT_BLUE_HOVER = Color(0xFF1B63D6)

/** Test tag - see `CapturedFullScreenButtonTest`. */
const val CAPTURED_FULLSCREEN_BUTTON_TAG = "captured-fullscreen-button"

/**
 * @param capturing whether a session is running, which flips the tooltip from enter to leave.
 * @param onToggle enter or leave. Never disabled: the button is also the way out.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CapturedFullScreenButton(
    capturing: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    HoverTooltipBox(
        text =
            if (capturing) {
                "Leave captured full screen"
            } else {
                "Captured full screen: the pointer and OS shortcuts stay in BOSS"
            },
        placement = TooltipPlacement.TOP,
        modifier = modifier,
    ) {
        Box(
            modifier =
                Modifier
                    .testTag(CAPTURED_FULLSCREEN_BUTTON_TAG)
                    .size(TRAFFIC_LIGHT_DIAMETER)
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(if (hovered) TRAFFIC_LIGHT_BLUE_HOVER else TRAFFIC_LIGHT_BLUE)
                    // A ring rather than a glyph while capturing. The lights beside it carry their
                    // own symbols only on hover, and a permanently marked fourth button would be
                    // the loudest thing in the corner of a mode whose whole point is an empty
                    // screen.
                    .then(
                        if (capturing) {
                            Modifier.border(width = 2.dp, color = Color.White.copy(alpha = 0.85f), shape = CircleShape)
                        } else {
                            Modifier
                        },
                    ).hoverable(interactionSource)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .onClick { onToggle() },
        )
    }
}
