package ai.rever.boss.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Moving existing installs onto the left tab bar and the hidden top bar.
 *
 * Changing the DEFAULT alone would not have done it: the manager writes the whole object on every
 * save, so an existing file names both values explicitly and would keep them for ever. The new
 * defaults would have reached new installs only - which is not what "by default" means to someone
 * who already has BOSS open.
 */
class WindowAppearanceMigrationsTest {
    @Test
    fun `a current file is left alone`() {
        val current = WindowAppearanceSettings(settingsVersion = WindowAppearanceSettings.CURRENT_SETTINGS_VERSION)
        assertNull(WindowAppearanceMigrations.migrate(current))
    }

    @Test
    fun `an install on the old shipped defaults is moved`() {
        val old = WindowAppearanceSettings(showTopBar = true, tabBarPosition = TabBarPosition.TOP, settingsVersion = 0)
        val migrated = WindowAppearanceMigrations.migrate(old)!!

        assertEquals(false, migrated.showTopBar)
        assertEquals(TabBarPosition.LEFT, migrated.tabBarPosition)
    }

    @Test
    fun `someone already on the left bar keeps every other choice`() {
        // Not on the old defaults, so nothing is decided for them - including the top bar they
        // chose to keep.
        val chosen =
            WindowAppearanceSettings(
                showTopBar = true,
                tabBarPosition = TabBarPosition.LEFT,
                settingsVersion = 0,
            )
        val migrated = WindowAppearanceMigrations.migrate(chosen)!!

        assertEquals(true, migrated.showTopBar)
        assertEquals(TabBarPosition.LEFT, migrated.tabBarPosition)
    }

    @Test
    fun `someone who hid the top bar but kept top tabs is left alone`() {
        val chosen =
            WindowAppearanceSettings(
                showTopBar = false,
                tabBarPosition = TabBarPosition.TOP,
                settingsVersion = 0,
            )
        val migrated = WindowAppearanceMigrations.migrate(chosen)!!

        assertEquals(false, migrated.showTopBar)
        assertEquals(TabBarPosition.TOP, migrated.tabBarPosition)
    }

    @Test
    fun `an out-of-date file is stamped even when nothing else changes`() {
        // Otherwise the step re-runs on every launch and would keep re-deciding for someone who
        // moved back afterwards.
        val chosen = WindowAppearanceSettings(showTopBar = false, tabBarPosition = TabBarPosition.TOP)
        val migrated = WindowAppearanceMigrations.migrate(chosen)!!

        assertEquals(WindowAppearanceSettings.CURRENT_SETTINGS_VERSION, migrated.settingsVersion)
        assertNull(WindowAppearanceMigrations.migrate(migrated))
    }

    @Test
    fun `every other appearance choice survives the move`() {
        val old =
            WindowAppearanceSettings(
                showTopBar = true,
                tabBarPosition = TabBarPosition.TOP,
                showBottomBar = false,
                tabBarVerticalWidth = 260f,
                tabBarCollapsed = true,
                settingsVersion = 0,
            )
        val migrated = WindowAppearanceMigrations.migrate(old)!!

        assertEquals(false, migrated.showBottomBar)
        assertEquals(260f, migrated.tabBarVerticalWidth)
        assertTrue(migrated.tabBarCollapsed)
    }

    @Test
    fun `a fresh install comes up on the new defaults`() {
        val fresh = WindowAppearanceSettings()

        assertEquals(false, fresh.showTopBar)
        assertEquals(TabBarPosition.LEFT, fresh.tabBarPosition)
    }
}
