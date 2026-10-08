@file:Suppress("PackageNaming")

package ai.rever.boss.components.window_panel

import ai.rever.boss.components.overlays.OverlayConfig
import ai.rever.boss.components.overlays.resetOverlayFieldForTest
import ai.rever.boss.components.sidebar.SidebarContentHost
import ai.rever.boss.components.sidebar.mainPanelSidebarClip
import ai.rever.boss.components.sidebar.rememberSidebarRevealMotion
import ai.rever.boss.components.sidebar.sidebarBodyWidth
import ai.rever.boss.components.sidebar.sidebarOverlayLayout
import ai.rever.boss.components.sidebar.sidebarShouldOverlay
import ai.rever.boss.components.window_panel.components.main_window_panels.TabBarRevealState
import ai.rever.boss.components.window_panel.components.main_window_panels.rememberTabBarRevealState
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.LocalHeavyweightOverlays
import ai.rever.boss.theme.LocalWindowGlass
import ai.rever.boss.theme.WindowGlass
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntegratedSidebarOverlayTest {
    @get:Rule val rule = createComposeRule()
    private val previousRenderer = OverlayConfig.heavyweightCorner
    private val previousHeavyweight = OverlayConfig.useHeavyweightPopups

    @After
    fun restoreRenderer() {
        OverlayConfig.heavyweightCorner = previousRenderer
        resetOverlayFieldForTest("useHeavyweightOverlays")
        OverlayConfig.useHeavyweightPopups = previousHeavyweight
    }

    @Test
    fun `interrupting a bouncing collapse keeps one body until the animation settles`() {
        val reveal =
            TabBarRevealState(MutableInteractionSource(), MutableInteractionSource()).apply {
                revealed = true
            }
        var mounts = 0
        var progress = 1f
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val motion = rememberSidebarRevealMotion(reveal, railShown = true, integrated = true)
            progress = motion.progress
            if (motion.visible) {
                DisposableEffect(Unit) {
                    mounts++
                    onDispose { }
                }
                Box(Modifier.size(100.dp).testTag("bouncing-body"))
            }
        }
        rule.onAllNodesWithTag("bouncing-body").assertCountEquals(1)
        rule.runOnIdle { reveal.revealed = false }
        repeat(3) {
            rule.mainClock.advanceTimeByFrame()
            rule.onAllNodesWithTag("bouncing-body").assertCountEquals(1)
            assertTrue(progress in 0f..1f, "bounce must keep native body geometry within its bounds")
        }
        rule.runOnIdle { reveal.revealed = true }
        rule.mainClock.advanceTimeBy(200)
        rule.onAllNodesWithTag("bouncing-body").assertCountEquals(1)
        assertEquals(1, mounts, "reversing the bounce must retain the existing sidebar body")
        rule.runOnIdle { reveal.revealed = false }
        rule.mainClock.advanceTimeBy(200)
        rule.onAllNodesWithTag("bouncing-body").assertCountEquals(0)
    }

    @Test
    fun `body edge matches header width throughout reveal`() {
        for (progress in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val body = sidebarBodyWidth(240.dp, progress)
            val header = 248.dp * progress
            assertEquals(header, body + if (header >= 8.dp) 8.dp else header)
        }
    }

    @Test
    fun `an explicitly opened sidebar reserves width even while its drawer is visible`() {
        val state =
            TabBarRevealState(
                androidx.compose.foundation.interaction
                    .MutableInteractionSource(),
                androidx.compose.foundation.interaction
                    .MutableInteractionSource(),
            )
        state.revealed = true
        assertTrue(sidebarShouldOverlay(true, state.isTransientReveal))
        state.openDrawer()
        assertTrue(state.drawerVisible)
        assertFalse(sidebarShouldOverlay(true, state.isTransientReveal))
        assertFalse(sidebarShouldOverlay(false, true))
    }

    @Test
    fun `native browser reveal uses one opaque heavyweight body and pinning returns it in window`() {
        val overlay = mutableStateOf(false)
        var nativeRequested = false
        var expectedBody = Color.Transparent
        var bodyGlassInstalled = false
        resetOverlayFieldForTest("useHeavyweightOverlays")
        OverlayConfig.useHeavyweightPopups = true
        OverlayConfig.heavyweightCorner = { _, _, _, _, _, content ->
            nativeRequested = true
            content()
        }
        rule.setContent {
            CompositionLocalProvider(
                LocalHeavyweightOverlays provides true,
                LocalWindowGlass provides WindowGlass(installed = true, coverage = "sidebar"),
            ) {
                expectedBody = BossTheme.colors.panel.copy(alpha = 1f)
                val reveal = rememberTabBarRevealState(true, false, false)
                Box(Modifier.size(300.dp, 180.dp).background(Color.Red).testTag("native-root")) {
                    SidebarContentHost(overlay.value, 100.dp, reveal, true) {
                        bodyGlassInstalled = LocalWindowGlass.current.installed
                        Box(Modifier.width(100.dp).fillMaxHeight().testTag("single-body"))
                    }
                }
            }
        }
        rule.waitForIdle()
        assertFalse(nativeRequested)
        assertTrue(bodyGlassInstalled, "the inline sidebar must retain its window glass")
        rule.runOnIdle { overlay.value = true }
        rule.waitForIdle()
        assertTrue(nativeRequested)
        assertFalse(bodyGlassInstalled, "hover content must not inherit native glass translucency")
        rule.onAllNodesWithTag("single-body").assertCountEquals(1)
        val pixels = rule.onNodeWithTag("native-root").captureToImage().toPixelMap()
        assertEquals(expectedBody, pixels[pixels.width / 6, pixels.height / 2])
        rule.runOnIdle {
            nativeRequested = false
            overlay.value = false
        }
        rule.waitForIdle()
        assertFalse(nativeRequested)
        assertTrue(bodyGlassInstalled, "pinning must restore the owning window's glass")
        rule.onAllNodesWithTag("single-body").assertCountEquals(1)
    }

    @Test
    fun `reveal keeps full main viewport and one clickable sidebar without content bleeding through`() {
        val shown = mutableStateOf(false)
        val pinned = mutableStateOf(false)
        var clicks = 0
        rule.setContent {
            Row(Modifier.size(300.dp, 180.dp).background(Color.Black).testTag("root")) {
                if (shown.value) {
                    Box(
                        sidebarOverlayLayout(!pinned.value)
                            .width(100.dp)
                            .fillMaxHeight()
                            .background(Color.Blue.copy(alpha = 0.5f))
                            .testTag("sidebar")
                            .clickable { clicks++ },
                    )
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(mainPanelSidebarClip(shown.value && !pinned.value, 100.dp))
                        .background(Color.Red)
                        .testTag("main"),
                )
            }
        }
        assertEquals(300.dp, rule.onNodeWithTag("main").getUnclippedBoundsInRoot().let { it.right - it.left })
        rule.runOnIdle { shown.value = true }
        assertEquals(300.dp, rule.onNodeWithTag("main").getUnclippedBoundsInRoot().let { it.right - it.left })
        assertEquals(100.dp, rule.onNodeWithTag("sidebar").getUnclippedBoundsInRoot().let { it.right - it.left })
        val pixels = rule.onNodeWithTag("root").captureToImage().toPixelMap()
        assertEquals(0f, pixels[pixels.width / 6, pixels.height / 2].red, "covered content must not bleed through")
        rule.onNodeWithTag("sidebar").performClick()
        rule.runOnIdle {
            assertEquals(1, clicks)
            pinned.value = true
        }
        assertEquals(200.dp, rule.onNodeWithTag("main").getUnclippedBoundsInRoot().let { it.right - it.left })
        rule.runOnIdle {
            pinned.value = false
            shown.value = false
        }
        assertEquals(300.dp, rule.onNodeWithTag("main").getUnclippedBoundsInRoot().let { it.right - it.left })
    }
}
