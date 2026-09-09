package ai.rever.boss.components.plugin

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the plugin id BossConsole#71 exists for, and the boundary of what counts as
 * not-hot-reloadable, so a typo in either silently reopens the bug.
 */
class HotReloadPolicyTest {
    @Test
    fun `fluck-browser is not hot-reloadable`() {
        assertTrue(HotReloadPolicy.requiresRestartInsteadOfHotReload("ai.rever.boss.plugin.dynamic.fluckbrowser"))
    }

    @Test
    fun `an ordinary plugin is hot-reloadable`() {
        assertFalse(HotReloadPolicy.requiresRestartInsteadOfHotReload("ai.rever.boss.plugin.dynamic.terminaltab"))
    }

    @Test
    fun `an unknown id is hot-reloadable by default`() {
        // Fails open on purpose: NOT_HOT_RELOADABLE is an allowlist of confirmed native-surface
        // owners, not a claim that every other plugin has been checked and is safe.
        assertFalse(HotReloadPolicy.requiresRestartInsteadOfHotReload("some.made.up.plugin"))
    }
}
