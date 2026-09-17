package ai.rever.boss.plugin.sandbox.notification

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [shouldShowClearAllControl]: the "Clear all" toast control appears only once there is more
 * than one toast, because a lone toast's own dismiss button already clears everything.
 */
class PluginToastHostClearAllTest {
    @Test
    fun `no clear-all control for zero or one toast`() {
        assertFalse(shouldShowClearAllControl(0))
        assertFalse(shouldShowClearAllControl(1))
    }

    @Test
    fun `clear-all control appears from two toasts up`() {
        assertTrue(shouldShowClearAllControl(2))
        assertTrue(shouldShowClearAllControl(3))
    }
}
