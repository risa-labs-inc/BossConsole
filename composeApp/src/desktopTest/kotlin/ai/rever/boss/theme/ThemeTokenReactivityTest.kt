package ai.rever.boss.theme

import ai.rever.boss.plugin.icons.FileIcons
import ai.rever.boss.plugin.scrollbar.HorizontalBarScrollbarConfig
import ai.rever.boss.plugin.scrollbar.PanelScrollbarConfig
import ai.rever.boss.plugin.ui.BossThemeController
import ai.rever.boss.plugin.ui.BossThemes
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Regression guard for the stored-val → getter fixes in the token migration.
 *
 * These accessors froze their color at class-load before the migration, so a
 * theme switch left them on stale values. Each test flips the active theme and
 * asserts the accessor follows. If someone later "simplifies" a getter back to
 * a stored `val`, the class-load snapshot makes these fail deterministically.
 *
 * Operator (dark) and Daylight (light) are used because their textSecondary
 * values differ; if the palettes ever converge on that token, pick another
 * pair rather than deleting the guard.
 */
class ThemeTokenReactivityTest {

    private lateinit var originalThemeId: String

    @BeforeEach
    fun rememberTheme() {
        originalThemeId = BossThemeController.currentId
        BossThemeController.select(BossThemes.DEFAULT_ID)
    }

    @AfterEach
    fun restoreTheme() {
        BossThemeController.select(originalThemeId)
    }

    @Test
    fun `panel scrollbar indicator color follows the active theme`() {
        val operatorColor = PanelScrollbarConfig.indicatorColor

        BossThemeController.select("daylight")
        val daylightColor = PanelScrollbarConfig.indicatorColor

        assertNotEquals(operatorColor, daylightColor, "PanelScrollbarConfig froze its color at class-load")
        assertEquals(BossThemeController.current.colors.textSecondary, daylightColor)
    }

    @Test
    fun `horizontal bar scrollbar indicator color follows the active theme`() {
        val operatorColor = HorizontalBarScrollbarConfig.indicatorColor

        BossThemeController.select("daylight")
        val daylightColor = HorizontalBarScrollbarConfig.indicatorColor

        assertNotEquals(operatorColor, daylightColor, "HorizontalBarScrollbarConfig froze its color at class-load")
    }

    @Test
    fun `unknown file icon color follows the active theme`() {
        val operatorColor = FileIcons.Colors.unknown

        BossThemeController.select("daylight")
        val daylightColor = FileIcons.Colors.unknown

        assertNotEquals(operatorColor, daylightColor, "FileIcons.Colors.unknown froze its color at class-load")
    }

    @Test
    fun `selecting an unknown theme id is ignored`() {
        BossThemeController.select("no-such-theme")
        assertEquals(BossThemes.DEFAULT_ID, BossThemeController.currentId)
    }
}
