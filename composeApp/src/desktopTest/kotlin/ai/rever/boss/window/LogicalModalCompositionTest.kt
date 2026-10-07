package ai.rever.boss.window

import ai.rever.boss.components.overlays.HeavyweightModal
import ai.rever.boss.plugin.browser.LocalAwtWindow
import ai.rever.boss.sharing.captureSurfaceSnapshot
import ai.rever.boss.sharing.onEdt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.awt.Dialog
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import java.awt.Window as AwtWindow

@OptIn(ExperimentalTestApi::class)
class LogicalModalCompositionTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "BOSS_TEST_NATIVE_TOOLBAR", matches = "1")
    fun `heavyweight modal marker follows composition and only revokes its exact owner`(): Unit =
        runComposeUiTest {
            val fixture = CompositionFixture()
            var open by mutableStateOf(false)
            setContent { FixtureWindows(fixture, open) }
            waitUntil(timeoutMillis = 10_000) {
                onEdt { fixture.roots.size == 2 && fixture.roots.values.all { it.isShowing } }
            }
            var rootAuthority: () -> Boolean = { true }
            var otherAuthority: () -> Boolean = { true }
            runOnIdle {
                rootAuthority = WindowInputModalBoundary.captureAuthority(fixture.roots.getValue(0))
                otherAuthority = WindowInputModalBoundary.captureAuthority(fixture.roots.getValue(1))
                open = true
            }
            waitUntil(timeoutMillis = 10_000) { onEdt { fixture.modal?.isShowing == true } }
            val first = onEdt { checkNotNull(fixture.modal) }
            runOnIdle {
                fixture.assertModal()
                assertFalse(rootAuthority())
                assertTrue(otherAuthority(), "A different BossWindow must retain its input authority")
                rootAuthority = WindowInputModalBoundary.captureAuthority(fixture.roots.getValue(0))
                open = false
            }
            waitUntil(timeoutMillis = 10_000) { onEdt { !first.isDisplayable } }
            runOnIdle {
                assertFalse(WindowInputModalBoundary.isModal(first))
                assertFalse(rootAuthority(), "Modal disposal must retire queued authority too")
                assertTrue(otherAuthority())
                open = true
            }
            waitUntil(timeoutMillis = 10_000) {
                onEdt { fixture.modal !== first && fixture.modal?.isShowing == true }
            }
            runOnIdle {
                assertNotSame(first, fixture.modal)
                fixture.assertModal()
                open = false
            }
            waitUntil(timeoutMillis = 10_000) { onEdt { fixture.modal?.isDisplayable == false } }
            runOnIdle { assertFalse(WindowInputModalBoundary.isModal(checkNotNull(fixture.modal))) }
        }

    @Composable
    private fun FixtureWindows(
        fixture: CompositionFixture,
        open: Boolean,
    ) {
        for (index in 0..1) {
            Window(
                onCloseRequest = {},
                title = "Logical modal composition fixture $index",
                icon = BossWindowIcon.painter,
                state = rememberWindowState(width = 360.dp, height = 240.dp),
                focusable = false,
            ) {
                ApplyBossWindowIcon(window)
                DisposableEffect(window) {
                    fixture.roots[index] = window
                    onDispose { }
                }
                CompositionLocalProvider(LocalAwtWindow provides window) {
                    if (index == 0 && open) ComposedModal(fixture)
                }
            }
        }
    }

    @Composable
    private fun ComposedModal(fixture: CompositionFixture) {
        HeavyweightModal(DialogProperties(), onDismissRequest = {}) {
            val modal = checkNotNull(LocalAwtWindow.current)
            DisposableEffect(modal) {
                fixture.modal = modal
                onDispose { }
            }
        }
    }

    private class CompositionFixture {
        val roots = mutableMapOf<Int, AwtWindow>()
        var modal: AwtWindow? = null

        fun assertModal() {
            val current = checkNotNull(modal)
            val root = roots.getValue(0)
            assertSame(root, current.owner)
            assertFalse((current as Dialog).isModal)
            assertTrue(WindowInputModalBoundary.isModal(current))
            assertTrue(captureSurfaceSnapshot(root).surfaces.single { it.window === current }.modal)
        }
    }
}
