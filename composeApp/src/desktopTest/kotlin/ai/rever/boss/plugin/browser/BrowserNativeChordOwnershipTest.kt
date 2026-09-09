package ai.rever.boss.plugin.browser

import com.teamdev.jxbrowser.engine.RenderingMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [FluckEngine.ownsChordsNatively] - no JxBrowser browser required.
 *
 * These exist because the decision's failure mode is a silent double action: when the native
 * PressKeyCallback and the AWT keymap both dispatch the same chord, one Cmd+W closes the active
 * tab AND the newly-active neighbour (the classic report: "Cmd+W on the tab after the terminal
 * closed the terminal and the other tab"), one Cmd+T opens two tabs, and one Cmd+Shift+F toggles
 * focus mode twice and appears to do nothing.
 *
 * The native layer may claim a chord only when it is the ONLY layer that sees it: non-macOS in
 * HARDWARE_ACCELERATED, where the native surface consumes the key before the JVM ever sees it.
 */
class BrowserNativeChordOwnershipTest {
    @Test
    fun `non-mac hardware mode is native owned`() {
        assertTrue(
            FluckEngine.ownsChordsNatively(isMacOS = false, mode = RenderingMode.HARDWARE_ACCELERATED),
            "non-macOS + HARDWARE_ACCELERATED is the only combination where the JVM never sees the chord",
        )
    }

    @Test
    fun `macOS defers to the AWT layer in every rendering mode`() {
        // macOS reaches AWT even under HARDWARE_ACCELERATED, so a native claim would be a
        // second dispatch on the same keypress.
        for (mode in RenderingMode.values()) {
            assertFalse(
                FluckEngine.ownsChordsNatively(isMacOS = true, mode = mode),
                "macOS + $mode must be served by the AWT keymap, not the native callback",
            )
        }
    }

    @Test
    fun `non-mac off-screen defers to the AWT layer`() {
        // Under OFF_SCREEN the chord arrives through AWT and Compose, so a native claim would
        // double-fire the same way it does on macOS.
        assertFalse(
            FluckEngine.ownsChordsNatively(isMacOS = false, mode = RenderingMode.OFF_SCREEN),
            "non-macOS + OFF_SCREEN must be served by the AWT keymap",
        )
    }
}
