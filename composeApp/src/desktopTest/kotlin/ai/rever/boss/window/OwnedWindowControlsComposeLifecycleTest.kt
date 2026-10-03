package ai.rever.boss.window

import ai.rever.boss.sharing.onEdt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class OwnedWindowControlsComposeLifecycleTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "BOSS_TEST_NATIVE_TOOLBAR", matches = "1")
    fun `composable window registers a live peer and retires replaced and removed windows`(): Unit =
        runComposeUiTest {
            val lifecycle = ControlLifecycle()
            var generation by mutableStateOf(0)
            var open by mutableStateOf(true)
            setContent {
                if (open) key(generation) { RegisteredWindow(lifecycle) }
            }
            waitUntil(timeoutMillis = 10_000) { onEdt { lifecycle.windows.size == 1 } }
            runOnIdle {
                lifecycle.assertCurrent(0)
                generation++
            }
            waitUntil(timeoutMillis = 10_000) {
                onEdt { lifecycle.windows.size == 2 && lifecycle.disposals == 1 && !lifecycle.windows[0].isDisplayable }
            }
            runOnIdle {
                assertNotSame(lifecycle.windows[0], lifecycle.windows[1])
                assertFalse(lifecycle.windows[0].isDisplayable)
                assertFalse(OwnedWindowControls.supports(lifecycle.windows[0], "close"))
                // Late cleanup from the old composition must not retire the replacement.
                lifecycle.registrations[0].close()
                lifecycle.assertCurrent(1)
                open = false
            }
            waitUntil(timeoutMillis = 10_000) {
                onEdt { lifecycle.disposals == 2 && !lifecycle.windows[1].isDisplayable }
            }
            runOnIdle {
                assertFalse(lifecycle.windows[1].isDisplayable)
                assertTrue(OwnedWindowControls.capabilities(WINDOW_ID).isEmpty())
                assertEquals(2, lifecycle.commands)
            }
        }

    @Composable
    private fun RegisteredWindow(lifecycle: ControlLifecycle) {
        Window(
            onCloseRequest = {},
            title = "Owned controls lifecycle fixture",
            icon = BossWindowIcon.painter,
            state = rememberWindowState(width = 360.dp, height = 180.dp),
            focusable = false,
        ) {
            ApplyBossWindowIcon(window)
            // Deliberately no pack/addNotify/isVisible setup: this is the BossWindow effect order.
            DisposableEffect(window) {
                assertTrue(window.isDisplayable, "Compose content must start after native peer creation")
                assertTrue(window.windowHandle != 0L, "Registration must never capture a zero native handle")
                val registration = lifecycle.register(window)
                onDispose {
                    registration.close()
                    lifecycle.disposals++
                }
            }
        }
    }

    private class ControlLifecycle {
        val windows = mutableListOf<ComposeWindow>()
        val registrations = mutableListOf<AutoCloseable>()
        var disposals = 0
        var commands = 0

        fun register(window: ComposeWindow): AutoCloseable {
            windows.add(window)
            return OwnedWindowControls
                .register(WINDOW_ID, window, mapOf("close" to { commands++ }))
                .also { registrations.add(it) }
        }

        fun assertCurrent(index: Int) {
            assertEquals(listOf("close"), OwnedWindowControls.capabilities(WINDOW_ID))
            assertTrue(OwnedWindowControls.perform(windows[index], "close", Long.MAX_VALUE) { true })
        }
    }

    private companion object {
        const val WINDOW_ID = "owned-controls-compose-lifecycle-fixture"
    }
}
