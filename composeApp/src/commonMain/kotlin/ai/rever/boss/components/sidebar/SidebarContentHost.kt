package ai.rever.boss.components.sidebar

import ai.rever.boss.components.overlays.overlayCornerIsHeavyweight
import ai.rever.boss.components.window_panel.components.main_window_panels.TabBarRevealState
import ai.rever.boss.components.window_panel.components.main_window_panels.overlayRegionInWindow
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.theme.LocalWindowGlass
import ai.rever.boss.theme.WindowGlass
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp

/** Explicitly opened drawers and pinned sidebars reserve width; only hover reveal overlays. */
internal fun sidebarShouldOverlay(
    railShown: Boolean,
    transientReveal: Boolean,
): Boolean = railShown && transientReveal

/** Transport the single sidebar body above native browser surfaces, without moving its header. */
@Composable
internal fun SidebarContentHost(
    overlay: Boolean,
    width: Dp,
    reveal: TabBarRevealState,
    extendsIntoTitleBar: Boolean,
    revealProgress: Float = 1f,
    content: @Composable () -> Unit,
) {
    val heavyweight = overlay && overlayCornerIsHeavyweight()
    if (!heavyweight) {
        if (overlay) {
            OpaqueSidebarBody(Modifier.width(width).fillMaxHeight(), content)
        } else {
            content()
        }
        return
    }
    val density = LocalDensity.current.density
    var region by remember { mutableStateOf<IntRect?>(null) }
    Box(
        Modifier.width(width).fillMaxHeight().onGloballyPositioned {
            region = overlayRegionInWindow(it.boundsInWindow(), density)
        },
    ) {
        val bounds = region ?: return@Box
        SidebarOverlayWindow(
            size = DpSize(sidebarBodyWidth(width, revealProgress).coerceAtLeast(1.dp), bounds.height.dp),
            region = bounds,
        ) {
            val bodyWidth = sidebarBodyWidth(width, revealProgress).coerceAtLeast(1.dp)
            OpaqueSidebarBody(
                Modifier
                    .width(bodyWidth)
                    .fillMaxHeight()
                    .hoverable(reveal.drawerHover)
                    .clip(
                        if (extendsIntoTitleBar) {
                            RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp)
                        } else {
                            RoundedCornerShape(22.dp)
                        },
                    ),
            ) {
                Box(
                    Modifier
                        .wrapContentWidth(Alignment.Start, unbounded = true)
                        .requiredWidth(width)
                        .fillMaxHeight(),
                ) { content() }
            }
        }
    }
}

/** The hover body paints and clips in Compose; glass remains scoped to the owning window. */
@Composable
private fun OpaqueSidebarBody(
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalWindowGlass provides WindowGlass()) {
        Box(modifier.background(BossTheme.colors.panel.copy(alpha = 1f))) { content() }
    }
}

/** Fixed geometry bypasses content measurement while the sidebar animates. */
@Composable
internal expect fun SidebarOverlayWindow(
    size: DpSize,
    region: IntRect,
    content: @Composable () -> Unit,
)
