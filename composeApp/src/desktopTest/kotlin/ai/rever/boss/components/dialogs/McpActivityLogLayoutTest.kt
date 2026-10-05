package ai.rever.boss.components.dialogs

import ai.rever.boss.components.overlays.resetOverlayFieldForTest
import ai.rever.boss.mcp.McpApprovalDisposition
import ai.rever.boss.mcp.McpOperationRecord
import ai.rever.boss.mcp.McpPolicyAction
import ai.rever.boss.plugin.ui.BossBlueprintColorScheme
import ai.rever.boss.plugin.ui.BossBlueprintLightColorScheme
import ai.rever.boss.plugin.ui.BossOverlayHost
import ai.rever.boss.plugin.ui.LocalBossColors
import ai.rever.boss.plugin.ui.LocalHeavyweightOverlays
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpActivityLogLayoutTest {
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

    private var captureTheme = "dark"

    private fun show(
        light: Boolean = false,
        windowSize: IntSize = IntSize(700, 360),
        panelAlpha: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        captureTheme = if (light) "light" else "dark"
        val colors = if (light) BossBlueprintLightColorScheme else BossBlueprintColorScheme
        rule.setContent {
            CompositionLocalProvider(
                LocalHeavyweightOverlays provides true,
                LocalDensity provides Density(1f),
                LocalBossColors provides colors.copy(panel = colors.panel.copy(alpha = panelAlpha)),
                LocalWindowInfo provides
                    object : WindowInfo {
                        override val isWindowFocused = true
                        override val containerSize = windowSize
                    },
            ) {
                val width = (if (windowSize.width > 0) windowSize.width else 700).dp
                Box(Modifier.size(width, 360.dp).clipToBounds()) { content() }
            }
        }
        rule.mainClock.advanceTimeBy(250)
    }

    private var captureIndex = 0

    private fun closeIsInsideWindow() {
        if (System.getenv("BOSS_REVIEW_CAPTURE") == "1") captureLayout()
        rule.onNodeWithText("Close").assertIsDisplayed()
        val bounds = rule.onNodeWithText("Close").getUnclippedBoundsInRoot()
        assertTrue(bounds.top >= 0.dp && bounds.bottom <= 360.dp, "Close bounds: $bounds")
    }

    private fun record(id: String) =
        McpOperationRecord(
            id = id,
            timestamp = 0,
            toolName = id,
            providerId = "provider".repeat(80),
            policyApplied = McpPolicyAction.ASK,
            approvalDisposition = McpApprovalDisposition.POLICY_PERSIST_FAILED,
            durationMs = 1,
            isError = true,
            sanitizedArgs = emptyMap(),
        )

    private fun captureLayout() {
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
        val name = "${javaClass.simpleName}-$captureTheme-${++captureIndex}.png"
        val output = File("build/reports/mcp-review", name)
        output.parentFile.mkdirs()
        javax.imageio.ImageIO.write(image, "png", output)
    }

    @Test fun `short dark window keeps close visible with 100 long metadata rows`() {
        var closed = false
        show { McpActivityLogDialog((1..100).map { record("tool-$it") }, 100, 100) { closed = true } }
        closeIsInsideWindow()
        rule.onNodeWithText("tool-100").performScrollTo().assertIsDisplayed()
        closeIsInsideWindow()
        rule.onNodeWithText("Close").performClick()
        rule.runOnIdle { assertTrue(closed) }
    }

    @Test fun `empty light dialog updates live without reopening and still closes`() {
        val records = mutableStateOf(emptyList<McpOperationRecord>())
        show(light = true) { McpActivityLogDialog(records.value, records.value.size.toLong(), 0, onDismiss = {}) }
        rule.onNodeWithText("No MCP tool calls recorded yet this session.").assertExists()
        closeIsInsideWindow()
        rule.runOnIdle { records.value = listOf(record("new-call")) }
        rule.onNodeWithText("No MCP tool calls recorded yet this session.").assertDoesNotExist()
        rule.onNodeWithText("new-call").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Policy Persist Failed").assertExists()
        closeIsInsideWindow()
    }

    @Test fun `unknown window size falls back without collapsing the dialog`() {
        show(windowSize = IntSize.Zero) { McpActivityLogDialog(emptyList(), 0, 0, onDismiss = {}) }
        closeIsInsideWindow()
        rule.onNodeWithText("Disk persistence is not configured", substring = true).assertExists()
    }

    @Test fun `narrow window keeps close horizontally inside the viewport`() {
        show(windowSize = IntSize(360, 360)) {
            McpActivityLogDialog(listOf(record("tool")), 1, 1, ledgerPath = "/actual/ledger.jsonl", onDismiss = {})
        }
        closeIsInsideWindow()
        assertTrue(rule.onNodeWithText("Close").getUnclippedBoundsInRoot().right <= 360.dp)
        rule.onNodeWithText("/actual/ledger.jsonl", substring = true).assertExists()
    }

    private fun assertOpaqueCard(
        title: String,
        light: Boolean,
    ) {
        val bounds = rule.onNodeWithText(title).getUnclippedBoundsInRoot()
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        // Sample the card's padding, clear of text and the rounded corner.
        val background = pixels[(bounds.left.value - 12).toInt(), (bounds.top.value - 12).toInt()]
        val colors = if (light) BossBlueprintLightColorScheme else BossBlueprintColorScheme
        assertEquals(colors.panel.toArgb(), background.toArgb())
    }

    @Test fun `activity card is opaque with a translucent dark panel token`() {
        show(panelAlpha = 0.25f) { McpActivityLogDialog(emptyList(), 0, 0, onDismiss = {}) }
        assertOpaqueCard("MCP Activity Log", light = false)
    }

    @Test fun `activity card is opaque with a translucent light panel token`() {
        show(light = true, panelAlpha = 0.25f) { McpActivityLogDialog(emptyList(), 0, 0, onDismiss = {}) }
        assertOpaqueCard("MCP Activity Log", light = true)
    }

    @Test fun `trusted plugins card is opaque with a translucent dark panel token`() {
        show(panelAlpha = 0.25f) { McpProviderTrustDialog(emptyMap(), onRevoke = { true }, onDismiss = {}) }
        assertOpaqueCard("Trusted Plugins", light = false)
    }

    @Test fun `trusted plugins card is opaque with a translucent light panel token`() {
        show(light = true, panelAlpha = 0.25f) {
            McpProviderTrustDialog(emptyMap(), onRevoke = { true }, onDismiss = {})
        }
        assertOpaqueCard("Trusted Plugins", light = true)
    }
}
