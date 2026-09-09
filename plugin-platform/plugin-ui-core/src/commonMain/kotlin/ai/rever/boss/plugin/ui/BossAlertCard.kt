package ai.rever.boss.plugin.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.LocalContentColor
import androidx.compose.material.ProvideTextStyle
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// Alert card
// ---------------------------------------------------------------------------
//
// Its own file rather than living in BossDialog.kt: that file was already carrying the dialog
// routing, the scrim, the popup and the anchor maths, and the card's own reasoning about measurement
// is long enough that detekt's per-file function budget and per-function length both objected.

/** Test tag on the body's scrollbar; see `BossAlertCardLayoutTest`. */
internal const val BODY_SCROLLBAR_TAG = "alert-body-scrollbar"

/** Width of a BOSS alert card, matching the house confirmation dialog. */
internal val ALERT_WIDTH: Dp = 400.dp

/**
 * Whether a spacer belongs above the button row.
 *
 * Only when there is header content - a title or a body - for it to separate the buttons FROM. A
 * buttons-only card (the `buttons` overload with no title and no text) would otherwise carry a dead
 * `space.xl` gap above a lone action row. Pure so the one rule is pinned by a test rather than read
 * off the layout.
 */
internal fun alertHeaderSpacerVisible(
    hasTitle: Boolean,
    hasText: Boolean,
): Boolean = hasTitle || hasText

/**
 * The card [BossAlertDialog] puts inside a dialog, split out so it can be measured.
 *
 * `BossAlertDialog` itself cannot be: on the path a test scene can host it routes to a platform
 * dialog window, and such a window does not inherit the size of whatever composed it. Both bugs
 * below are about a card with LESS room than it wants, which only a constrained parent produces -
 * hence `internal`, and `BossAlertCardLayoutTest` constraining it directly.
 *
 * **Width: [ALERT_WIDTH] is what an alert wants, not what it must have.** `.width(ALERT_WIDTH)` set
 * the minimum as well as the maximum, so a window narrower than 400dp got a card it could not fit.
 * `BoxWithConstraints` rather than `widthIn(max = …)`, because with only a maximum a `Surface` wraps
 * its content and every short alert in the app would have become narrower than 400dp.
 *
 * **Height: the actions are what a short window used to lose, and it is the same bug.** A `Column`
 * offers each non-weighted child what the previous ones left, so the body took what it wanted and
 * the actions - measured last - were offered nothing: 0dp tall in a 300dp frame, exactly as the
 * primary came out 0dp wide in a card too narrow for its action row. So the body takes the flexible
 * space and scrolls, while the title and the actions keep theirs.
 *
 * Three things about that are load-bearing, and each has a test that fails without it:
 *
 *  - **`fill = false`** - a bare `weight(1f)` fills its share, stretching every short alert to the
 *    window.
 *  - **the flex is gated on a BOUNDED height, read from the constraints the `Column` receives** (see
 *    [AlertBody]). A weighted child of an unbounded axis gets a share of almost nothing.
 *  - **`heightIn` is the margin, not the fix** - incoming constraints already bound the card; that
 *    line only holds it `space.lg` clear of the parent's edges.
 *
 * **This fixes the squeeze where the card is measured against a parent, which is the scrimmed path**
 * (`ScrimmedModalContent` is a `fillMaxSize` Box, and that is where the report came from).
 *
 * **On the lightweight `Dialog` path the answer is platform-dependent, not "inert".** That is
 * measured, not assumed: a test asserting the body was NOT flexed there passed on macOS and Linux
 * and failed on windows-latest, so that window hands its content an unbounded height on some
 * platforms and a bounded one on others. Where it is unbounded the ceiling is the *screen* rather
 * than the card, and a tall card on a shorter display still puts its actions out of reach -
 * pre-existing, and not addressed here. Where it is bounded, this fix applies and the body scrolls.
 * Either way the actions keep their height, which is the only invariant worth pinning; do not write
 * a test that asserts which branch a platform takes.
 *
 * **There is a floor, about 176dp.** Only the body flexes, so padding, title and spacers are still
 * measured first. Measured: full-height actions at a 180dp parent, 20dp at 160dp, 0dp at 140dp, and
 * the title itself goes at 80dp. Pinned by `theActionsSurviveTheShortestWindowThatCanHoldThem`.
 * Going lower means shrinking padding or dropping the title, which is a design decision.
 *
 * **A [text] slot that caps itself against its parent loses that reference point.** A cap written as
 * `min(myMax, constraints.maxHeight)` coerces against an infinite maximum here and always takes its
 * full constant, so it stops shrinking with a short window - two nested scroll regions instead of
 * one. A slot that wants to shrink has to be told its budget rather than derive it. `verticalScroll`
 * also applies `clipScrollableContainer`, so the body is clipped in the scroll direction.
 *
 * The measurements behind all of the above, and the audit of what a `text` slot may contain, are in
 * the PR that introduced it (risa-labs-inc/BossConsole#216) rather than here.
 */
@Composable
internal fun BossAlertCard(
    buttons: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape? = null,
    backgroundColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
) {
    val colors = BossTheme.colors
    val space = BossTheme.space
    BoxWithConstraints(contentAlignment = Alignment.Center) {
        val available = (maxWidth - space.lg * 2).coerceAtLeast(0.dp)
        val parentBoundsUs = constraints.hasBoundedHeight
        Surface(
            modifier =
                modifier
                    .width(ALERT_WIDTH.coerceAtMost(available))
                    .then(
                        if (parentBoundsUs) {
                            // The margin, not the fix - see the KDoc. Pinned by
                            // theCardKeepsAMarginFromAParentShorterThanItself.
                            Modifier.heightIn(max = (maxHeight - space.lg * 2).coerceAtLeast(0.dp))
                        } else {
                            Modifier
                        },
                    ).wrapContentHeight(),
            shape = shape ?: BossTheme.radius.dialogShape,
            color = backgroundColor.takeOrElse { colors.panel },
            contentColor = contentColor.takeOrElse { colors.textPrimary },
        ) {
            // Not redundant with the outer one: the caller's `modifier` sits between the outer
            // constraints and this point, so reading them here is what makes the branch below true
            // by construction for the axis the weight uses. See the KDoc; pinned by
            // aCallerModifierThatRemovesTheHeightBoundDoesNotCollapseTheBody.
            BoxWithConstraints {
                val bodyCanFlex = constraints.hasBoundedHeight
                Column(modifier = Modifier.padding(space.xl)) {
                    if (title != null) {
                        CompositionLocalProvider(LocalContentColor provides colors.textPrimary) {
                            ProvideTextStyle(BossTheme.type.title, title)
                        }
                    }
                    if (title != null && text != null) {
                        Spacer(Modifier.height(space.md))
                    }
                    if (text != null) {
                        AlertBody(text, bodyCanFlex, colors.textSecondary)
                    }
                    if (alertHeaderSpacerVisible(title != null, text != null)) {
                        Spacer(Modifier.height(space.xl))
                    }
                    buttons()
                }
            }
        }
    }
}

/**
 * The card's body: the part that gives way when the card is shorter than its content.
 *
 * The scrollbar is a raw `VerticalScrollbar` and does **not** honour the app-wide scrollbar
 * settings that `plugin-scrollbar` owns - this module cannot depend on that one, so the
 * inconsistency is deliberate rather than an oversight.
 *
 * [canFlex] must come from the constraints the enclosing `Column` receives, not from the card's own
 * incoming constraints - the caller's `modifier` sits between the two, and a caller that removes the
 * height bound would otherwise get the weighted branch against an infinite axis, which is the
 * collapse the flag exists to prevent, reached through it rather than around it.
 */
@Composable
private fun ColumnScope.AlertBody(
    text: @Composable () -> Unit,
    canFlex: Boolean,
    contentColor: Color,
) {
    val scroll = rememberScrollState()
    val showScrollbar = canFlex && scroll.maxValue in 1 until Int.MAX_VALUE
    // Two boxes, and which one owns which modifier is the whole point.
    //
    // The VIEWPORT owns the weight; the scroll lives in a child. Putting the scrollbar inside the
    // scrolled subtree instead - as the first version of this did - makes it a sibling of the
    // content in CONTENT space: `verticalScroll` measures its child against an infinite height, so
    // that Box sizes to the content (~996dp for a 40-line body against a ~150dp viewport), and a
    // `matchParentSize` scrollbar is then measured to the content height and translates upward as
    // the user scrolls. Keeping the viewport and the scrollbar as siblings puts the scrollbar in
    // viewport space, where a scrollbar belongs.
    Box(modifier = if (canFlex) Modifier.weight(1f, fill = false) else Modifier) {
        Box(modifier = if (canFlex) Modifier.verticalScroll(scroll) else Modifier) {
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                // A gutter while the scrollbar is showing, so it does not sit on top of the last
                // few characters of every wrapped line.
                Box(Modifier.padding(end = if (showScrollbar) BossTheme.space.md else 0.dp)) {
                    ProvideTextStyle(BossTheme.type.body, text)
                }
            }
        }
        if (showScrollbar) {
            VerticalScrollbar(
                rememberScrollbarAdapter(scroll),
                // Tagged so a test can assert it is measured against the VIEWPORT and does not
                // translate with the content; nothing else about it is observable.
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().testTag(BODY_SCROLLBAR_TAG),
            )
        }
    }
}
