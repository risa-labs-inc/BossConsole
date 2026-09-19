package ai.rever.boss.performance

import kotlin.test.Test
import kotlin.test.assertEquals

class PerformanceSettingsValidationTest {
    @Test
    fun `heap validation clamps corrupt values without discarding other settings`() {
        val validated =
            PerformanceSettings(
                showIndicator = false,
                historyRetentionMinutes = 45,
                pluginJvmHeapMb = 0,
                pluginJvmInitialHeapMb = 4096,
            ).validated()

        assertEquals(128, validated.pluginJvmHeapMb)
        assertEquals(128, validated.pluginJvmInitialHeapMb)
        assertEquals(false, validated.showIndicator)
        assertEquals(45, validated.historyRetentionMinutes)
    }
}
