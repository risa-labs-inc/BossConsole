package ai.rever.boss.components.window_panel

import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.components.overlays.isNativeRepresentable
import ai.rever.boss.components.window_panel.components.main_window_panels.rememberBarMenuItems
import ai.rever.boss.window.WindowAppearanceSettingsManager
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the menu a tab surface offers on its own empty space.
 *
 * Two surfaces show it - the vertical bar's tab list and the favicon strip across each pane - and
 * they are meant to be the same menu, differing only in which pane "New Tab" lands in. That is
 * the whole reason it was hoisted out of `rememberTabBarState`, so the thing worth pinning is
 * that one call really does answer for both.
 *
 * Reads only. Nothing here writes settings, because [WindowAppearanceSettingsManager] persists to
 * the real `~/.boss` file and a test that flipped a toggle would be editing the machine it runs on.
 */
class BarMenuItemsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun menu(): List<ContextMenuItem> {
        lateinit var items: List<ContextMenuItem>
        compose.setContent { items = rememberBarMenuItems(openNewTab = {}) }
        compose.waitForIdle()
        return items
    }

    @Test
    fun `the strip can be hidden from the menu, and shown again from it`() {
        val labels = menu().mapNotNull { it.text.takeIf { text -> text.isNotBlank() } }

        // Worded as the action, and present whichever way the setting currently sits. Offering it
        // only on the strip would be a one-way door: once hidden, the strip has no empty space
        // left to right-click, and the way back would be Settings.
        assertEquals(
            1,
            labels.count { it == "Hide Pane Tab Strip" || it == "Show Pane Tab Strip" },
            "expected exactly one pane-strip toggle, got: $labels",
        )
    }

    @Test
    fun `the menu carries the bar's own entries too`() {
        val labels = menu().map { it.text }

        assertTrue("New Tab" in labels, "expected New Tab, got: $labels")
        assertTrue("Tab Bar Position" in labels, "expected the position submenu, got: $labels")
    }

    @Test
    fun `the menu stays native-representable`() {
        // A trailing icon disqualifies the whole menu from the native NSMenu path on macOS, and
        // on the drawn path it would be painted BEHIND the browser's native surface on Windows.
        // That is why the position submenu spells its checkmark into the label instead, and why
        // the pane-strip toggle is worded rather than ticked. A new entry with a trailing icon
        // would silently take the menu off that path.
        assertTrue(
            menu().isNativeRepresentable(),
            "the empty-space menu must stay representable as a native menu",
        )
    }
}
