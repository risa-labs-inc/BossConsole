package ai.rever.boss.components.overlays

import ai.rever.boss.plugin.ui.LocalHeavyweightOverlays
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * That [OverlayCorner] hands the heavyweight renderer everything it was given.
 *
 * Both arguments this pins were added for the browser find bar, and both have defaults - so a
 * forwarding mistake compiles, leaves every other test green, and shows up only as a bar in the
 * wrong corner (`regionInWindow` dropped, and the overlay resolves against the whole content pane)
 * or one that cannot be typed into (`focusable` dropped). `FocusModeQuickActionsTest` records
 * `inset` for exactly this reason; these are the same class of hazard.
 */
class OverlayCornerForwardingTest {
    @get:Rule
    val rule = createComposeRule()

    private val previousRenderer = OverlayConfig.heavyweightCorner
    private val previousHeavyweight = OverlayConfig.useHeavyweightPopups

    @After
    fun tearDown() {
        OverlayConfig.heavyweightCorner = previousRenderer
        resetOverlayFieldForTest("useHeavyweightOverlays")
        OverlayConfig.useHeavyweightPopups = previousHeavyweight
    }

    private class Captured {
        var alignment: Alignment? = null
        var initialSize: DpSize? = null
        var inset: DpSize? = null
        var focusable: Boolean? = null
        var regionInWindow: IntRect? = null
    }

    private fun capture(
        focusable: Boolean,
        regionInWindow: IntRect?,
    ): Captured {
        val captured = Captured()
        resetOverlayFieldForTest("useHeavyweightOverlays")
        OverlayConfig.useHeavyweightPopups = true
        OverlayConfig.heavyweightCorner = { alignment, initialSize, inset, isFocusable, region, _ ->
            // Recorded, not composed: composing a real Window needs a display.
            captured.alignment = alignment
            captured.initialSize = initialSize
            captured.inset = inset
            captured.focusable = isFocusable
            captured.regionInWindow = region
        }
        rule.setContent {
            CompositionLocalProvider(LocalHeavyweightOverlays provides true) {
                Box(modifier = Modifier.fillMaxSize()) {
                    OverlayCorner(
                        alignment = Alignment.TopEnd,
                        initialSize = DpSize(360.dp, 60.dp),
                        inset = DpSize(4.dp, 6.dp),
                        focusable = focusable,
                        regionInWindow = regionInWindow,
                    ) {}
                }
            }
        }
        rule.waitForIdle()
        return captured
    }

    @Test
    fun `focusable and the anchor region both reach the renderer`() {
        val region = IntRect(400, 8, 792, 592)
        val captured = capture(focusable = true, regionInWindow = region)
        assertTrue(captured.focusable == true, "a bar with a text field must be told it may take focus")
        assertEquals(
            region,
            captured.regionInWindow,
            "the pane rect must survive the hop, or the bar lands window-relative",
        )
        // The pre-existing arguments must still arrive, since adding two parameters is exactly when
        // an argument gets attached to the wrong slot.
        assertEquals(Alignment.TopEnd, captured.alignment)
        assertEquals(DpSize(360.dp, 60.dp), captured.initialSize)
        assertEquals(DpSize(4.dp, 6.dp), captured.inset)
    }

    @Test
    fun `the defaults are what a caller that says nothing gets`() {
        val captured = capture(focusable = false, regionInWindow = null)
        assertTrue(captured.focusable == false, "a toast must not take focus away from the app")
        assertNull(captured.regionInWindow, "no region means resolve against the whole content pane")
    }
}
