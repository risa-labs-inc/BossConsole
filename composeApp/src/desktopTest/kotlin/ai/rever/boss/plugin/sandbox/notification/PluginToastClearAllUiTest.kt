package ai.rever.boss.plugin.sandbox.notification

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The "Clear all" control in [PluginToastHost]: absent for a single toast, present for a stack, and
 * wired to [PluginToastState.dismissAll].
 *
 * `dismissAll()` has been on the controller with no surface that calls it, so a user faced with a
 * stack of INDEFINITE toasts (which clear only by hand) had to dismiss each one. This is that
 * surface.
 */
class PluginToastClearAllUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(Job())

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun toast(i: Int) =
        ToastMessage(
            type = ToastType.ERROR,
            title = "Plugin error $i",
            message = "Something went wrong ($i).",
            duration = ToastDuration.INDEFINITE,
        )

    @Test
    fun `a single toast shows no clear-all control`() {
        val state = PluginToastState(scope, maxToasts = 3)
        state.show(toast(0))

        rule.setContent {
            Box(Modifier.width(432.dp)) { PluginToastHost(toastState = state) }
        }
        rule.waitForIdle()

        rule.onAllNodesWithText("Clear all").assertCountEquals(0)
    }

    @Test
    fun `clear-all dismisses the whole stack`() {
        val state = PluginToastState(scope, maxToasts = 3)
        repeat(3) { state.show(toast(it)) }

        rule.setContent {
            Box(Modifier.width(432.dp)) { PluginToastHost(toastState = state) }
        }
        rule.waitForIdle()

        assertEquals(3, state.toastCount())
        rule.onNodeWithText("Clear all").performClick()
        rule.waitForIdle()

        assertEquals(0, state.toastCount(), "Clear all should empty the toast stack.")
        rule.onAllNodesWithText("Clear all").assertCountEquals(0)
    }
}
