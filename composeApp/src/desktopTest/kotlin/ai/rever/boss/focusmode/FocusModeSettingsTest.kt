package ai.rever.boss.focusmode

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for FocusModeSettings data class.
 *
 * Tests cover:
 * - Default values for new users
 * - Property validation
 */
class FocusModeSettingsTest {
    // ==================== DEFAULT VALUES TESTS ====================

    @Test
    fun `default settings should have focus mode disabled`() {
        val settings = FocusModeSettings()
        assertFalse(settings.enabled, "Focus mode should be disabled by default for new users")
    }

    @Test
    fun `default settings should have auto-reveal enabled`() {
        val settings = FocusModeSettings()
        assertTrue(settings.autoRevealEnabled, "Auto-reveal should be enabled by default")
    }

    @Test
    fun `default settings should have 30px reveal offset`() {
        val settings = FocusModeSettings()
        assertEquals(30f, settings.revealOffsetPx, "Default reveal offset should be 30px")
    }

    // ==================== CUSTOM VALUES TESTS ====================

    @Test
    fun `settings can be created with focus mode enabled`() {
        val settings = FocusModeSettings(enabled = true)
        assertTrue(settings.enabled, "Focus mode should be enabled when explicitly set")
    }

    @Test
    fun `settings can be created with auto-reveal disabled`() {
        val settings = FocusModeSettings(autoRevealEnabled = false)
        assertFalse(settings.autoRevealEnabled, "Auto-reveal should be disabled when explicitly set")
    }

    @Test
    fun `settings can be created with custom reveal offset`() {
        val settings = FocusModeSettings(revealOffsetPx = 25f)
        assertEquals(25f, settings.revealOffsetPx, "Reveal offset should match custom value")
    }

    // ==================== COPY TESTS ====================

    @Test
    fun `copy preserves unchanged values`() {
        val original = FocusModeSettings(enabled = true, autoRevealEnabled = false, revealOffsetPx = 20f)
        val copied = original.copy(enabled = false)

        assertFalse(copied.enabled, "Enabled should be updated")
        assertFalse(copied.autoRevealEnabled, "Auto-reveal should be preserved")
        assertEquals(20f, copied.revealOffsetPx, "Reveal offset should be preserved")
    }

    @Test
    fun `toggling focus mode preserves other settings`() {
        val original = FocusModeSettings(enabled = false, autoRevealEnabled = true, revealOffsetPx = 15f)
        val toggled = original.copy(enabled = !original.enabled)

        assertTrue(toggled.enabled, "Focus mode should be toggled on")
        assertTrue(toggled.autoRevealEnabled, "Auto-reveal should be preserved")
        assertEquals(15f, toggled.revealOffsetPx, "Reveal offset should be preserved")
    }

    // region platform defaults

    /**
     * Hover-to-reveal starts OFF on Windows because the mechanism cannot work there. Reveal is
     * driven by Compose `onPointerEvent(Enter/Exit)` on edge strips, and Windows runs the browser
     * in HARDWARE mode, where Chromium owns a foreign native window that composites over the
     * Compose scene. The OS routes pointer events to that window, so Compose never sees the
     * pointer reach an edge strip beneath the browser: a user in focus mode with a browser tab
     * open would sweep the edge and the bars would simply never come back.
     */
    @Test
    fun `windows starts with hover-to-reveal off`() {
        for (os in listOf("Windows 10", "Windows 11", "Windows Server 2022", "windows")) {
            assertFalse(FocusModeSettings.defaultAutoReveal(os), os)
            assertFalse(FocusModeSettings.defaultsFor(os).autoRevealEnabled, os)
        }
    }

    @Test
    fun `every other platform keeps hover-to-reveal on`() {
        for (os in listOf("Mac OS X", "macOS", "Linux", "FreeBSD", "SunOS", "")) {
            assertTrue(FocusModeSettings.defaultAutoReveal(os), os)
            assertTrue(FocusModeSettings.defaultsFor(os).autoRevealEnabled, os)
        }
    }

    /**
     * `"darwin"` contains `"win"`. A check written with `contains` would disable a feature on
     * macOS that works perfectly well there. The same trap is pinned in `ResourceModeTest` and
     * `JxBrowserRenderingModeTest`.
     */
    @Test
    fun `darwin is not windows`() {
        assertTrue(FocusModeSettings.defaultAutoReveal("darwin"))
    }

    /** Only the reveal default is platform-specific; focus mode itself stays off everywhere. */
    @Test
    fun `the platform default changes nothing else`() {
        val win = FocusModeSettings.defaultsFor("Windows 11")
        val mac = FocusModeSettings.defaultsFor("Mac OS X")
        assertFalse(win.enabled)
        assertFalse(mac.enabled)
        assertEquals(mac.revealOffsetPx, win.revealOffsetPx)
        assertEquals(mac.revealDelayMs, win.revealDelayMs)
    }

    // endregion
}
