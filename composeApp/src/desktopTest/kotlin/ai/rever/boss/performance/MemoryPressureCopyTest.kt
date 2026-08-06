package ai.rever.boss.performance

import ai.rever.boss.config.BossResourceMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Holds the memory-pressure notice's copy against the tier table it describes.
 *
 * This exists because the copy was wrong and nothing noticed. The dialog stated flatly that
 * "background performance sampling is off", while the only tier the watchdog ever applies is
 * LITE, which leaves sampling on - and the sampler is started once at boot and never stopped
 * mid-session regardless. `ResourceModeTest` pins the table and the Compose test pins that the
 * dialog renders, but nothing connected the prose to the data, so the two drifted freely.
 *
 * Any future "what changed" sentence belongs here rather than inline in the composable.
 */
class MemoryPressureCopyTest {
    @Test
    fun `the summary never claims sampling is off while the tier leaves it on`() {
        for (mode in BossResourceMode.entries) {
            val summary = changeSummary(mode)
            if (mode.backgroundSamplingEnabled) {
                assertFalse(
                    summary.contains("sampling", ignoreCase = true),
                    "${mode.name} leaves sampling on but the notice says: $summary",
                )
            }
        }
    }

    @Test
    fun `the summary mentions the browser cap exactly when the tier has one`() {
        for (mode in BossResourceMode.entries) {
            val summary = changeSummary(mode)
            val cap = mode.maxConcurrentBrowsers
            if (cap == null) {
                assertFalse(
                    summary.contains("browser", ignoreCase = true),
                    "${mode.name} is uncapped but the notice says: $summary",
                )
            } else {
                assertTrue(
                    summary.contains(cap.toString()),
                    "${mode.name} caps at $cap but the notice does not say so: $summary",
                )
            }
        }
    }

    /**
     * LITE is the only tier the watchdog can actually apply live, so its sentence is the one a
     * user will really see. Pinned concretely rather than by property, since this is the string
     * that was wrong.
     */
    @Test
    fun `the tier the watchdog applies describes only the browser cap`() {
        val summary = changeSummary(BossResourceMode.LITE)
        assertEquals(
            "at most ${BossResourceMode.LITE.maxConcurrentBrowsers} browsers can be open at once",
            summary,
        )
    }

    @Test
    fun `a tier that changes nothing live says so rather than inventing an effect`() {
        assertTrue(changeSummary(BossResourceMode.FULL).contains("nothing"))
    }
}
