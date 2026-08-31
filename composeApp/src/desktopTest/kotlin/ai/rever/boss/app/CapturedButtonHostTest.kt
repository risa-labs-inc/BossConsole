package ai.rever.boss.app

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [capturedButtonHost].
 *
 * The bug this exists for shipped in the first version of the feature: the button was given to the
 * title row when that was drawn and to the top bar otherwise, with nothing asking what happens when
 * neither is on. That configuration is not exotic - switch the top bar off with a sidebar open and
 * the traffic-light clearance moves to the left columns - and the button simply did not render.
 */
class CapturedButtonHostTest {
    private fun host(
        titleRow: Boolean = false,
        topBar: Boolean = false,
        captured: Boolean = false,
        isMacOs: Boolean = true,
        enabled: Boolean = true,
    ) = capturedButtonHost(
        titleRowDrawn = titleRow,
        topBarDrawn = topBar,
        captured = captured,
        isMacOs = isMacOs,
        enabled = enabled,
    )

    @Test
    fun `the title row wins when it is drawn`() {
        assertEquals(CapturedButtonHost.TITLE_ROW, host(titleRow = true))
        assertEquals(
            CapturedButtonHost.TITLE_ROW,
            host(titleRow = true, topBar = true),
            "Both can be on screen at once, and two blue buttons is worse than one",
        )
    }

    @Test
    fun `the top bar takes it when there is no title row`() {
        assertEquals(CapturedButtonHost.TOP_BAR, host(topBar = true))
    }

    @Test
    fun `with neither bar on macOS it falls back to the clearance band`() {
        // The regression case. Top bar off, no title row: the lights are over the left columns,
        // which are inset out of the way, so the band they were inset for is empty and free.
        assertEquals(CapturedButtonHost.OVERLAY, host(titleRow = false, topBar = false))
    }

    @Test
    fun `off macOS there is no cluster to join, so no overlay`() {
        // Windows and Linux have no traffic lights and no clearance band. An overlay in the corner
        // would be a floating circle over content, which is not what the feature is.
        assertEquals(CapturedButtonHost.NONE, host(isMacOs = false))
        assertEquals(CapturedButtonHost.TOP_BAR, host(topBar = true, isMacOs = false))
    }

    @Test
    fun `nothing is drawn while a session is running`() {
        // Every bar is gone by design; the exits are the shortcuts, the hold and the HUD. A lone
        // button left over the content would undo the point of the mode.
        assertEquals(CapturedButtonHost.NONE, host(titleRow = true, captured = true))
        assertEquals(CapturedButtonHost.NONE, host(topBar = true, captured = true))
        assertEquals(CapturedButtonHost.NONE, host(captured = true))
    }

    @Test
    fun `the feature is off by default, so no button is drawn anywhere`() {
        // The default install must not show it at all - not greyed, not inert, absent.
        assertEquals(CapturedButtonHost.NONE, host(titleRow = true, enabled = false))
        assertEquals(CapturedButtonHost.NONE, host(topBar = true, enabled = false))
        assertEquals(CapturedButtonHost.NONE, host(enabled = false))
    }

    @Test
    fun `the gate outranks capture`() {
        // Order matters: the disable path ends any running session, so a captured window cannot
        // survive the setting going off, and asking the gate first is what keeps the two agreeing.
        assertEquals(CapturedButtonHost.NONE, host(titleRow = true, captured = true, enabled = false))
    }

    @Test
    fun `capture is asked before anything else`() {
        // Ordering is load-bearing: reversed, a captured window with its title row still nominally
        // wanted would draw the button over full-screen content.
        assertEquals(CapturedButtonHost.NONE, host(titleRow = true, topBar = true, captured = true))
    }
}
