package ai.rever.boss.performance

import ai.rever.boss.config.BossResourceMode
import kotlin.test.Test
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

    /**
     * LITE is the only tier the watchdog can actually apply live, and since the browser ceiling
     * was retired in favour of hibernation there is nothing left that a mid-session tighten
     * changes for it. Saying "nothing yet" is the honest answer; inventing an effect here is the
     * mistake this file exists to prevent.
     */
    @Test
    fun `the tier the watchdog applies claims no live effect it cannot deliver`() {
        assertTrue(changeSummary(BossResourceMode.LITE).contains("nothing"))
    }

    @Test
    fun `a tier that changes nothing live says so rather than inventing an effect`() {
        assertTrue(changeSummary(BossResourceMode.FULL).contains("nothing"))
    }

    /**
     * The restart sentence drifted the same way the summary did, and for longer. It promised that
     * restarting in Ultra Lite "would also skip non-essential plugins on the way up", which
     * stopped being true the moment plugin gating was removed: no tier skips a plugin now.
     */
    @Test
    fun `the restart rationale never promises to skip plugins`() {
        for (mode in BossResourceMode.entries) {
            val rationale = restartRationale(mode)
            assertFalse(
                rationale.contains("plugin", ignoreCase = true),
                "${mode.name} promises plugin savings no tier delivers: $rationale",
            )
        }
    }

    @Test
    fun `the restart rationale names the limit the tier will actually apply`() {
        for (mode in BossResourceMode.entries) {
            val limit = mode.rendererProcessLimit ?: continue
            assertTrue(
                restartRationale(mode).contains(limit.toString()),
                "${mode.name} caps at $limit but says: ${restartRationale(mode)}",
            )
        }
    }

    /** FULL caps nothing, so it must not invent a number to quote. */
    @Test
    fun `an uncapped tier quotes no limit`() {
        val rationale = restartRationale(BossResourceMode.FULL)
        assertFalse(rationale.contains("capped"), rationale)
        assertTrue(rationale.isNotBlank())
    }
}
