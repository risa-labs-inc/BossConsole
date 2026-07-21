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
}
