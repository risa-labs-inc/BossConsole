package ai.rever.boss.window

import ai.rever.boss.layout.ChromeDensity
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [defaultDensityFor], the pure decision behind a fresh install's screen-size default
 * (issue #239). [WindowAppearanceSettingsManager.getDefaultSettings] is the only caller, and it is
 * the one that reads `Toolkit` - this is what stays testable without a display.
 */
class WindowAppearanceSettingsManagerDensityTest {
    @Test
    fun `a 13 inch Air's own numbers land on Compact`() {
        // The reference machine from issue #239: 956pt full screen height, 931pt once the macOS
        // menu bar is subtracted. Both are the case this default exists to catch.
        assertEquals(ChromeDensity.COMPACT, defaultDensityFor(956))
        assertEquals(ChromeDensity.COMPACT, defaultDensityFor(931))
    }

    @Test
    fun `a taller laptop or desktop display stays Comfortable`() {
        assertEquals(ChromeDensity.COMFORTABLE, defaultDensityFor(1000))
        assertEquals(ChromeDensity.COMFORTABLE, defaultDensityFor(1117))
        assertEquals(ChromeDensity.COMFORTABLE, defaultDensityFor(1440))
    }

    @Test
    fun `an unreadable screen size is not treated as small`() {
        // Toolkit throws HeadlessException off a display; a null must not crash or misconfigure a
        // fresh install just because the height could not be measured.
        assertEquals(ChromeDensity.COMFORTABLE, defaultDensityFor(null))
    }
}
