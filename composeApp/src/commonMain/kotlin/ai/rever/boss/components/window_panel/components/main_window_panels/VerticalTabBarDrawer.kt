package ai.rever.boss.components.window_panel.components.main_window_panels

import ai.rever.boss.components.overlays.OverlayCorner
import ai.rever.boss.components.overlays.overlayCornerIsHeavyweight
import ai.rever.boss.layout.BossChrome
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The full vertical tab bar shown as a temporary drawer beside the permanent activity rail.
 *
 * Permanent Activity Bar: the drawer is a sibling beside the 40dp rail, not an overlay over it.
 * The rail's Settings/Search/Sign-Out icons stay stationary at the window edge (Fitts's Law);
 * this drawer carries only the tab list and the window footer. The heavyweight region is
 * therefore offset by the rail width before placement (see `SplitView.RevealedBar.besideRail`);
 * the lightweight path offsets in the Compose scene for the same reason.
 *
 * **Why this is not just a Box with an offset.** Under HARDWARE_ACCELERATED JxBrowser - the
 * default on every platform - a browser tab is Chromium's own native window composited ABOVE the
 * Compose scene. Compose cannot draw over it. A drawer painted in place would therefore be
 * invisible over precisely the tabs people spend most of their time in, which is the failure mode
 * the tab tooltip, the Ctrl+Tab switcher, the toast host and the browser find bar each had to be
 * fixed for. [OverlayCorner] is the primitive that already solves it: a content-sized,
 * non-focusable window placed inside a sub-rectangle of the parent, which is exactly the shape of
 * a drawer over one panel of a split.
 *
 * **Composed only while [visible].** A heavyweight corner overlay is content-sized but still
 * swallows the clicks underneath it - the JVM has no portable click-through - so one composed
 * unconditionally would leave a permanently dead strip down the leading edge of every panel.
 * This is the same rule `BrowserHandleImpl` states for the find bar.
 *
 * @param hoverSource the drawer's hover interaction source, owned by the caller because the
 *   reveal decision needs it alongside the rail's.
 * @param panelRegion this panel's rectangle in dp relative to the window's content pane, already
 *   offset beside the rail for the permanent activity bar. Null means not yet measured, and
 *   nothing is drawn - see [overlayRegionInWindow].
 * @param onDismissOutside installs a click-catcher over the rest of the panel that invokes this.
 *   Non-null only for a drawer opened by the chevron; a hover-revealed one must NOT swallow the
 *   click that focuses the content behind it, since the pointer leaving is what closes it.
 */
@Composable
fun BoxScope.VerticalTabBarDrawer(
    visible: Boolean,
    hoverSource: MutableInteractionSource,
    hoverEnabled: Boolean,
    width: Dp,
    panelRegion: IntRect?,
    onDismissOutside: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    if (onDismissOutside != null && visible) {
        Box(
            modifier =
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures(onPress = { onDismissOutside() })
                },
        )
    }

    if (!visible) return

    val region = panelRegion ?: return
    val heavyweight = overlayCornerIsHeavyweight()

    if (heavyweight) {
        OverlayCorner(
            alignment = Alignment.TopStart,
            // A generous UPPER bound: the window opens at this size and settles once its content
            // measures, and content that measures inside a too-small window stays clipped.
            initialSize = DpSize(width, region.height.dp),
            regionInWindow = region,
        ) {
            Box(modifier = Modifier.hoverable(hoverSource, enabled = hoverEnabled)) { content() }
        }
    } else {
        // OFF_SCREEN rendering: the browser is lightweight, everything layers normally, and the
        // drawer can slide in the scene. Reachable via BOSS_RENDERING_MODE=OFF_SCREEN, which is a
        // supported escape hatch rather than a dead branch. The heavyweight path gets no slide -
        // animating a native window's bounds per frame is a different and much worse trade.
        // Permanent Activity Bar: offset beside the 40dp rail so its hit-targets stay exposed.
        val railWidth = BossChrome.dimens.stripWidth
        AnimatedVisibility(
            visible = true,
            modifier = Modifier.align(Alignment.CenterStart).fillMaxHeight().padding(start = railWidth),
            enter = slideInHorizontally(initialOffsetX = { -it }),
            exit = slideOutHorizontally(targetOffsetX = { -it }),
        ) {
            Box(modifier = Modifier.hoverable(hoverSource, enabled = hoverEnabled)) { content() }
        }
    }
}

/**
 * A layout rectangle converted to the dp-relative-to-content-pane form a heavyweight overlay is
 * placed in, or null when it does not describe a real area.
 *
 * Dp, because AWT's logical units map 1:1 to dp: an overlay window positioned from device pixels
 * lands off by the scale factor on any HiDPI display.
 *
 * Null for a degenerate rectangle rather than a zero-sized one. `boundsInWindow` is empty for a
 * clipped-away node and can carry infinities in a degenerate layout, and those survive the
 * division into a garbage IntRect; a caller that draws nothing on null is correct, where one
 * handed a garbage rectangle is not. Same guard, for the same reason, as
 * `BrowserFindPlacement.findBarRegion`.
 */
fun overlayRegionInWindow(
    boundsInWindow: Rect,
    density: Float,
): IntRect? {
    if (density <= 0f || !density.isFinite() || !boundsInWindow.hasArea()) return null
    return IntRect(
        left = (boundsInWindow.left / density).roundToInt(),
        top = (boundsInWindow.top / density).roundToInt(),
        right = (boundsInWindow.right / density).roundToInt(),
        bottom = (boundsInWindow.bottom / density).roundToInt(),
    )
}

/** Whether this rectangle describes a real, finite area. */
private fun Rect.hasArea(): Boolean {
    val w = width
    val h = height
    return w.isFinite() && h.isFinite() && w > 0f && h > 0f
}
