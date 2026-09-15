package ai.rever.boss.plugin.sandbox.notification

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins that a toast's plugin-controlled text is bounded, so it cannot push a dismiss button
 * off-window (BossConsole#154).
 *
 * `title` and `message` are arbitrary plugin strings. Before [TOAST_TITLE_MAX_LINES] /
 * [TOAST_MESSAGE_MAX_LINES] neither [androidx.compose.material3.Text] had a `maxLines`, so a verbose
 * toast grew without limit - and a stack of [PluginToastState]'s `maxToasts` (3) INDEFINITE toasts,
 * which clear only by hand, grew the content-sized overlay past the parent pane it may fill, landing
 * the lowest toast's dismiss button off-window where it cannot be clicked. The two assertions below
 * are the single toast and the full stack: capping each toast keeps both within the overlay's own
 * 600.dp ceiling (`TOAST_OVERLAY_INITIAL_SIZE`), so every dismiss button stays reachable.
 *
 * Removing either `maxLines` fails these: the single toast balloons past [SINGLE_TOAST_CEILING] and
 * the stack's lowest dismiss button falls past [STACK_CEILING].
 */
class PluginToastTextBoundsTest {
    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(Job())

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `a single toast with unbounded text stays within a bounded height`() {
        rule.setContent {
            // Width-constrained so the message must wrap; the wrapper's height then equals the
            // toast's, which is what we measure.
            Box(Modifier.width(TOAST_WIDTH).testTag(TOAST_TAG)) {
                PluginToast(
                    message =
                        ToastMessage(
                            type = ToastType.ERROR,
                            title = LOREM.repeat(20),
                            message = LOREM.repeat(400),
                        ),
                    onDismiss = {},
                )
            }
        }

        val bounds = rule.onNodeWithTag(TOAST_TAG).getUnclippedBoundsInRoot()
        val height = bounds.bottom - bounds.top
        assertTrue(
            height < SINGLE_TOAST_CEILING,
            "A toast with unbounded text rendered $height, past the $SINGLE_TOAST_CEILING cap - " +
                "its text is no longer bounded by maxLines.",
        )
    }

    @Test
    fun `a full stack of verbose toasts keeps every dismiss button within the overlay ceiling`() {
        val toastState = PluginToastState(scope, maxToasts = 3)
        repeat(3) { i ->
            toastState.show(
                ToastMessage(
                    type = ToastType.ERROR,
                    title = "Plugin error $i ${LOREM.repeat(20)}",
                    message = LOREM.repeat(400),
                    duration = ToastDuration.INDEFINITE,
                ),
            )
        }

        rule.setContent {
            Box(Modifier.width(OVERLAY_WIDTH)) {
                PluginToastHost(toastState = toastState)
            }
        }
        rule.waitForIdle()

        val dismissButtons = rule.onAllNodesWithContentDescription("Dismiss").fetchSemanticsNodes()
        assertEquals(3, dismissButtons.size, "Expected three toasts to be shown.")

        val lowestBottom =
            (0 until 3).maxOf { i ->
                rule.onAllNodesWithContentDescription("Dismiss")[i].getUnclippedBoundsInRoot().bottom
            }
        assertTrue(
            lowestBottom < STACK_CEILING,
            "The lowest dismiss button sits at $lowestBottom, past the $STACK_CEILING overlay " +
                "ceiling - a full stack of verbose toasts still overflows the window.",
        )
    }

    private companion object {
        const val TOAST_TAG = "bounded-toast"
        val TOAST_WIDTH = 400.dp
        val OVERLAY_WIDTH = 432.dp

        // A single capped toast (2-line title + 6-line message + chrome) is well under this; an
        // uncapped one wrapping hundreds of lines is thousands of dp.
        val SINGLE_TOAST_CEILING = 300.dp

        // The overlay's own first-frame ceiling is 600.dp (TOAST_OVERLAY_INITIAL_SIZE); a capped
        // three-toast stack stays under it, an uncapped stack runs far past.
        val STACK_CEILING = 600.dp

        const val LOREM = "lorem ipsum dolor sit amet "
    }
}
