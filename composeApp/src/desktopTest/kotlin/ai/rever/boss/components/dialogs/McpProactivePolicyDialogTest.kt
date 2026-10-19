package ai.rever.boss.components.dialogs

import ai.rever.boss.components.home.LocalPluginStates
import ai.rever.boss.components.overlays.resetOverlayFieldForTest
import ai.rever.boss.components.plugin.DynamicPluginInfo
import ai.rever.boss.mcp.McpPolicyAction
import ai.rever.boss.mcp.McpProactivePolicyOutcome
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.api.PluginState
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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpProactivePolicyDialogTest {
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
        content: @Composable () -> Unit,
    ) {
        captureTheme = if (light) "light" else "dark"
        rule.setContent {
            CompositionLocalProvider(
                LocalHeavyweightOverlays provides true,
                LocalDensity provides Density(1f),
                LocalBossColors provides if (light) BossBlueprintLightColorScheme else BossBlueprintColorScheme,
                LocalWindowInfo provides
                    object : WindowInfo {
                        override val isWindowFocused = true
                        override val containerSize = windowSize
                    },
            ) {
                val width = (if (windowSize.width > 0) windowSize.width else 700).dp
                val height = (if (windowSize.height > 0) windowSize.height else 360).dp
                Box(Modifier.size(width, height).clipToBounds()) { content() }
            }
        }
        rule.mainClock.advanceTimeBy(250)
    }

    private var captureIndex = 0

    private fun closeIsInsideWindow() {
        if (System.getenv("BOSS_REVIEW_CAPTURE") == "1") captureLayout()
        rule.onNodeWithText("Close").assertIsDisplayed()
        val bounds = rule.onNodeWithText("Close").getUnclippedBoundsInRoot()
        val windowBounds = rule.onRoot().getUnclippedBoundsInRoot()
        assertTrue(bounds.top >= windowBounds.top && bounds.bottom <= windowBounds.bottom, "Close bounds: $bounds")
    }

    private fun captureLayout() {
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(x, y, pixels[x, y].toArgb())
        val name = "${javaClass.simpleName}-$captureTheme-${++captureIndex}.png"
        val output = File("build/reports/mcp-review", name)
        output.parentFile.mkdirs()
        javax.imageio.ImageIO.write(image, "png", output)
    }

    @Test fun `short window keeps close visible with saved and proactive rows`() {
        var closed = false
        show {
            McpPolicyManagerDialog(
                rules = (1..30).associate { "saved-$it" to McpPolicyAction.DENY },
                availableTools = listOf(McpToolIdentity("tool", "provider".repeat(80), 0)),
                onRevoke = { true },
                onSetPolicy = { _, _ -> McpProactivePolicyOutcome.Saved },
                onRefreshCandidates = {},
                onDismiss = { closed = true },
            )
        }
        closeIsInsideWindow()
        rule.onNodeWithText("Allow", substring = false).performScrollTo().assertIsDisplayed()
        closeIsInsideWindow()
        rule.onNodeWithText("Close").performClick()
        rule.runOnIdle { assertTrue(closed) }
    }

    @Test fun `view excludes risk-classified vault tools even when declared read only`() {
        val vault = McpToolIdentity("secrets_list", "vault", 0, readOnly = true)
        val prefixed = vault.copy(toolName = "mcp__boss__secrets_list")
        assertTrue(!vault.isViewTool())
        assertTrue(!prefixed.isViewTool())
        assertEquals(emptySet(), sectionSelection(listOf(vault, prefixed), McpSectionMode.View))
        assertEquals(
            McpSectionMode.None,
            savedSectionMode(listOf(vault), mapOf(vault.toolName to McpPolicyAction.DENY)),
        )
    }

    @Test fun `sensitive grants require review and remain retryable after failure`() {
        val tools = listOf(McpToolIdentity("secrets_list", "vault", 0, "List vault entries", readOnly = true))
        var writes = 0
        show(windowSize = IntSize(700, 800)) {
            McpPolicyManagerDialog(
                emptyMap(),
                tools,
                { true },
                { _, _ -> McpProactivePolicyOutcome.Saved },
                {},
                {},
                sectionTools = tools,
                onApplySection = {
                    writes++
                    if (writes == 1) McpProactivePolicyOutcome.Failed("disk") else McpProactivePolicyOutcome.Saved
                },
            )
        }
        rule.onNodeWithContentDescription("All for all sections").performScrollTo().performClick()
        rule.onNodeWithText("Review sensitive allows").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(0, writes) }
        rule.onNodeWithText("secrets_list · HIGH", substring = true).assertExists()
        captureTheme = "sensitive-dark"
        closeIsInsideWindow()
        rule.onNodeWithText("Confirm sensitive allows").performScrollTo().performClick()
        rule.onNodeWithText("Review sensitive allows").assertExists()
        rule.onNodeWithText("Could not save", substring = true).assertExists()
        rule.onNodeWithText("Review sensitive allows").performScrollTo().performClick()
        rule.onNodeWithText("Confirm sensitive allows").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(2, writes) }
    }

    @Test fun `saved policy search matches plugin names and sensitive review includes denials`() {
        val tool = McpToolIdentity("read", "plugin.id::vault", 0, "Read current state", readOnly = true)
        val rules = mapOf("read" to McpPolicyAction.DENY)
        assertEquals("Documents", policySectionName(tool.providerId, mapOf("plugin.id" to "Documents")))
        assertEquals(rules, filterSavedPolicies(rules, listOf(tool), "Documents", mapOf("plugin.id" to "Documents")))
        assertEquals(rules, filterSavedPolicies(rules, listOf(tool), "vault", mapOf("plugin.id" to "Documents")))
        assertEquals(listOf(tool), sensitiveAllows(listOf(tool), setOf("read"), rules))
        // A declared-mutating tool with an innocent name must reach the review gate through the
        // catalog signal too - risk level and saved denials must not be the only ways in (#804).
        val declaredMutating = McpToolIdentity("data_fetch", "plugin.id", 0, "Fetch data", readOnly = false)
        assertEquals(
            listOf(declaredMutating),
            sensitiveAllows(listOf(declaredMutating), setOf("data_fetch"), emptyMap()),
        )
        assertEquals("Saved: Ask before running", savedPolicyLabel(McpPolicyAction.ASK))
    }

    @Test fun `isViewTool treats the catalog as the single classification point`() {
        // The four inputs of the truth table - name signal crossed with the provider declaration.
        // Someone restoring a separate `readOnly &&` conjunct later would quietly re-narrow the
        // View bucket away from exactly the tools #804 routes to the gate, so pin all four.
        assertFalse(
            McpToolIdentity("k8s_delete", "p", 0, "d", readOnly = true).isViewTool(),
        ) // the name wins - a lying read-only claim never upgrades
        assertFalse(
            McpToolIdentity("k8s_delete", "p", 0, "d", readOnly = false).isViewTool(),
        )
        assertTrue(
            McpToolIdentity("data_fetch", "p", 0, "d", readOnly = true).isViewTool(),
        ) // innocent name + honest read-only declaration: view
        assertFalse(
            McpToolIdentity("data_fetch", "p", 0, "d", readOnly = false).isViewTool(),
        ) // innocent name + declared side effects: edit, one call
    }

    @Test fun `global none includes sections hidden by search and waits for confirmation`() {
        val tools =
            listOf(
                McpToolIdentity("read", "alpha", 0, readOnly = true),
                McpToolIdentity("write", "beta", 0),
            )
        var saved = emptyList<ai.rever.boss.mcp.McpSectionPolicyChange>()
        val plugins =
            MutableStateFlow(
                mapOf(
                    "alpha" to
                        DynamicPluginInfo(
                            PluginManifest(
                                pluginId = "alpha",
                                displayName = "Documents",
                                version = "1.0.0",
                                mainClass = "alpha.Main",
                                apiVersion = "1.0.0",
                            ),
                            "/plugins/alpha.jar",
                            PluginState.LOADED,
                            0L,
                            true,
                        ),
                ),
            )
        show(windowSize = IntSize(700, 800)) {
            CompositionLocalProvider(LocalPluginStates provides plugins) {
                McpPolicyManagerDialog(
                    emptyMap(),
                    tools,
                    { true },
                    { _, _ -> McpProactivePolicyOutcome.Saved },
                    {},
                    {},
                    sectionTools = tools,
                    onApplySection = {
                        saved = it
                        McpProactivePolicyOutcome.Saved
                    },
                )
            }
        }
        rule.onNodeWithText("Find a tool or provider").performTextInput("Documents")
        rule.onAllNodesWithText("Documents", substring = false).assertCountEquals(2)
        rule.onNodeWithContentDescription("None for all sections").performScrollTo().performClick()
        rule.runOnIdle { assertTrue(saved.isEmpty()) }
        captureTheme = "global-dark"
        closeIsInsideWindow()
        rule.onNodeWithText("Confirm all sections").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(setOf("alpha", "beta"), saved.map { it.providerId }.toSet())
            assertTrue(saved.all { it.action == McpPolicyAction.DENY })
        }
    }

    @Test fun `custom selection works in a short light window`() {
        val tools = listOf(McpToolIdentity("read", "provider", 0, "Read the current document", readOnly = true))
        var saved = emptyList<ai.rever.boss.mcp.McpSectionPolicyChange>()
        show(light = true) {
            McpPolicyManagerDialog(
                emptyMap(),
                tools,
                { true },
                { _, _ -> McpProactivePolicyOutcome.Saved },
                {},
                {},
                sectionTools = tools,
                onApplySection = {
                    saved = it
                    McpProactivePolicyOutcome.Saved
                },
            )
        }
        rule.onNodeWithText("Custom", substring = false).performScrollTo().performClick()
        rule.onNodeWithContentDescription("Allow read").performScrollTo().performClick()
        rule.runOnIdle { assertTrue(saved.isEmpty()) }
        captureTheme = "sections-light"
        closeIsInsideWindow()
        rule.onNodeWithText("Confirm section changes").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(McpPolicyAction.ALLOW, saved.single().action) }
    }

    @Test fun `all and update presets cover the entire section and saved mode is restored`() {
        val tools =
            listOf(
                McpToolIdentity("read", "p", 0, readOnly = true),
                McpToolIdentity("write", "p", 0, readOnly = false),
            )
        assertEquals(setOf("read", "write"), sectionSelection(tools, McpSectionMode.All))
        assertEquals(setOf("write"), sectionSelection(tools, McpSectionMode.Edit))
        assertEquals(McpSectionMode.Custom, savedSectionMode(tools, emptyMap()))
        assertEquals(
            McpSectionMode.View,
            savedSectionMode(tools, mapOf("read" to McpPolicyAction.ALLOW, "write" to McpPolicyAction.DENY)),
        )
    }

    @Test fun `view section preset stages all rules and excludes known mutations`() {
        val tools =
            listOf(
                McpToolIdentity("read", "provider", 0, "Read current state", readOnly = true),
                McpToolIdentity("k8s_delete", "provider", 0, "Delete a workload", readOnly = true),
            )
        var saved = emptyList<ai.rever.boss.mcp.McpSectionPolicyChange>()
        show(windowSize = IntSize(700, 800)) {
            McpPolicyManagerDialog(
                emptyMap(),
                tools,
                { true },
                { _, _ -> McpProactivePolicyOutcome.Saved },
                {},
                {},
                sectionTools = tools,
                onApplySection = {
                    saved = it
                    McpProactivePolicyOutcome.Saved
                },
            )
        }
        rule
            .onAllNodesWithText("View", substring = false)
            .onLast()
            .performScrollTo()
            .performClick()
        rule.runOnIdle { assertTrue(saved.isEmpty()) }
        captureTheme = "sections-dark"
        closeIsInsideWindow()
        rule.onNodeWithText("Confirm section changes").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(listOf(McpPolicyAction.ALLOW, McpPolicyAction.DENY), saved.map { it.action })
        }
        closeIsInsideWindow()
    }

    @Test fun `long descriptions remain readable without changing a policy`() {
        val description = "Edit the current document using an AI instruction. ".repeat(6)
        var writes = 0
        show {
            McpPolicyManagerDialog(
                emptyMap(),
                listOf(McpToolIdentity("ai_compose", "editor-tab", 0, description)),
                { true },
                { _, _ ->
                    writes++
                    McpProactivePolicyOutcome.Saved
                },
                {},
                {},
            )
        }
        rule.onNodeWithText(description).performScrollTo().assertExists()
        closeIsInsideWindow()
        rule.runOnIdle { assertEquals(0, writes) }
    }

    @Test fun `replacing a candidate invalidates the armed confirmation in light theme`() {
        val candidate = mutableStateOf(McpToolIdentity("tool", "provider", 0))
        var writes = 0
        show(light = true) {
            McpPolicyManagerDialog(
                emptyMap(),
                listOf(candidate.value),
                { true },
                { _, _ ->
                    writes++
                    McpProactivePolicyOutcome.Saved
                },
                {},
                {},
            )
        }
        rule.onNodeWithText("Allow", substring = false).performScrollTo().performClick()
        rule.onNodeWithText("Confirm allow?").assertExists()
        rule.runOnIdle { assertEquals(0, writes) }
        closeIsInsideWindow()
        rule.runOnIdle { candidate.value = candidate.value.copy(expectedRevocation = 1) }
        rule.onNodeWithText("Confirm allow?").assertDoesNotExist()
        rule.onNodeWithText("Allow", substring = false).performScrollTo().performClick()
        rule.onNodeWithText("Confirm allow?").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1, writes) }
    }

    @Test fun `refused write refreshes candidates and reports refusal distinctly from disk failure`() {
        var refreshes = 0
        show {
            McpPolicyManagerDialog(
                emptyMap(),
                listOf(McpToolIdentity("tool", "provider", 0)),
                { true },
                { _, _ -> McpProactivePolicyOutcome.Refused },
                { refreshes++ },
                {},
            )
        }
        rule.onNodeWithText("Deny", substring = false).performScrollTo().performClick()
        rule.onNodeWithText("Save rule").performScrollTo().performClick()
        rule.onNodeWithText("Policy changed.", substring = true).assertExists()
        rule.runOnIdle { assertEquals(1, refreshes) }
        assertTrue(McpProactivePolicyOutcome.Failed("disk").proactivePolicyMessage()!!.contains("storage"))
    }

    @Test fun `unreadable policy explains recovery inside the modal`() {
        show {
            McpPolicyManagerDialog(
                emptyMap(),
                listOf(McpToolIdentity("tool", "provider", 0)),
                { true },
                { _, _ -> McpProactivePolicyOutcome.PolicyUnreadable },
                {},
                {},
            )
        }
        rule.onNodeWithText("Deny", substring = false).performScrollTo().performClick()
        rule.onNodeWithText("Save rule").performScrollTo().performClick()
        rule.onNodeWithText("Policy file unreadable:", substring = true).performScrollTo().assertIsDisplayed()
        assertTrue(McpProactivePolicyOutcome.Denied.proactivePolicyMessage()!!.contains("already denies"))
    }

    @Test fun `unknown window size retains a usable dialog`() {
        show(windowSize = IntSize.Zero) {
            McpPolicyManagerDialog(
                emptyMap(),
                emptyList(),
                { true },
                { _, _ -> McpProactivePolicyOutcome.Saved },
                {},
                {},
            )
        }
        closeIsInsideWindow()
    }

    @Test fun `narrow window keeps close horizontally inside the viewport`() {
        show(windowSize = IntSize(360, 360)) {
            McpPolicyManagerDialog(
                emptyMap(),
                emptyList(),
                { true },
                { _, _ -> McpProactivePolicyOutcome.Saved },
                {},
                {},
            )
        }
        closeIsInsideWindow()
        assertTrue(rule.onNodeWithText("Close").getUnclippedBoundsInRoot().right <= 360.dp)
    }
}
