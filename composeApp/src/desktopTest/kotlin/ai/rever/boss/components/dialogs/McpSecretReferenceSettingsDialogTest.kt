package ai.rever.boss.components.dialogs

import ai.rever.boss.components.overlays.resetOverlayFieldForTest
import ai.rever.boss.mcp.McpHostSecretSettings
import ai.rever.boss.mcp.McpProactivePolicyOutcome
import ai.rever.boss.mcp.McpSecretPolicyAction
import ai.rever.boss.mcp.McpToolPolicyConfig
import ai.rever.boss.mcp.hostSecretSettings
import ai.rever.boss.plugin.ui.BossBlueprintColorScheme
import ai.rever.boss.plugin.ui.BossBlueprintLightColorScheme
import ai.rever.boss.plugin.ui.BossOverlayHost
import ai.rever.boss.plugin.ui.LocalBossColors
import ai.rever.boss.plugin.ui.LocalHeavyweightOverlays
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The "Secret references" dialog as an operator meets it: a draft saved in one write, a second
 * confirming tap before scrubbing is given up, a change saved elsewhere shown rather than written
 * over, and nothing editable while the policy file cannot be read.
 */
class McpSecretReferenceSettingsDialogTest {
    @get:Rule val rule = createComposeRule()
    private val previousRenderer = BossOverlayHost.modalRenderer
    private val previousHeavyweight = BossOverlayHost.useHeavyweightOverlays

    @Before fun setup() {
        resetOverlayFieldForTest("modalRenderer")
        resetOverlayFieldForTest("useHeavyweightOverlays")
        BossOverlayHost.useHeavyweightOverlays = true
        BossOverlayHost.modalRenderer = { _, _, content -> content() }
    }

    @After fun cleanup() {
        resetOverlayFieldForTest("modalRenderer")
        resetOverlayFieldForTest("useHeavyweightOverlays")
        BossOverlayHost.modalRenderer = previousRenderer
        BossOverlayHost.useHeavyweightOverlays = previousHeavyweight
    }

    private val defaults = McpToolPolicyConfig().hostSecretSettings
    private val saves = mutableListOf<Pair<McpHostSecretSettings, McpHostSecretSettings>>()
    private var closed = false
    private var captureTheme = "dark"

    private fun show(
        light: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        captureTheme = if (light) "light" else "dark"
        rule.setContent {
            CompositionLocalProvider(
                LocalHeavyweightOverlays provides true,
                LocalDensity provides Density(1f),
                LocalBossColors provides if (light) BossBlueprintLightColorScheme else BossBlueprintColorScheme,
            ) {
                Box(Modifier.size(620.dp, 640.dp).clipToBounds()) { content() }
            }
        }
        // BossDialog ignores input until it is armed, so a click that opened it cannot land in it.
        rule.mainClock.advanceTimeBy(250)
    }

    @Composable
    private fun dialog(
        saved: McpHostSecretSettings = defaults,
        policyUnreadable: Boolean = false,
        answer: () -> McpProactivePolicyOutcome = { McpProactivePolicyOutcome.Saved },
    ) {
        McpSecretReferenceSettingsDialog(
            saved = saved,
            policyUnreadable = policyUnreadable,
            onSave = { expected, updated ->
                saves += expected to updated
                answer()
            },
            onDismiss = { closed = true },
        )
    }

    @Test fun `turning delivery off is saved in one write and closes the dialog`() {
        show { dialog() }
        rule.onNodeWithContentDescription("Deliver secret references").assertIsOn().performClick()
        rule.onNodeWithText("Every call that carries a reference is refused", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Refuse them").performClick()
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()

        assertEquals(
            listOf(defaults to McpHostSecretSettings(false, McpSecretPolicyAction.DENY, true)),
            saves,
        )
        assertTrue(closed)
    }

    @Test fun `turning scrubbing off takes a second tap that says what it gives up`() {
        show { dialog() }
        rule.onNodeWithContentDescription("Scrub delivered values from results").performClick()
        rule.onNodeWithText("hands it to the agent", substring = true).assertIsDisplayed()

        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        assertEquals(emptyList(), saves, "the first tap saved without confirming")
        capture("scrubbing-off-confirm")

        rule.onNodeWithText("Confirm scrubbing off?").performClick()
        rule.waitForIdle()
        assertEquals(listOf(defaults to defaults.copy(resultScrubbingEnabled = false)), saves)
        assertTrue(closed)
    }

    @Test fun `nothing to save leaves Save disabled`() {
        show { dialog() }
        rule.onNodeWithText("Save").assertIsNotEnabled()
        capture("defaults")
    }

    @Test fun `a change saved elsewhere meanwhile is shown, not written over`() {
        var onDisk by mutableStateOf(defaults)
        // The engine refuses a save whose starting point is no longer what is on disk.
        show { dialog(saved = onDisk) { McpProactivePolicyOutcome.Refused } }
        rule.onNodeWithText("Refuse them").performClick()
        // Another window turns delivery off while this one is being edited.
        rule.runOnIdle { onDisk = defaults.copy(referencesEnabled = false) }
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Changed in another window meanwhile", substring = true).assertIsDisplayed()
        // The dialog now shows what is on disk, and its own edit is gone.
        rule.onNodeWithContentDescription("Deliver secret references").assertIsOff()
        rule.onNodeWithText("Save").assertIsNotEnabled()
        assertTrue(!closed)
    }

    @Test fun `an unreadable policy file leaves nothing editable`() {
        show(light = true) { dialog(policyUnreadable = true) }
        rule.onNodeWithText("could not be read", substring = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Deliver secret references").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Scrub delivered values from results").assertIsNotEnabled()
        rule.onNodeWithText("Refuse them").assertIsNotEnabled()
        rule.onNodeWithText("Save").assertIsNotEnabled()
        capture("policy-unreadable")
    }

    @Test fun `every unsaved outcome says why, and a saved one says nothing`() {
        assertEquals(null, secretSettingsSaveProblem(McpProactivePolicyOutcome.Saved))
        listOf(
            McpProactivePolicyOutcome.Refused,
            McpProactivePolicyOutcome.PolicyUnreadable,
            McpProactivePolicyOutcome.Denied,
            McpProactivePolicyOutcome.Failed("disk full"),
        ).forEach { outcome ->
            val problem = secretSettingsSaveProblem(outcome)
            assertTrue(!problem.isNullOrBlank(), "$outcome")
            // The disk error is for the host log, not the dialog.
            assertTrue("disk full" !in problem, problem)
        }
    }

    /** `BOSS_REVIEW_CAPTURE=1` writes what the test rendered, for review. */
    private fun capture(label: String) {
        if (System.getenv("BOSS_REVIEW_CAPTURE") != "1") return
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
        val output = File("build/reports/mcp-review", "${javaClass.simpleName}-$captureTheme-$label.png")
        output.parentFile.mkdirs()
        javax.imageio.ImageIO.write(image, "png", output)
    }
}
