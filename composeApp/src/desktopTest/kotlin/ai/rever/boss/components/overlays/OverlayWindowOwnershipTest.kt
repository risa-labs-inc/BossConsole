package ai.rever.boss.components.overlays

import ai.rever.boss.plugin.browser.LocalAwtWindow
import ai.rever.boss.sharing.captureSurfaceSnapshot
import ai.rever.boss.sharing.onEdt
import ai.rever.boss.window.ApplyBossWindowIcon
import ai.rever.boss.window.BossWindowIcon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.awt.Dialog
import java.awt.event.WindowEvent
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import java.awt.Window as AwtWindow

@OptIn(ExperimentalTestApi::class)
class OverlayWindowOwnershipTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "BOSS_TEST_NATIVE_TOOLBAR", matches = "1")
    fun `nested overlays retain exact ownership capture scope and disposal`(): Unit =
        runComposeUiTest {
            val fixture = OverlayFixture()
            var open by mutableStateOf(true)
            setContent { FixtureWindows(fixture, open) }
            waitUntil(timeoutMillis = 10_000) {
                onEdt { fixture.windows.size == 4 && fixture.windows.values.all { it.isShowing } }
            }
            runOnIdle {
                fixture.assertOwnership()
                fixture.windows.getValue("overlay").dispatchEvent(
                    WindowEvent(fixture.windows.getValue("overlay"), WindowEvent.WINDOW_CLOSING),
                )
                assertEquals(1, fixture.closeRequests)
                open = false
            }
            waitUntil(timeoutMillis = 10_000) {
                onEdt {
                    !fixture.windows.getValue("overlay").isDisplayable &&
                        !fixture.windows.getValue("nested").isDisplayable
                }
            }
            runOnIdle {
                val parent = fixture.windows.getValue("parent")
                assertEquals(listOf(parent), captureSurfaceSnapshot(parent).surfaces.map { it.window })
                assertTrue(fixture.windows.getValue("unrelated").isShowing)
            }
        }

    @Composable
    private fun FixtureWindows(
        fixture: OverlayFixture,
        open: Boolean,
    ) {
        Window(
            onCloseRequest = {},
            title = "Unrelated overlay fixture",
            focusable = false,
            icon = BossWindowIcon.painter,
        ) {
            ApplyBossWindowIcon(window)
            DisposableEffect(window) {
                fixture.windows["unrelated"] = window
                onDispose { }
            }
        }
        Window(
            onCloseRequest = {},
            title = "Owned overlay fixture",
            icon = BossWindowIcon.painter,
            state = rememberWindowState(width = 360.dp, height = 240.dp),
            focusable = false,
        ) {
            ApplyBossWindowIcon(window)
            DisposableEffect(window) {
                fixture.windows["parent"] = window
                onDispose { }
            }
            CompositionLocalProvider(LocalAwtWindow provides window) {
                if (open) FixtureOverlay(fixture, "overlay") { FixtureOverlay(fixture, "nested") {} }
            }
        }
    }

    @Composable
    private fun FixtureOverlay(
        fixture: OverlayFixture,
        name: String,
        content: @Composable () -> Unit,
    ) {
        OverlayWindow(
            onCloseRequest = { fixture.closeRequests++ },
            state = rememberWindowState(width = 180.dp, height = 120.dp),
            focusable = false,
        ) { window ->
            val providedOwner = LocalAwtWindow.current
            DisposableEffect(window) {
                assertSame(window, providedOwner, "Nested overlays must receive their actual containing window")
                fixture.windows[name] = window
                onDispose { }
            }
            content()
        }
    }

    private class OverlayFixture {
        val windows = mutableMapOf<String, AwtWindow>()
        var closeRequests = 0

        fun assertOwnership() {
            val parent = windows.getValue("parent")
            val overlay = assertIs<Dialog>(windows.getValue("overlay"))
            val nested = assertIs<Dialog>(windows.getValue("nested"))
            assertSame(parent, overlay.owner)
            assertSame(overlay, nested.owner)
            assertFalse(overlay.isModal)
            assertFalse(overlay.focusableWindowState)
            assertFalse(overlay.isAlwaysOnTop)
            assertEquals(listOf(parent, overlay, nested), captureSurfaceSnapshot(parent).surfaces.map { it.window })
            val unrelated = windows.getValue("unrelated")
            assertEquals(listOf(unrelated), captureSurfaceSnapshot(unrelated).surfaces.map { it.window })
        }
    }
}
