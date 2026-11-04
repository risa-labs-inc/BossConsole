package ai.rever.boss.plugin.sandbox.notification

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Real pointer enter/exit coverage for the toast hover-pause wiring in [PluginToastHost], driving
 * an actual Compose composition so the hover interaction and the disposal path are exercised the
 * way they run in the app - not simulated.
 *
 * The second test is the regression for the disposal bug: an earlier fix guarded the release with a
 * live `hovered` read inside `onDispose`, but a detaching `Modifier.hoverable` emits its Exit as
 * part of the same teardown, so the guard could read `false` and skip the resume, stranding the
 * controller paused for the rest of the window's life. The fix releases unconditionally on teardown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginToastHoverLifecycleTest {
    @get:Rule
    val rule = createComposeRule()

    private val scope = TestScope()
    private val state = PluginToastState(scope)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `hover pauses and pointer exit restarts the timeout`() {
        state.show(shortToast())
        rule.setContent { PluginToastHost(state, Modifier.testTag("host")) }

        rule.onNodeWithTag("host").performMouseInput { enter(center) }
        rule.waitForIdle()
        advanceTimers()
        assertEquals(1, state.toastCount(), "A hovered toast must not auto-dismiss.")

        rule.onNodeWithTag("host").performMouseInput { exit() }
        rule.waitForIdle()
        advanceTimers()
        assertEquals(0, state.toastCount(), "Leaving the toast restarts and completes the timeout.")
    }

    @Test
    fun `disposing a hovered host releases its pause for subsequent toasts`() {
        val visible = mutableStateOf(true)
        state.show(shortToast())
        rule.setContent {
            if (visible.value) PluginToastHost(state, Modifier.testTag("host"))
        }

        rule.onNodeWithTag("host").performMouseInput { enter(center) }
        rule.waitForIdle()
        advanceTimers()
        assertEquals(1, state.toastCount())

        rule.runOnIdle { visible.value = false }
        rule.waitForIdle()
        rule.runOnIdle {
            state.dismissAll()
            state.show(shortToast())
        }
        advanceTimers()
        assertEquals(0, state.toastCount(), "An unmounted host must not leave the controller paused.")
    }

    private fun advanceTimers() {
        rule.runOnIdle {
            scope.advanceTimeBy(6001)
            scope.runCurrent()
        }
        rule.waitForIdle()
    }

    private fun shortToast() =
        ToastMessage(
            type = ToastType.INFO,
            title = "Notice",
            message = "Details",
            duration = ToastDuration.SHORT,
        )
}
