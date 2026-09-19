package ai.rever.boss.components.dialogs

import ai.rever.boss.components.overlays.resetOverlayFieldForTest
import ai.rever.boss.mcp.McpApprovalRequest
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File

/**
 * The approval dialog lists the stored startup commands a call would run, in full, so the
 * operator approves commands and not an id. Set `BOSS_REVIEW_CAPTURE=1` to write the rendered
 * dialog to `build/reports/mcp-review`.
 */
class McpApprovalDialogStoredCommandsTest {
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

    private fun show(request: McpApprovalRequest) {
        rule.setContent {
            CompositionLocalProvider(
                LocalHeavyweightOverlays provides true,
                LocalDensity provides Density(1f),
                LocalBossColors provides BossBlueprintColorScheme,
                LocalWindowInfo provides
                    object : WindowInfo {
                        override val isWindowFocused = true
                        override val containerSize = IntSize(720, 620)
                    },
            ) {
                Box(Modifier.size(720.dp, 620.dp).clipToBounds()) {
                    McpApprovalDialog(request = request, onApprove = { _, _, _ -> }, onDeny = { _, _ -> })
                }
            }
        }
        rule.mainClock.advanceTimeBy(250)
    }

    @Test fun `stored commands are listed one per line above the buttons`() {
        show(
            McpApprovalRequest(
                toolName = "open_workspace",
                providerId = "boss-workspace",
                arguments = mapOf("workspaceId" to "api-service", "windowId" to "window-1"),
                timeoutMs = 45_000L,
                riskAssessment =
                    McpRiskAssessment(
                        McpRiskLevel.HIGH,
                        "Runs 2 stored startup command(s) the arguments do not show; arbitrary shell execution",
                    ),
                declaredReadOnly = false,
                storedCommands = listOf("cd ~/api && docker compose up -d", "npm run dev"),
            ),
        )
        if (System.getenv("BOSS_REVIEW_CAPTURE") == "1") captureLayout()
        rule.onNodeWithText("This Space will run 2 stored startup command(s)", substring = true).assertIsDisplayed()
        rule.onNodeWithText("$ cd ~/api && docker compose up -d").assertIsDisplayed()
        rule.onNodeWithText("$ npm run dev").assertIsDisplayed()
        rule.onNodeWithText("Approve Once").assertIsDisplayed()
    }

    @Test fun `a request without stored commands shows no such section`() {
        show(
            McpApprovalRequest(
                toolName = "open_workspace",
                providerId = "boss-workspace",
                arguments = mapOf("workspaceId" to "plain"),
                timeoutMs = 45_000L,
            ),
        )
        rule.onNodeWithText("stored startup command", substring = true).assertDoesNotExist()
    }

    private fun captureLayout() {
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
        val output = File("build/reports/mcp-review", "${javaClass.simpleName}.png")
        output.parentFile.mkdirs()
        javax.imageio.ImageIO.write(image, "png", output)
    }
}
