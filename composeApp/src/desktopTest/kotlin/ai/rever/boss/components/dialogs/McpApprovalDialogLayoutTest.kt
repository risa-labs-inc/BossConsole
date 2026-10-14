package ai.rever.boss.components.dialogs

import ai.rever.boss.components.overlays.resetOverlayFieldForTest
import ai.rever.boss.mcp.McpApprovalRequest
import ai.rever.boss.mcp.McpPolicyAction
import ai.rever.boss.mcp.sandbox.McpRiskAssessment
import ai.rever.boss.mcp.sandbox.McpRiskLevel
import ai.rever.boss.plugin.ui.BossBlueprintColorScheme
import ai.rever.boss.plugin.ui.BossOverlayHost
import ai.rever.boss.plugin.ui.LocalBossColors
import ai.rever.boss.plugin.ui.LocalHeavyweightOverlays
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The approval prompt reported unusable from a real `run_in_sidebar` call under ASK: the card was
 * taller than the window, so Allow and Deny were below its edge and the only outcome was the
 * auto-deny. This composes the REAL [McpApprovalDialog] in a short 800x500 window with a
 * description and an argument list both far longer than fit, and checks that the answer is on
 * screen and works.
 */
class McpApprovalDialogLayoutTest {
    @get:Rule val rule = createComposeRule()
    private val previousRenderer = BossOverlayHost.modalRenderer
    private val previousHeavyweight = BossOverlayHost.useHeavyweightOverlays

    @Before fun setup() {
        resetOverlayFieldForTest("modalRenderer")
        resetOverlayFieldForTest("useHeavyweightOverlays")
        BossOverlayHost.useHeavyweightOverlays = true
        // The heavyweight path, rendered inline so the card lives in this test's own window.
        BossOverlayHost.modalRenderer = { _, _, content -> content() }
    }

    @After fun cleanup() {
        resetOverlayFieldForTest("modalRenderer")
        resetOverlayFieldForTest("useHeavyweightOverlays")
        BossOverlayHost.modalRenderer = previousRenderer
        BossOverlayHost.useHeavyweightOverlays = previousHeavyweight
    }

    private val windowWidth = 800
    private val windowHeight = 500

    private val longDescription =
        (1..12).joinToString(" ") {
            "Runs a shell command in a terminal tab in the sidebar and streams its output back, line $it of the prose."
        }

    private val longCommand = (1..80).joinToString(" && ") { "echo step-$it-with-a-fairly-long-argument" }

    private fun request() =
        McpApprovalRequest(
            toolName = "ai.rever.boss.plugin.dynamic.terminaltab::" + "run_in_sidebar".repeat(6),
            providerId = "ai.rever.boss.plugin.dynamic.terminaltab",
            arguments =
                mapOf(
                    "command" to longCommand,
                    "env" to mapOf("GITHUB_TOKEN" to "ghp_not_a_real_token_value"),
                    "name" to "build",
                    "config_id" to "cfg-1",
                ),
            timeoutMs = 45_000L,
            riskAssessment =
                McpRiskAssessment(
                    McpRiskLevel.HIGH,
                    "Shell execution tool 'run_in_sidebar' allows arbitrary command execution",
                ),
            declaredReadOnly = false,
            toolDescription = longDescription,
            policy = McpPolicyAction.ASK,
        )

    private val approvals = mutableListOf<Triple<Boolean, Boolean, Boolean>>()
    private val denials = mutableListOf<Pair<String, Boolean>>()

    private fun show() {
        val request = request()
        rule.setContent {
            CompositionLocalProvider(
                LocalHeavyweightOverlays provides true,
                LocalDensity provides Density(1f),
                LocalBossColors provides BossBlueprintColorScheme,
                LocalWindowInfo provides
                    object : WindowInfo {
                        override val isWindowFocused = true
                        override val containerSize = IntSize(windowWidth, windowHeight)
                    },
            ) {
                Box(Modifier.size(windowWidth.dp, windowHeight.dp).clipToBounds()) {
                    McpApprovalDialog(
                        request = request,
                        onApprove = { session, persist, provider -> approvals += Triple(session, persist, provider) },
                        onDeny = { reason, persist -> denials += reason to persist },
                    )
                }
            }
        }
        // Past the heavyweight modal's input-arming delay, so clicks land.
        rule.mainClock.advanceTimeBy(250)
    }

    private fun window() = DpRect(0.dp, 0.dp, windowWidth.dp, windowHeight.dp)

    private fun SemanticsNodeInteraction.assertInsideWindow(label: String): SemanticsNodeInteraction {
        assertIsDisplayed()
        val clipped = getBoundsInRoot()
        val unclipped = getUnclippedBoundsInRoot()
        val w = window()
        assertTrue(
            unclipped.left >= w.left && unclipped.top >= w.top &&
                unclipped.right <= w.right && unclipped.bottom <= w.bottom,
            "$label must sit inside the ${windowWidth}x$windowHeight window, was $unclipped",
        )
        // Not clipped by a scrolling ancestor either: the whole button is visible.
        assertEquals(unclipped, clipped, "$label is partly clipped")
        assertTrue(unclipped.width > 0.dp && unclipped.height > 0.dp, "$label has no size: $unclipped")
        return this
    }

    private fun assertActionsReachable() {
        rule.onNodeWithText("Allow once").assertInsideWindow("Allow")
        rule.onNodeWithText("Deny").assertInsideWindow("Deny")
        rule.onNodeWithText("Expires in", substring = true).assertInsideWindow("Countdown")
    }

    @Test fun `allow and deny stay inside a short window with a long description and arguments`() {
        show()
        assertActionsReachable()

        rule.onNodeWithText("Allow once").performClick()
        rule.runOnIdle { assertEquals(listOf(Triple(false, false, false)), approvals) }
    }

    @Test fun `deny is reachable and denies once`() {
        show()
        rule.onNodeWithText("Deny").performClick()
        rule.runOnIdle { assertEquals(listOf("Operator declined this action" to false), denials) }
    }

    @Test fun `scrolling the body to the last scope keeps the actions pinned`() {
        show()
        rule.onNodeWithText("Always, for every tool from this plugin").performScrollTo().assertIsDisplayed()
        assertActionsReachable()
        rule.onNodeWithText("Always, for every tool from this plugin").performClick()
        rule.onNodeWithText("Trust plugin").assertInsideWindow("Trust plugin").performClick()
        rule.runOnIdle { assertEquals(listOf(Triple(false, false, true)), approvals) }
    }

    @Test fun `the long description is collapsed until asked for`() {
        show()
        val collapsed = rule.onNodeWithText(longDescription).getUnclippedBoundsInRoot()
        // Three 12sp lines at density 1, with room for line height; the full text is 12+ lines.
        assertTrue(collapsed.height <= 60.dp, "collapsed description is ${collapsed.height} tall")
        rule.onNodeWithText("Show more").assertIsDisplayed().performClick()
        val expanded = rule.onNodeWithText(longDescription).getUnclippedBoundsInRoot()
        assertTrue(expanded.height > collapsed.height * 2, "expanded ${expanded.height} vs ${collapsed.height}")
        rule.onNodeWithText("Show less").assertExists()
        // Expanded, the body scrolls; the answer is still on screen.
        assertActionsReachable()
    }

    @Test fun `arguments open at their top in a bounded box and keep env redacted`() {
        show()
        val box = rule.onNodeWithTag(APPROVAL_ARGUMENTS_TAG).getUnclippedBoundsInRoot()
        assertTrue(box.height <= 141.dp, "arguments box is ${box.height} tall")
        val first = rule.onNodeWithText("command = echo step-1-", substring = true).getUnclippedBoundsInRoot()
        assertTrue(
            first.top >= box.top && first.top <= box.top + 12.dp,
            "the command line must start at the top of its box: line $first, box $box",
        )
        rule.onNodeWithText("env = {", substring = true).assertExists()
        rule.onNodeWithText("[REDACTED]", substring = true).assertExists()
        rule.onNodeWithText("ghp_not_a_real_token_value", substring = true).assertDoesNotExist()
    }

    @Test fun `one countdown only, and the risk box is still shown`() {
        show()
        rule.onNodeWithText("Expires in 0:4", substring = true).assertIsDisplayed()
        rule.onNodeWithText("auto-denies", substring = true).assertDoesNotExist()
        rule.onNodeWithText("HIGH: Shell execution tool", substring = true).assertExists()
        rule.onNodeWithText("This tool performs mutations or external execution.").assertExists()
    }
}
