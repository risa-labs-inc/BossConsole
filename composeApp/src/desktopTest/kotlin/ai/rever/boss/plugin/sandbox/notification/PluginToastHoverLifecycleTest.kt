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
        assertEquals(1, state.toastCount())

        rule.onNodeWithTag("host").performMouseInput { exit() }
        rule.waitForIdle()
        advanceTimers()
        assertEquals(0, state.toastCount())
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
        assertEquals(0, state.toastCount(), "An unmounted host must not leave the controller paused")
    }

    private fun advanceTimers() {
        rule.runOnIdle {
            scope.advanceTimeBy(6001)
            scope.runCurrent()
        }
        rule.waitForIdle()
    }

    private fun shortToast() =
        ToastMessage(type = ToastType.INFO, title = "Notice", message = "Details", duration = ToastDuration.SHORT)
}
