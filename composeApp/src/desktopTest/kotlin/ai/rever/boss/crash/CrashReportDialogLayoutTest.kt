package ai.rever.boss.crash

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Layout guarantees for [CrashReportDialog] at the real crash window's sizes.
 *
 * The dialog is hosted in a JFrame (`CrashHandler.showCrashDialogWindow`). Its *content pane* is
 * 550x700 preferred — that size is applied to the ComposePanel, so the dialog gets it exactly — but
 * the 450x500 minimum is a **frame** dimension, decorations included. The content pane at that
 * minimum is therefore smaller by the title bar, which is why the sizes below subtract
 * [WORST_CASE_DECORATION_HEIGHT] rather than using the minimum as-is.
 *
 * A crash report is a lot of content for that box, and expanding "Technical
 * Details" adds ~250dp more — so a body laid out without a scroll region pushes "Report Issue" and
 * "Don't Send" below the bottom edge, where they cannot be clicked and the crash can neither be
 * reported nor dismissed. These tests pin the three properties that keep that from happening:
 * the footer is pinned, the body scrolls, and none of it costs anything when the content fits.
 *
 * The window is reproduced with `clipToBounds()`, because that is what makes off-window content
 * actually undisplayed — without clipping, overflow is merely painted outside the frame and
 * `assertIsDisplayed` would pass on a broken layout.
 */
class CrashReportDialogLayoutTest {
    private companion object {
        /**
         * How far the footer may sit below the content it follows. Correct is ~30dp (a 16dp spacer
         * plus the checkbox row's padding); a `weight(1f)` regression measures ~271dp. The
         * threshold sits between those, far from both — it is slack, not a tuned value.
         */
        val MAX_FOOTER_GAP = 80.dp

        /**
         * Worst-case window decoration, subtracted from `CrashHandler.FRAME_MIN_*` to get a content
         * box no platform's is smaller than. Measured 32px of title bar on macOS (0 at the sides);
         * Windows and common Linux WMs run to roughly 31-40 with side borders. Rounding up means
         * these tests exercise a box *tighter* than anything that ships, which is the safe
         * direction — the alternative, assuming the frame minimum is also the content minimum,
         * validates against headroom no user has.
         */
        val WORST_CASE_DECORATION_HEIGHT = 40.dp
        val WORST_CASE_DECORATION_WIDTH = 16.dp

        /**
         * dp-to-px rounding slack. Both edges compared are anchored to the same Box — the rule by
         * `Alignment.BottomStart`, the scrollbar by `fillMaxHeight()` — so they land on the same
         * bottom edge exactly; the rule's 1dp thickness only moves its *top*.
         */
        val RULE_OVERLAY_TOLERANCE = 2.dp
    }

    @get:Rule
    val rule = createComposeRule()

    /** Deep enough that the stack trace pane reaches its 200dp cap. */
    private val deepCrashReport =
        CrashReport(
            signature = "test-signature",
            exceptionType = "java.lang.IllegalStateException",
            exceptionMessage = "Something went badly wrong while doing the thing",
            stackTrace =
                "java.lang.IllegalStateException: Something went badly wrong\n" +
                    (1..60).joinToString("\n") { i ->
                        "\tat ai.rever.boss.example.Frame$i.doWork(Frame$i.kt:$i)"
                    },
            systemInfo =
                SystemInfo(
                    osName = "Mac OS X",
                    osVersion = "15.0",
                    osArch = "aarch64",
                    javaVersion = "21",
                    javaVendor = "Test",
                    heapUsedMB = 256,
                    heapMaxMB = 4096,
                    nonHeapUsedMB = 128,
                    availableProcessors = 8,
                ),
            appInfo = AppInfo(version = "9.9.9", platform = "macos", isDebug = true),
            timestamp = 0L,
        )

    @Composable
    private fun Dialog(submitResult: CrashReportService.SubmitResult? = null) {
        CrashReportDialog(
            crashReport = deepCrashReport,
            onDismiss = {},
            onSubmit = { _, _ -> },
            onCleanAndRestart = {},
            initialSubmitResult = submitResult,
        )
    }

    private fun setDialogInWindow(
        width: Dp,
        height: Dp,
        submitResult: CrashReportService.SubmitResult? = null,
    ) {
        rule.setContent {
            Box(modifier = Modifier.size(width, height).clipToBounds()) {
                Dialog(submitResult)
            }
        }
    }

    /** Tighter than the smallest content pane that ships; see [WORST_CASE_DECORATION_HEIGHT]. */
    private fun setDialogAtMinimumWindowSize(submitResult: CrashReportService.SubmitResult? = null) =
        setDialogInWindow(
            CrashHandler.FRAME_MIN_WIDTH.dp - WORST_CASE_DECORATION_WIDTH,
            CrashHandler.FRAME_MIN_HEIGHT.dp - WORST_CASE_DECORATION_HEIGHT,
            submitResult,
        )

    /**
     * Asserts each label is entirely within the window, not merely overlapping it.
     *
     * `assertIsDisplayed()` only requires a node's *clipped* bounds to be non-empty, so a button
     * hanging most of the way off an edge satisfies it. Comparing clipped against unclipped bounds
     * is what actually rules that out.
     */
    private fun assertWhollyOnScreen(
        vararg labels: String,
        because: String,
    ) {
        for (label in labels) {
            val node = rule.onNodeWithText(label)
            assertEquals(
                node.getUnclippedBoundsInRoot(),
                node.getBoundsInRoot(),
                "\"$label\" is not wholly on screen: $because",
            )
        }
    }

    @Test
    fun actionButtonsStayVisibleWhenTechnicalDetailsAreExpanded() {
        setDialogAtMinimumWindowSize()

        rule.onNodeWithText("Report Issue").assertIsDisplayed()

        rule.onNodeWithText("Technical Details").performClick()
        rule.waitForIdle()

        // The expanded stack trace must not push the footer off the window.
        rule.onNodeWithText("Report Issue").assertIsDisplayed()
        rule.onNodeWithText("Don't Send").assertIsDisplayed()
        rule.onNodeWithText("Clean Data & Restart").assertIsDisplayed()
    }

    @Test
    fun footerButtonsAreWhollyOnScreenNotMerelyIntersectingIt() {
        setDialogAtMinimumWindowSize()

        rule.onNodeWithText("Technical Details").performClick()
        rule.waitForIdle()

        assertWhollyOnScreen(
            "Clean Data & Restart",
            "Don't Send",
            "Report Issue",
            because = "the expanded details must not push the footer past an edge",
        )
    }

    @Test
    fun bodyContentBelowTheFoldIsReachableByScrolling() {
        setDialogAtMinimumWindowSize()

        rule.onNodeWithText("Technical Details").performClick()
        rule.waitForIdle()

        // Fails without a scrollable ancestor: the checkbox sits below the fold once the
        // details are expanded, so it is only reachable if the body actually scrolls.
        rule.onNodeWithText("Include recent activity logs").performScrollTo().assertIsDisplayed()

        // Scrolling the body must not have carried the footer away with it.
        rule.onNodeWithText("Report Issue").assertIsDisplayed()
    }

    @Test
    fun collapsedDialogFitsWithoutScrollingAtThePreferredWindowSize() {
        setDialogInWindow(
            CrashHandler.CONTENT_PREFERRED_WIDTH.dp,
            CrashHandler.CONTENT_PREFERRED_HEIGHT.dp,
        )

        // Note the absent performScrollTo: at the preferred size a collapsed report fits, and
        // must still fit — nothing may be pushed below the fold to pay for chrome it doesn't need.
        rule.onNodeWithText("Include recent activity logs").assertIsDisplayed()
        rule.onNodeWithText("What were you doing when this happened? (optional)").assertIsDisplayed()
        rule.onNodeWithText("Report Issue").assertIsDisplayed()
    }

    @Test
    fun collapsedFooterSitsBelowTheContentRatherThanAtTheWindowBottom() {
        setDialogInWindow(
            CrashHandler.CONTENT_PREFERRED_WIDTH.dp,
            CrashHandler.CONTENT_PREFERRED_HEIGHT.dp,
        )

        // `weight(1f, fill = false)` is what keeps the body's height cap from also being a floor.
        // Visibility assertions cannot see the difference — with a plain `weight(1f)` everything
        // is still displayed, just with the footer stranded at the bottom edge and ~230dp of dead
        // space above it. Only the geometry shows it, so this measures the geometry.
        val contentBottom =
            rule.onNodeWithText("Helps with debugging (logs are sanitized)").getBoundsInRoot().bottom
        val footerTop = rule.onNodeWithText("Report Issue").getBoundsInRoot().top

        val gap = footerTop - contentBottom
        assertTrue(
            gap < MAX_FOOTER_GAP,
            "Footer should follow the content when it fits, but sat ${gap.value}dp below it - " +
                "the body is claiming space it does not need (regression to plain weight(1f)?)",
        )
    }

    @Test
    fun theBodyScrollbarAppearsOnlyWhileTheBodyIsClipping() {
        setDialogInWindow(
            CrashHandler.CONTENT_PREFERRED_WIDTH.dp,
            CrashHandler.CONTENT_PREFERRED_HEIGHT.dp,
        )

        // Pins the *gating*: a thumb appears when, and only when, there is travel to offer.
        // Verified by control that this does NOT catch the `maxValue > 0` sentinel bug — by the
        // time waitForIdle() returns, measure has assigned maxValue and both forms agree. The
        // sentinel needs a paused clock; see theBodyScrollbarIsAbsentEvenOnTheFirstFrame.
        rule.onNodeWithTag(BODY_SCROLLBAR_TAG).assertDoesNotExist()
        // The rule also requires overflow, so it must be absent too - otherwise a stray line is drawn
        // across a dialog with nothing clipped.
        rule.onNodeWithTag(BOUNDARY_RULE_TAG).assertDoesNotExist()

        rule.onNodeWithText("Technical Details").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(BODY_SCROLLBAR_TAG).assertExists()
        rule.onNodeWithTag(BOUNDARY_RULE_TAG).assertExists()
    }

    @Test
    fun theBoundaryRuleDisappearsAtTheBottomAndReturnsWhenScrollingBack() {
        setDialogAtMinimumWindowSize()
        rule.onNodeWithText("Technical Details").performClick()
        rule.onNodeWithTag(BOUNDARY_RULE_TAG).assertExists()

        val body =
            rule.onNode(
                hasScrollAction() and hasAnyDescendant(hasText("Include recent activity logs")),
            )
        body.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 10_000f) }
        rule.waitForIdle()

        rule.onNodeWithTag(BOUNDARY_RULE_TAG).assertDoesNotExist()
        rule.onNodeWithTag(BODY_SCROLLBAR_TAG).assertExists()
        rule.onNodeWithText("Helps with debugging (logs are sanitized)").assertIsDisplayed()

        body.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -10_000f) }
        rule.waitForIdle()
        rule.onNodeWithTag(BOUNDARY_RULE_TAG).assertExists()
    }

    @Test
    fun aLongSubmitFailureIsTruncatedBeforeBeingDisplayedAndStillSanitized() {
        setDialogAtMinimumWindowSize(
            CrashReportService.SubmitResult.Error(
                "Request failed: https://private.example.com/secret " +
                    "boom ".repeat(100_000) + "END_OF_OVERSIZED_MESSAGE",
            ),
        )

        rule.onNodeWithText("Request failed:", substring = true).assertExists()
        rule.onNodeWithText("private.example.com", substring = true).assertDoesNotExist()
        rule.onNodeWithText("END_OF_OVERSIZED_MESSAGE", substring = true).assertDoesNotExist()
    }

    @Test
    fun theBodyScrollbarIsAbsentEvenOnTheFirstFrame() {
        // The sentinel guard specifically. Every other assertion in this class runs after
        // waitForIdle(), by which point measure has assigned maxValue and the naive `maxValue > 0`
        // form behaves identically — so pausing the clock is the only way to observe the frame the
        // sentinel affects.
        rule.mainClock.autoAdvance = false
        setDialogInWindow(
            CrashHandler.CONTENT_PREFERRED_WIDTH.dp,
            CrashHandler.CONTENT_PREFERRED_HEIGHT.dp,
        )

        rule.onNodeWithTag(BODY_SCROLLBAR_TAG).assertDoesNotExist()
        // The rule also needs the sentinel guard, even if canScrollForward initially reports true.
        rule.onNodeWithTag(BOUNDARY_RULE_TAG).assertDoesNotExist()
    }

    @Test
    fun aLongSubmitFailureCannotPushTheButtonsOffTheWindow() {
        // The one path that can squeeze the body from *below*: the submit-result card sits in the
        // pinned footer, and CrashReportService interpolates e.message into it, which a TLS or
        // proxy failure can make arbitrarily long. maxLines caps it; this is that cap's guard.
        setDialogAtMinimumWindowSize(
            CrashReportService.SubmitResult.Error("Failed to submit crash report: " + "boom ".repeat(1000)),
        )

        rule.onNodeWithText("Technical Details").performClick()
        rule.waitForIdle()

        assertWhollyOnScreen(
            "Clean Data & Restart",
            "Don't Send",
            "Report Issue",
            because = "a long failure message filled the footer",
        )
    }

    @Test
    fun theSuccessFooterKeepsBothItsRowsOnScreen() {
        // Success is structurally different from Error: the result card *plus* a second row holding
        // "Close" — the only configuration with two button rows, and so the tallest footer the
        // pinned layout has to fit.
        setDialogAtMinimumWindowSize(
            CrashReportService.SubmitResult.Success(
                issueUrl = "https://github.com/risa-labs-inc/BossConsole/issues/1",
                isNewIssue = true,
            ),
        )

        rule.onNodeWithText("Technical Details").performClick()
        rule.waitForIdle()

        assertWhollyOnScreen(
            "Clean Data & Restart",
            "Close",
            because = "the success footer adds a second button row",
        )
    }

    @Test
    fun aSubmitFailureIsSanitizedBeforeItIsShown() {
        // This card is selectable and the likeliest thing to be pasted into a public issue, and its
        // text interpolates a raw exception message. maskUriParams — which this originally used —
        // only redacts named params inside a `?`/`#` segment, so it returned a ktor timeout message
        // carrying the request URL completely untouched.
        setDialogAtMinimumWindowSize(
            CrashReportService.SubmitResult.Error(
                "Failed to submit crash report: Request timeout has expired " +
                    "[url=https://api.risaboss.com/functions/v1/crash-report, request_timeout=15000 ms]",
            ),
        )

        // The card must render — without this the two negative assertions below would also pass
        // if the Error branch were dropped or initialSubmitResult stopped being wired through.
        // This substring also documents what sanitizing is meant to *preserve*.
        rule.onNodeWithText("Request timeout has expired", substring = true).assertExists()

        rule.onNodeWithText("api.risaboss.com", substring = true).assertDoesNotExist()
        // Hyphenated, so it matches only the URL path — the "Failed to submit crash report:" prose
        // has a space. Not "crash-report]": the fixture has a comma there, so that literal appears
        // nowhere in the input and the assertion could never fail.
        rule.onNodeWithText("crash-report", substring = true).assertDoesNotExist()
    }

    @Test
    fun theStackTracePaneIsCappedAndScrollsWithinThatCap() {
        setDialogAtMinimumWindowSize()
        rule.onNodeWithText("Technical Details").performClick()
        rule.waitForIdle()

        // The other load-bearing behaviour of this layout: 60 frames don't turn the body into an
        // endless scroll, because the pane is bounded and takes the overflow itself. Both halves
        // are asserted — a thumb (it is clipping) and a height inside the 200dp cap. Deliberately
        // not named "independently": a pane wired to bodyScrollState would still satisfy this, and
        // the scroll states aren't observable from here.
        rule.onNodeWithTag(TRACE_SCROLLBAR_TAG).assertExists()

        // Measured on the pane itself, not the scrollbar inside it: the thumb fills the height
        // *within* the pane's 8dp padding, so asserting on it would carry 16dp of slack.
        //
        // Bounded against the body viewport rather than against TRACE_PANE_MAX_HEIGHT. Asserting a
        // shared constant against itself is tautological — it holds at any cap value, while the
        // property it claims to protect does not. This bound is the property that actually matters
        // and it does not move when the cap is tuned: the pane must leave room in the body for
        // everything below it.
        //
        // Controlled at cap = 400dp, where it fires: "trace pane (400.0dp) fills the body viewport
        // (268.0dp)". Note it is not what catches an *extreme* cap — at 2000dp the trace stops
        // clipping inside the pane, so the thumb assertion above fires first. This bound owns the
        // middle range, where the pane still clips but has eaten the body.
        val paneBounds = rule.onNodeWithTag(TRACE_PANE_TAG).getUnclippedBoundsInRoot()
        val paneHeight = paneBounds.bottom - paneBounds.top
        val bodyBounds = rule.onNodeWithTag(BODY_SCROLLBAR_TAG).getUnclippedBoundsInRoot()
        val bodyViewport = bodyBounds.bottom - bodyBounds.top
        assertTrue(
            paneHeight < bodyViewport,
            "trace pane (${paneHeight.value}dp) fills the body viewport (${bodyViewport.value}dp), " +
                "so a deep trace has become the body's scroll",
        )

        // And the body still scrolls past it to reach what's below.
        rule.onNodeWithText("Include recent activity logs").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theBoundaryRuleCostsNoLayoutHeight() {
        setDialogAtMinimumWindowSize()
        rule.onNodeWithText("Technical Details").performClick()
        rule.waitForIdle()

        // The invariant the overlay exists for: the rule lives *inside* the body region, so it
        // adds nothing to the parent Column's height.
        //
        // Measured against the body scrollbar, which fillMaxHeight()s inside the same Box and so
        // ends exactly at the body's bottom edge. Overlaid, the rule ends there too; as a footer
        // sibling it would sit a spacer's width below it. Note this is *not* measured as a
        // rule-to-footer gap — that gap is 16dp in both shapes, so it cannot tell them apart
        // (verified by control: a sibling rule passes such an assertion).
        val bodyBottom = rule.onNodeWithTag(BODY_SCROLLBAR_TAG).getUnclippedBoundsInRoot().bottom
        val ruleBottom = rule.onNodeWithTag(BOUNDARY_RULE_TAG).getUnclippedBoundsInRoot().bottom
        // Two-sided: `<=` alone would also pass a rule pinned to the *top* of the body, which is a
        // visible bug unrelated to overlay-vs-sibling.
        val delta = ruleBottom - bodyBottom
        assertTrue(
            abs(delta.value) <= RULE_OVERLAY_TOLERANCE.value,
            "rule ends ${delta.value}dp from the body's bottom edge - expected it flush with it; " +
                "below means a sibling consuming layout height, above means it is not at the edge",
        )
    }

    @Test
    fun theTraceScrollbarIsAbsentWhileTheDetailsAreCollapsed() {
        setDialogAtMinimumWindowSize()

        // Collapsed, the pane isn't composed at all — so this also pins that the hoisted trace
        // scroll state's stale maxValue never leaks a thumb into the collapsed dialog.
        rule.onNodeWithTag(TRACE_SCROLLBAR_TAG).assertDoesNotExist()
    }

    @Test
    fun aBlankFailureMessageRendersThePlaceholderNotAnEmptyCard() {
        // Unreachable today (every Error interpolates a prefix), but it is the one behaviour the
        // sanitizer swap changed beyond redaction: maskUriParams answered "[empty]" for blank input,
        // sanitizeExceptionMessage answers "[no message]".
        setDialogAtMinimumWindowSize(CrashReportService.SubmitResult.Error(""))

        rule.onNodeWithText("[no message]", substring = true).assertExists()
    }
}
