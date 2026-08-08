package ai.rever.boss.window

import ai.rever.boss.config.BossResourceMode
import ai.rever.boss.config.ResourceModeConfig
import ai.rever.boss.config.ResourceModeReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the two label helpers behind View > Resource Mode.
 *
 * The submenu's whole job is to not lie. A radio list states an intent, and in three separate
 * situations the intent is not the current state - so the wording around the radios is the
 * feature, not decoration.
 */
class ResourceModeMenuTest {
    @Test
    fun `the running-as line names the live tier, not the selected one`() {
        assertEquals("Running as Ultra Lite", runningAsLabel(BossResourceMode.ULTRA_LITE))
        assertEquals("Running as Full", runningAsLabel(BossResourceMode.FULL))
    }

    @Test
    fun `every tier produces a running-as line`() {
        for (mode in BossResourceMode.entries) {
            val label = runningAsLabel(mode)
            assertTrue(label.startsWith("Running as "), mode.name)
            assertTrue(label.endsWith(mode.displayName), mode.name)
        }
    }

    /**
     * The environment override outranks a stored selection at resolve time, so clicking a radio
     * while it is set changes the file and nothing else. Saying "applies on the next launch"
     * there would be a promise the next launch does not keep.
     */
    @Test
    fun `an environment override says the selection is ignored`() {
        val label = applyHintLabel(ResourceModeReason.ENVIRONMENT_OVERRIDE, tightened = false)
        assertTrue(label.contains(ResourceModeConfig.MODE_KEY), label)
        assertTrue(label.contains("ignored"), label)
        assertFalse(label.contains("next launch"), label)
    }

    /** The override wins even when the watchdog has also tightened the live tier. */
    @Test
    fun `an environment override outranks the tightened wording`() {
        assertEquals(
            applyHintLabel(ResourceModeReason.ENVIRONMENT_OVERRIDE, tightened = false),
            applyHintLabel(ResourceModeReason.ENVIRONMENT_OVERRIDE, tightened = true),
        )
    }

    @Test
    fun `a mid-session tightening is called out rather than left unexplained`() {
        val label = applyHintLabel(ResourceModeReason.USER_SELECTION, tightened = true)
        assertTrue(label.contains("low memory"), label)
        assertTrue(label.contains("next launch"), label)
    }

    @Test
    fun `the ordinary case just says when it applies`() {
        for (reason in ResourceModeReason.entries - ResourceModeReason.ENVIRONMENT_OVERRIDE) {
            assertEquals(
                "Applies on the next launch",
                applyHintLabel(reason, tightened = false),
                reason.name,
            )
        }
    }

    /** No hint may ever read as "done", since none of them are applied at click time. */
    @Test
    fun `no hint claims the change is already in effect`() {
        for (reason in ResourceModeReason.entries) {
            for (tightened in listOf(true, false)) {
                val label = applyHintLabel(reason, tightened)
                assertTrue(label.isNotBlank(), reason.name)
                assertFalse(label.contains("now", ignoreCase = true), label)
                assertFalse(label.contains("applied", ignoreCase = true), label)
            }
        }
    }
}
