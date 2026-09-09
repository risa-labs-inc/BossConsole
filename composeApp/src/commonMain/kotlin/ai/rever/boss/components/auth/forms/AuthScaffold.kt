package ai.rever.boss.components.auth.forms

import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Width of the form column itself, independent of the pane it sits in.
 *
 * The house card width, matching `BossDialog`'s `ALERT_WIDTH` and `ConfirmationDialog`.
 */
private val AuthColumnWidth: Dp = 400.dp

/**
 * Width of the pane holding the form, when the brand panel is shown beside it.
 *
 * FIXED, deliberately, rather than a weight. A percentage split puts the 400dp column back in the
 * middle of a large empty area on a wide display - the very thing this layout exists to remove - so
 * the form pane stays a tight column and [AuthBrandArt] absorbs whatever the window has spare.
 */
private val FormPaneWidth: Dp = 520.dp

/**
 * Narrowest window that gets the brand panel.
 *
 * Below this the panel would take space the form needs, so the form goes full width and carries the
 * wordmark itself. 900dp is not invented: `DisplayUtils.calculateAuthWindowSize()` clamps an auth
 * window to 900x700, which is this screen's existing statement of how small it is expected to go.
 */
internal val BrandPanelMinWindowWidth: Dp = 900.dp

/**
 * Whether a window this wide shows the brand panel beside the form.
 *
 * A pure function so the breakpoint is pinned by a test without needing a display, the same shape as
 * `shouldRouteHeavyweight` in `BossDialog.kt`.
 */
internal fun showsBrandPanel(windowWidth: Dp): Boolean = windowWidth >= BrandPanelMinWindowWidth

/**
 * The shared frame for every authentication screen: brand panel, form pane, title, scrolling.
 *
 * All four auth screens (sign-in, magic-link waiting, passkey selection, passkey waiting) used to
 * repeat this frame verbatim, which is how three of them ended up with a `verticalScroll` and the
 * fourth - the one with the longest content - without.
 *
 * **The modifier order on the form column is the whole point.** The old `AuthCard` wrote
 * `fillMaxWidth().widthIn(max = 400.dp)`, which reads as a capped card and is not one: `fillMaxWidth`
 * measures its child with `minWidth == maxWidth == the parent's width`, and `widthIn` enforces
 * incoming constraints, so its 0..400 range is coerced back up into that fixed range and the cap is
 * discarded. The card spanned the whole window. Here the cap comes FIRST and `fillMaxWidth` fills
 * what the cap allows - and a window narrower than 400dp still shrinks rather than overflowing.
 *
 * @param title Screen heading, in the mono brand voice.
 * @param subtitle Optional line under the heading.
 * @param content The screen's own fields and actions, in a centered column [AuthColumnWidth] wide.
 */
@Composable
fun AuthScaffold(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = BossTheme.colors

    // A four-second arrival swell, tied to this screen's lifetime. DisposableEffect rather than
    // LaunchedEffect because of the stop half: four seconds is easily longer than a saved session takes to
    // sign in, and a sound carrying on over the signed-in app is the one outcome to avoid. Starting is
    // latched per process, so moving between the four auth screens this scaffold frames does not re-fire it.
    DisposableEffect(Unit) {
        startAuthTheme()
        onDispose { stopAuthTheme() }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Captured before the Row: inside it, RowScope is the innermost receiver and the
        // BoxWithConstraintsScope members are no longer reachable implicitly.
        val paneHeight = maxHeight
        if (showsBrandPanel(maxWidth)) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Weighted, so it takes whatever the fixed-width pane leaves. Row measures the
                // fixed child first, so the panel cannot squeeze the form.
                AuthBrandArt(modifier = Modifier.weight(1f).fillMaxHeight())
                Box(
                    modifier =
                        Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(colors.line),
                )
                AuthFormPane(
                    modifier = Modifier.width(FormPaneWidth),
                    paneHeight = paneHeight,
                    showWordmark = false,
                    title = title,
                    subtitle = subtitle,
                    content = content,
                )
            }
        } else {
            AuthFormPane(
                modifier = Modifier.fillMaxWidth(),
                paneHeight = paneHeight,
                showWordmark = true,
                title = title,
                subtitle = subtitle,
                content = content,
            )
        }
    }
}

/**
 * The form side: a centered, width-capped, scrolling column.
 *
 * [paneHeight] is the window height, and `heightIn(min = paneHeight)` with `Arrangement.Center` is
 * kept from the screens this replaces: together they center short content and let long content grow
 * and scroll, without needing to know which case applies.
 */
@Composable
private fun AuthFormPane(
    modifier: Modifier,
    paneHeight: Dp,
    showWordmark: Boolean,
    title: String,
    subtitle: String?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = BossTheme.colors
    val space = BossTheme.space
    Box(
        modifier = modifier.fillMaxHeight().background(colors.panel),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .heightIn(min = paneHeight)
                    .verticalScroll(rememberScrollState())
                    // padding OUTSIDE the cap, so 400dp is the content width rather than the
                    // content-plus-margin width.
                    .padding(space.xl)
                    .widthIn(max = AuthColumnWidth)
                    .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (showWordmark) {
                // The wordmark in type, not `boss_icon.png`. That asset is a launcher tile - a
                // near-black rounded square with BOSS set inside it - so on these dark surfaces it has
                // no edge and reads as a hole, and it repeats in words whatever label sits next to it.
                Text(
                    text = "BOSS CONSOLE",
                    style = BossTheme.type.label,
                    color = colors.signalText,
                )
                Spacer(modifier = Modifier.height(space.xl))
            }
            Text(
                text = title,
                style = BossTheme.type.displaySmall,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(space.sm))
                Text(
                    text = subtitle,
                    style = BossTheme.type.body,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(modifier = Modifier.height(space.xl))
            content()
        }
    }
}
