package ai.rever.boss.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Guards how `~/.boss/resource-mode.json` is parsed.
 *
 * Deliberately exercises [ResourceModeSettings.decode] rather than the file itself. This object
 * resolves a real path under the developer's home directory, so a test that wrote there would
 * pollute their install and become order-dependent against anything else touching the same file.
 */
class ResourceModeSettingsTest {
    @Test
    fun `a written document reads back unchanged`() {
        val original =
            ResourceModeSettingsData(
                selectedMode = "LITE",
                nextLaunchMode = "ULTRA_LITE",
                liteThresholdGb = 24,
                ultraLiteThresholdGb = 12,
                livePressureEnabled = false,
            )
        assertEquals(original, ResourceModeSettings.decode(ResourceModeSettings.encode(original)))
    }

    @Test
    fun `an empty document is all defaults`() {
        val decoded = ResourceModeSettings.decode("{}")
        assertNull(decoded.selectedMode)
        assertNull(decoded.nextLaunchMode)
        assertEquals(ResourceModeConfig.DEFAULT_LITE_THRESHOLD_GB, decoded.liteThresholdGb)
        assertEquals(ResourceModeConfig.DEFAULT_ULTRA_LITE_THRESHOLD_GB, decoded.ultraLiteThresholdGb)
    }

    /**
     * A truncated or corrupt file must not take the app down, and must not be read as a
     * deliberate choice either: defaults mean Auto, which resolves per machine.
     */
    @Test
    fun `a corrupt document falls back to defaults rather than throwing`() {
        for (raw in listOf("", "   ", "{", "not json at all", """{"selectedMode":}""")) {
            assertEquals(ResourceModeSettingsData(), ResourceModeSettings.decode(raw), "for: $raw")
        }
    }

    /**
     * The additive-migration case. A file written by a newer build carries fields this one does
     * not model, and strict decoding would throw on the lot - the failure mode AGENTS.md
     * documents for the Supabase models, where one unmodelled column emptied whole lists.
     */
    @Test
    fun `an unknown field written by a newer build is ignored, not fatal`() {
        val decoded =
            ResourceModeSettings.decode(
                """{"selectedMode":"LITE","somethingFromTheFuture":true,"nested":{"a":1}}""",
            )
        assertEquals("LITE", decoded.selectedMode)
    }

    /**
     * The reverse direction: a file written *before* `nextLaunchMode` existed must decode to a
     * null one-shot, not to a pending restart request nobody made.
     */
    @Test
    fun `a document from before the one-shot field decodes with no pending request`() {
        val decoded = ResourceModeSettings.decode("""{"selectedMode":"FULL","liteThresholdGb":16}""")
        assertEquals("FULL", decoded.selectedMode)
        assertNull(decoded.nextLaunchMode)
    }

    /**
     * The one-shot must be a separate field from the selection. Sharing one field is what made a
     * single click under memory pressure the permanent tier, and made Settings attribute it to
     * the user.
     */
    @Test
    fun `the one-shot request does not disturb the stored selection`() {
        val stored = ResourceModeSettingsData(selectedMode = "FULL")
        val afterRequest = stored.copy(nextLaunchMode = "ULTRA_LITE")
        assertEquals("FULL", afterRequest.selectedMode)

        val afterConsumption = afterRequest.copy(nextLaunchMode = null)
        assertEquals(stored, afterConsumption)
    }
}
