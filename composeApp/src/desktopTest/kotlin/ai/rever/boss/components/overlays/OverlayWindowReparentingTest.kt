package ai.rever.boss.components.overlays

import ai.rever.boss.plugin.browser.LocalAwtWindow
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
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import java.awt.Window as AwtWindow

@OptIn(ExperimentalTestApi::class)
class OverlayWindowReparentingTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "BOSS_TEST_NATIVE_TOOLBAR", matches = "1")
    fun `retained overlay updates focus and recreates only for a different native owner`(): Unit =
        runComposeUiTest {
            val fixture = ReparentingFixture()
            var ownerIndex by mutableStateOf(0)
            var open by mutableStateOf(false)
            var focusable by mutableStateOf(false)
            setContent {
                OwnerWindows(fixture)
                if (open) RetainedOverlay(fixture, ownerIndex, focusable)
            }
            waitUntil(timeoutMillis = 10_000) {
                onEdt { fixture.owners.size == 2 && fixture.owners.values.all { it.isShowing } }
            }
            runOnIdle { open = true }
            waitUntil(timeoutMillis = 10_000) { onEdt { fixture.overlays.size == 1 } }
            runOnIdle {
                fixture.assertCurrent(0, 0, false)
                focusable = true
            }
            waitUntil(timeoutMillis = 10_000) { onEdt { fixture.overlays[0].focusableWindowState } }
            runOnIdle {
                fixture.assertCurrent(0, 0, true)
                ownerIndex = 1
            }
            waitUntil(timeoutMillis = 10_000) {
                onEdt { fixture.overlays.size == 2 && !fixture.overlays[0].isDisplayable }
            }
            runOnIdle {
                assertNotSame(fixture.overlays[0], fixture.overlays[1])
                fixture.assertCurrent(1, 1, true)
                fixture.overlays[1].dispatchEvent(WindowEvent(fixture.overlays[1], WindowEvent.WINDOW_CLOSING))
                assertEquals(listOf(1), fixture.closeOwners)
                open = false
            }
            waitUntil(timeoutMillis = 10_000) { onEdt { fixture.overlays.none { it.isDisplayable } } }
            runOnIdle { assertEquals(2, fixture.disposals) }
        }

    @Composable
    private fun OwnerWindows(fixture: ReparentingFixture) {
        for (index in 0..1) {
            Window(
                onCloseRequest = {},
                title = "Overlay owner fixture $index",
                icon = BossWindowIcon.painter,
                state = rememberWindowState(width = 360.dp, height = 240.dp),
                focusable = false,
            ) {
                ApplyBossWindowIcon(window)
                DisposableEffect(window) {
                    fixture.owners[index] = window
                    onDispose { }
                }
            }
        }
    }

    @Composable
    private fun RetainedOverlay(
        fixture: ReparentingFixture,
        ownerIndex: Int,
        focusable: Boolean,
    ) {
        CompositionLocalProvider(LocalAwtWindow provides fixture.owners.getValue(ownerIndex)) {
            OverlayWindow(
                onCloseRequest = { fixture.closeOwners.add(ownerIndex) },
                state = rememberWindowState(width = 180.dp, height = 120.dp),
                focusable = focusable,
            ) { window ->
                DisposableEffect(window) {
                    fixture.overlays.add(window)
                    onDispose { fixture.disposals++ }
                }
            }
        }
    }

    private class ReparentingFixture {
        val owners = mutableMapOf<Int, AwtWindow>()
        val overlays = mutableListOf<AwtWindow>()
        val closeOwners = mutableListOf<Int>()
        var disposals = 0

        fun assertCurrent(
            index: Int,
            ownerIndex: Int,
            focusable: Boolean,
        ) {
            assertEquals(index + 1, overlays.size)
            val overlay = overlays[index]
            assertSame(owners.getValue(ownerIndex), overlay.owner)
            assertEquals(focusable, overlay.focusableWindowState)
            assertEquals(focusable, overlay.isAutoRequestFocus)
            assertFalse((overlay as Dialog).isModal)
            assertTrue(overlay.isDisplayable)
        }
    }
}
