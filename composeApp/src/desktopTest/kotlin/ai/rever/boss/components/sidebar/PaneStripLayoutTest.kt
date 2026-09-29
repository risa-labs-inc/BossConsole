package ai.rever.boss.components.sidebar

import ai.rever.boss.window.TabBarPosition
import ai.rever.boss.window.usesNativeSidebarTitleBar
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaneStripLayoutTest {
    @Test
    fun `non mac platforms retain the original title bar for every tab position`() {
        TabBarPosition.entries.forEach { assertFalse(usesNativeSidebarTitleBar(false, it)) }
        assertTrue(usesNativeSidebarTitleBar(true, TabBarPosition.LEFT))
        assertFalse(usesNativeSidebarTitleBar(true, TabBarPosition.TOP))
    }
}
