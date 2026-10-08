@file:Suppress("PackageNaming")

package ai.rever.boss.components.window_panel

import ai.rever.boss.components.overlays.OverlayConfig
import ai.rever.boss.components.overlays.resetOverlayFieldForTest
import ai.rever.boss.components.sidebar.SidebarContentHost
import ai.rever.boss.components.sidebar.integratedSidebarLayout
import ai.rever.boss.components.sidebar.mainPanelSidebarClip
import ai.rever.boss.components.sidebar.rememberSidebarRevealMotion
import ai.rever.boss.components.sidebar.sidebarBodyWidth
import ai.rever.boss.components.sidebar.sidebarFrameWidth
import ai.rever.boss.components.sidebar.sidebarOverlayLayout
import ai.rever.boss.components.sidebar.sidebarShouldOverlay
import ai.rever.boss.components.window_panel.components.main_window_panels.TabBarLayout
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.LayoutDirection
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
    fun `interrupting a closing animation keeps one body until the animation settles`() {
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
                Box(Modifier.size(100.dp).testTag("animated-body"))
            }
        }
        rule.onAllNodesWithTag("animated-body").assertCountEquals(1)
        rule.runOnIdle { reveal.revealed = false }
        repeat(3) {
            rule.mainClock.advanceTimeByFrame()
            rule.onAllNodesWithTag("animated-body").assertCountEquals(1)
            assertTrue(progress in 0f..1f, "animation must keep native body geometry within its bounds")
        }
        rule.runOnIdle { reveal.revealed = true }
        rule.mainClock.advanceTimeBy(200)
        rule.onAllNodesWithTag("animated-body").assertCountEquals(1)
        assertEquals(1, mounts, "reversing the animation must retain the existing sidebar body")
        rule.runOnIdle { reveal.revealed = false }
        rule.mainClock.advanceTimeBy(200)
        rule.onAllNodesWithTag("animated-body").assertCountEquals(0)
    }

    @Test
    fun `button opened sidebar retains its viewport reservation until closing finishes`() {
        val reveal = TabBarRevealState(MutableInteractionSource(), MutableInteractionSource())
        var progress = 1f
        var overlay = false
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val motion = rememberSidebarRevealMotion(reveal, railShown = true, integrated = true)
            progress = motion.progress
            overlay = motion.overlay
            Row(Modifier.size(300.dp, 180.dp).background(Color.Black).testTag("closing-root")) {
                if (motion.visible) {
                    val bar = TabBarLayout(true, 100.dp, false, false, false)
                    Box(integratedSidebarLayout(bar, reveal, 0.dp, true, false, motion.overlay, motion.progress)) {
                        Box(Modifier.width(100.dp).fillMaxHeight().background(Color.Blue))
                    }
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color.Red)
                        .testTag("closing-main"),
                )
            }
        }

        fun mainWidth() = rule.onNodeWithTag("closing-main").getUnclippedBoundsInRoot().let { it.right - it.left }
        assertEquals(300.dp, mainWidth())
        rule.runOnIdle { reveal.openDrawer() }
        rule.mainClock.advanceTimeByFrame()
        assertEquals(1f, progress, "a button open must start from full width even on a quick close")
        assertEquals(192.dp, mainWidth())
        rule.runOnIdle { reveal.dismiss(pointerInSidebar = true) }
        repeat(3) {
            rule.mainClock.advanceTimeByFrame()
            assertFalse(overlay, "button-opened close must keep its in-flow body")
            assertEquals(192.dp, mainWidth())
        }
        assertTrue(progress > 0f && progress < 0.95f)
        val pixels = rule.onNodeWithTag("closing-root").captureToImage().toPixelMap()
        val scale = pixels.width / 300f
        assertEquals(Color.Black, pixels[(95 * scale).toInt(), pixels.height / 2], "body must close with the header")
        assertEquals(Color.Red, pixels[(150 * scale).toInt(), pixels.height / 2])
        rule.mainClock.advanceTimeBy(200)
        assertEquals(300.dp, mainWidth())
        rule.runOnIdle { reveal.revealed = true }
        rule.mainClock.advanceTimeBy(200)
        assertTrue(overlay, "the next hover reveal must return to overlay mode")
        assertEquals(300.dp, mainWidth())
    }

    @Test
    fun `pinned sidebar stays fully painted without a visible drawer`() {
        val reveal = TabBarRevealState(MutableInteractionSource(), MutableInteractionSource())
        rule.setContent {
            val motion = rememberSidebarRevealMotion(reveal, railShown = false, integrated = true)
            assertEquals(1f, motion.progress)
            assertFalse(motion.visible)
            assertFalse(motion.overlay)
        }
    }

    @Test
    fun `header frame and body have fixed insets and bounded edges`() {
        val samples =
            listOf(
                Triple(-1f, 0.dp, 0.dp),
                Triple(0f, 0.dp, 0.dp),
                Triple(0.01f, 2.48.dp, 0.dp),
                Triple(0.25f, 62.dp, 54.dp),
                Triple(0.5f, 124.dp, 116.dp),
                Triple(1f, 248.dp, 240.dp),
                Triple(2f, 248.dp, 240.dp),
            )
        for ((progress, frame, body) in samples) {
            assertEquals(frame, sidebarFrameWidth(240.dp, progress))
            assertEquals(body, sidebarBodyWidth(240.dp, progress))
        }
    }

    @Test
    fun `RTL reveal paints and receives mouse clicks at the window trailing edge`() {
        var clicks = 0
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Row(Modifier.size(300.dp, 180.dp).background(Color.Black).testTag("rtl-root")) {
                    Box(
                        sidebarOverlayLayout(true, 0.5f)
                            .width(100.dp)
                            .fillMaxHeight()
                            .background(Color.Blue)
                            .clickable { clicks++ }
                            .testTag("rtl-sidebar"),
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .then(mainPanelSidebarClip(true, 50.dp))
                            .background(Color.Red)
                            .testTag("rtl-main"),
                    )
                }
            }
        }
        assertEquals(300.dp, rule.onNodeWithTag("rtl-main").getUnclippedBoundsInRoot().let { it.right - it.left })
        val sidebarBounds = rule.onNodeWithTag("rtl-sidebar").getUnclippedBoundsInRoot()
        assertEquals(200.dp, sidebarBounds.left)
        assertEquals(300.dp, sidebarBounds.right)
        val pixels = rule.onNodeWithTag("rtl-root").captureToImage().toPixelMap()
        assertEquals(Color.Red, pixels[pixels.width * 3 / 4, pixels.height / 2])
        assertEquals(Color.Blue, pixels[pixels.width * 11 / 12, pixels.height / 2])
        rule.onNodeWithTag("rtl-sidebar").performMouseInput { click(Offset(width * 0.75f, height / 2f)) }
        rule.runOnIdle { assertEquals(1, clicks) }
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
        rule.onNodeWithTag("sidebar").performMouseInput { click() }
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
