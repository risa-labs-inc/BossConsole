package ai.rever.boss.plugin.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [alertHeaderSpacerVisible] (issue #143): the spacer above the button row exists only to
 * separate the buttons from a title or a body, so a buttons-only card must not carry a dead gap
 * above a lone action row.
 */
class BossAlertCardSpacerTest {
    @Test
    fun `a buttons-only card omits the pre-button spacer`() {
        assertFalse(alertHeaderSpacerVisible(hasTitle = false, hasText = false))
    }

    @Test
    fun `a title or a body keeps the pre-button spacer`() {
        assertTrue(alertHeaderSpacerVisible(hasTitle = true, hasText = false))
        assertTrue(alertHeaderSpacerVisible(hasTitle = false, hasText = true))
        assertTrue(alertHeaderSpacerVisible(hasTitle = true, hasText = true))
    }
}
