@file:Suppress("PackageNaming")

package ai.rever.boss.components.tabs_navigation

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [TabsNavigation.addTab]'s `activate` parameter.
 *
 * Added for BossConsole's runner `focusOnRun` setting: a caller that wants a new tab to exist
 * and run without stealing focus from whatever the user is already looking at needs `addTab`
 * itself to leave `activeIndex` alone - a `selectTab` call made conditionally right after an
 * unconditional `addTab` is not equivalent, because `addTab` had already moved `activeIndex` to
 * the new tab before that condition was ever checked.
 */
class TabsNavigationTest {
    @Test
    fun `addTab activates the new tab by default`() {
        val nav = TabsNavigation<String>()
        nav.addTab("first")
        val secondIndex = nav.addTab("second")

        assertEquals(secondIndex, nav.state.value.activeIndex)
    }

    @Test
    fun `addTab with activate false leaves the previously active tab active`() {
        val nav = TabsNavigation<String>()
        val firstIndex = nav.addTab("first")

        nav.addTab("second", activate = false)

        assertEquals(firstIndex, nav.state.value.activeIndex)
        assertEquals(listOf("first", "second"), nav.state.value.tabs)
    }

    @Test
    fun `an unactivated tab added to an empty list leaves nothing active`() {
        val nav = TabsNavigation<String>()

        nav.addTab("only", activate = false)

        assertEquals(-1, nav.state.value.activeIndex)
        assertEquals(listOf("only"), nav.state.value.tabs)
    }

    @Test
    fun `several unactivated adds never move activeIndex off the original tab`() {
        val nav = TabsNavigation<String>()
        val firstIndex = nav.addTab("first")

        nav.addTab("second", activate = false)
        nav.addTab("third", activate = false)
        nav.addTab("fourth", activate = false)

        assertEquals(firstIndex, nav.state.value.activeIndex)
        assertEquals("first", nav.state.value.activeTab)
    }
}
