package ai.rever.boss.config

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Holds each tier's Settings copy against what the tier actually does.
 *
 * The strings here are the only description most users will ever read, and they drifted once
 * already: every tier advertised "idle browser tabs sleep after N minutes" while
 * [BossResourceMode.hibernationIdleMs] had no consumer anywhere in the app, so nobody's tabs
 * slept at all. A promise of a feature that is merely planned is indistinguishable, from the
 * user's side, from a feature that is broken.
 *
 * The rule enforced here is narrow and mechanical: copy may not describe a lever this repo does
 * not yet act on. When the plugin starts reading [ResourceModeConfig.HIBERNATION_IDLE_PROPERTY],
 * relax the hibernation assertion below and say so in the summaries.
 */
class ResourceModeCopyTest {
    @Test
    fun `no tier summary promises hibernation while nothing consumes the timing`() {
        for (mode in BossResourceMode.entries) {
            for (word in listOf("sleep", "hibernat", "idle")) {
                assertFalse(
                    mode.summary.contains(word, ignoreCase = true),
                    "${mode.name} promises hibernation that nothing applies yet: ${mode.summary}",
                )
            }
        }
    }

    @Test
    fun `only the tier that disables sampling mentions it`() {
        for (mode in BossResourceMode.entries) {
            val mentions = mode.summary.contains("sampling", ignoreCase = true)
            assertTrue(
                mentions == !mode.backgroundSamplingEnabled,
                "${mode.name} sampling=${mode.backgroundSamplingEnabled} but says: ${mode.summary}",
            )
        }
    }

    @Test
    fun `every tier has a non-empty summary and display name`() {
        for (mode in BossResourceMode.entries) {
            assertTrue(mode.summary.isNotBlank(), mode.name)
            assertTrue(mode.displayName.isNotBlank(), mode.name)
            assertFalse(mode.displayName.contains('_'), "${mode.name} leaks the shouty enum name")
        }
    }

    /**
     * FULL is the unreduced tier, so its copy must not describe a restriction. A user reading the
     * dropdown should be able to tell which option costs them nothing.
     */
    @Test
    fun `the unreduced tier describes no restriction`() {
        val summary = BossResourceMode.FULL.summary
        for (word in listOf("fewer", "fewest", "limit", "off")) {
            assertFalse(summary.contains(word, ignoreCase = true), summary)
        }
    }
}
